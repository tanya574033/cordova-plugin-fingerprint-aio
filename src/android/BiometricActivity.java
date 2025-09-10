package de.niklasmerz.cordova.biometric;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import android.util.Log;
import android.os.SystemClock;

import java.util.concurrent.Executor;

import javax.crypto.Cipher;

public class BiometricActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS = 2;
    private PromptInfo mPromptInfo;
    private CryptographyManager mCryptographyManager;
    private static final String SECRET_KEY = "__aio_secret_key";
    private BiometricPrompt mBiometricPrompt;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private boolean mLaunchingDeviceCredential = false;
    private boolean mSuppressCancelError = false; // ignore ERROR_CANCELED while we're handing off to Keyguard
    private int mFailedAttempts = 0; // counts both face + fingerprint failures
    private static final String TAG = "FAIO";
    // Handoff guard (avoid double-Launching Keyguard), and optional watchdog state
    private boolean mHandoffScheduled = false;
    private long mLastEventAt = 0L;
    private Runnable mAttemptWatchdog = null;
    
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(null);
        int layout = getResources()
                .getIdentifier("biometric_activity", "layout", getPackageName());
        setContentView(layout);

        if (savedInstanceState != null) {
            return;
        }

        mCryptographyManager = new CryptographyManagerImpl();
        mPromptInfo = new PromptInfo.Builder(getIntent().getExtras()).build();
        final Handler handler = new Handler(Looper.getMainLooper());
        Executor executor = handler::post;
        mBiometricPrompt = new BiometricPrompt(this, executor, mAuthenticationCallback);
        try {
            authenticate();
        } catch (CryptoException e) {
            finishWithError(e);
        } catch (Exception e) {
            finishWithError(PluginError.BIOMETRIC_UNKNOWN_ERROR, e.getMessage());
        }
    }

    private void authenticate() throws CryptoException {
        switch (mPromptInfo.getType()) {
          case JUST_AUTHENTICATE:
            justAuthenticate();
            return;
          case REGISTER_SECRET:
            authenticateToEncrypt(mPromptInfo.invalidateOnEnrollment());
            return;
          case LOAD_SECRET:
            authenticateToDecrypt();
            return;
        }
        throw new CryptoException(PluginError.BIOMETRIC_ARGS_PARSING_FAILED);
    }

    private void authenticateToEncrypt(boolean invalidateOnEnrollment) throws CryptoException {
        if (mPromptInfo.getSecret() == null) {
            throw new CryptoException(PluginError.BIOMETRIC_ARGS_PARSING_FAILED);
        }
        Cipher cipher = mCryptographyManager
                .getInitializedCipherForEncryption(SECRET_KEY, invalidateOnEnrollment, this);
        mBiometricPrompt.authenticate(createPromptInfo(), new BiometricPrompt.CryptoObject(cipher));
        startAttemptWatchdogIfNeeded();
    }

    private void justAuthenticate() {
        mBiometricPrompt.authenticate(createPromptInfo());
        startAttemptWatchdogIfNeeded();
    }

    private void authenticateToDecrypt() throws CryptoException {
        byte[] initializationVector = EncryptedData.loadInitializationVector(this);
        Cipher cipher = mCryptographyManager
                .getInitializedCipherForDecryption(SECRET_KEY, initializationVector, this);
        mBiometricPrompt.authenticate(createPromptInfo(), new BiometricPrompt.CryptoObject(cipher));
        startAttemptWatchdogIfNeeded();
    }

    private BiometricPrompt.PromptInfo createPromptInfo() {
        BiometricPrompt.PromptInfo.Builder promptInfoBuilder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(mPromptInfo.getTitle())
                .setSubtitle(mPromptInfo.getSubtitle())
                .setConfirmationRequired(mPromptInfo.getConfirmationRequired())
                .setDescription(mPromptInfo.getDescription());

        if (mPromptInfo.isDeviceCredentialAllowed()
                && mPromptInfo.getType() == BiometricActivityType.JUST_AUTHENTICATE
                && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            // NOTE: This mode forbids a negative button.
            //noinspection deprecation
            promptInfoBuilder.setDeviceCredentialAllowed(true);
        } else {
            String negativeText = mPromptInfo.isDeviceCredentialAllowed()
                    ? mPromptInfo.getFallbackButtonTitle()
                    : mPromptInfo.getCancelButtonTitle();
            promptInfoBuilder.setNegativeButtonText(negativeText);
        }

        return promptInfoBuilder.build();
    }

    private BiometricPrompt.AuthenticationCallback mAuthenticationCallback =
            new BiometricPrompt.AuthenticationCallback() {

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        Log.d(TAG, "onError code=" + errorCode + " msg=" + String.valueOf(errString)
                                + " launchingKeyguard=" + mLaunchingDeviceCredential
                                + " suppress=" + mSuppressCancelError);
                    super.onAuthenticationError(errorCode, errString);
                    recordProgress();
                    onError(errorCode, errString);
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    stopAttemptWatchdog();
                    try {
                        finishWithSuccess(result.getCryptoObject());
                    } catch (CryptoException e) {
                        finishWithError(e);
                    }
                }

                @Override
                public void onAuthenticationFailed() {
                    Log.d(TAG, "failed++ -> " + (mFailedAttempts+1) + " / limit=" + mPromptInfo.getMaxAttempts());
                    
                    super.onAuthenticationFailed();
                    recordProgress();
                    mFailedAttempts++;
                    int limit = mPromptInfo.getMaxAttempts();
                    if (limit > 0 && mFailedAttempts >= limit) {
                        Log.d(TAG, "limit reached -> schedule handoff to Keyguard");
                        if (mPromptInfo.isDeviceCredentialAllowed()) {
                            scheduleHandoffToKeyguard();
                        } else {
                            stopAttemptWatchdog();
                            finishWithError(PluginError.BIOMETRIC_LOCKED_OUT);
                        }
                    }
                }
            };

    /** Dismiss BiometricPrompt and reliably launch Keyguard (PIN/Pattern/Password), avoiding double-launch. */
    private void scheduleHandoffToKeyguard() {
        if (mHandoffScheduled) return;
        mHandoffScheduled = true;
        mSuppressCancelError = true;
        try { mBiometricPrompt.cancelAuthentication(); } catch (Exception ignored) {}
        // Primary attempt after short delay to let the prompt dismiss
        mUi.postDelayed(() -> {
            Log.d(TAG, "handoff->Keyguard t1");
            if (!mLaunchingDeviceCredential) launchDeviceCredential();
        }, 400);
        // Safety attempt in case some OEM face UIs are sticky
        mUi.postDelayed(() -> {
            Log.d(TAG, "handoff->Keyguard t2 (watchdog)");
            if (!mLaunchingDeviceCredential) launchDeviceCredential();
        }, 1200);
    }

    // --- Optional "silent-period" watchdog (disabled when attemptWindowMs == 0) ---
    private void recordProgress() { mLastEventAt = SystemClock.uptimeMillis(); }
    private void startAttemptWatchdogIfNeeded() {
        int window = mPromptInfo.getAttemptWindowMs();
        if (window <= 0) return;
        recordProgress();
        if (mAttemptWatchdog == null) mAttemptWatchdog = this::watchdogTick;
        mUi.removeCallbacks(mAttemptWatchdog);
        mUi.postDelayed(mAttemptWatchdog, window);
    }
    private void stopAttemptWatchdog() {
        if (mAttemptWatchdog != null) mUi.removeCallbacks(mAttemptWatchdog);
    }
    private void watchdogTick() {
        int window = mPromptInfo.getAttemptWindowMs();
        if (window <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastEventAt >= window) {
            Log.d(TAG, "attempt watchdog -> count as failed");
            mAuthenticationCallback.onAuthenticationFailed();
            return;
        }
        mUi.postDelayed(mAttemptWatchdog, window);
    }

    private void launchDeviceCredential() {
        Log.d(TAG, "launchDeviceCredential()");
        KeyguardManager keyguardManager = ContextCompat
                .getSystemService(this, KeyguardManager.class);
        if (keyguardManager == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            finishWithError(PluginError.BIOMETRIC_UNKNOWN_ERROR);
            return;
        }
        if (!keyguardManager.isKeyguardSecure()) {
            finishWithError(PluginError.BIOMETRIC_SCREEN_GUARD_UNSECURED);
            return;
        }
        Intent intent = keyguardManager
                .createConfirmDeviceCredentialIntent(mPromptInfo.getTitle(), mPromptInfo.getDescription());
        if (intent != null) {
            mLaunchingDeviceCredential = true; // already transitioning
            stopAttemptWatchdog();
            this.startActivityForResult(intent, REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS);
        } else {
            finishWithError(PluginError.BIOMETRIC_UNKNOWN_ERROR);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CONFIRM_DEVICE_CREDENTIALS) {
            Log.d(TAG, "Keyguard result=" + (resultCode == Activity.RESULT_OK ? "OK" : "CANCELED"));
            if (resultCode == Activity.RESULT_OK) {
                finishWithSuccess();
            } else {
                finishWithError(PluginError.BIOMETRIC_PIN_OR_PATTERN_DISMISSED);
            }
            // We're done with the handoff; re-enable normal cancel handling.
            mLaunchingDeviceCredential = false;
            mSuppressCancelError = false;
            mHandoffScheduled = false;
            stopAttemptWatchdog();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void onError(int errorCode, @NonNull CharSequence errString) {

        switch (errorCode) {
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_CANCELED:
                // If we intentionally canceled to launch Keyguard, ignore this edge callback.
                if (mSuppressCancelError || mLaunchingDeviceCredential) {
                    return;
                } else {
                    stopAttemptWatchdog();
                    finishWithError(PluginError.BIOMETRIC_DISMISSED);
                    return;
                }
            case BiometricPrompt.ERROR_TIMEOUT:
                // Count face timeouts as failed attempts to progress toward fallback
                Log.d(TAG, "timeout -> count as failed");
                mAuthenticationCallback.onAuthenticationFailed();
                return;
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                if (mPromptInfo.isDeviceCredentialAllowed()) {
                    scheduleHandoffToKeyguard();
                    return;
                }
                stopAttemptWatchdog();
                finishWithError(PluginError.BIOMETRIC_DISMISSED);
                return;
            case BiometricPrompt.ERROR_LOCKOUT:
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                if (mPromptInfo.isDeviceCredentialAllowed()) {
                    scheduleHandoffToKeyguard();
                    return;
                }
                if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                    stopAttemptWatchdog();
                    finishWithError(PluginError.BIOMETRIC_LOCKED_OUT.getValue(), errString.toString());
                } else {
                    stopAttemptWatchdog();
                    finishWithError(PluginError.BIOMETRIC_LOCKED_OUT_PERMANENT.getValue(), errString.toString());
                }
                return;
            default:
                stopAttemptWatchdog();
                finishWithError(errorCode, errString.toString());
        }
    }

    private void finishWithSuccess() {
        setResult(RESULT_OK);
        finish();
    }

    private void finishWithSuccess(BiometricPrompt.CryptoObject cryptoObject) throws CryptoException {
        Intent intent = null;
        switch (mPromptInfo.getType()) {
          case REGISTER_SECRET:
            encrypt(cryptoObject);
            break;
          case LOAD_SECRET:
            intent = getDecryptedIntent(cryptoObject);
            break;
        }
        if (intent == null) {
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_OK, intent);
        }
        finish();
    }

    private void encrypt(BiometricPrompt.CryptoObject cryptoObject) throws CryptoException {
        String text = mPromptInfo.getSecret();
        EncryptedData encryptedData = mCryptographyManager.encryptData(text, cryptoObject.getCipher());
        encryptedData.save(this);
    }

    private Intent getDecryptedIntent(BiometricPrompt.CryptoObject cryptoObject) throws CryptoException {
        byte[] ciphertext = EncryptedData.loadCiphertext(this);
        String secret = mCryptographyManager.decryptData(ciphertext, cryptoObject.getCipher());
        if (secret != null) {
            Intent intent = new Intent();
            intent.putExtra(PromptInfo.SECRET_EXTRA, secret);
            return intent;
        }
        return null;
    }

    private void finishWithError(CryptoException e) {
        finishWithError(e.getError().getValue(), e.getMessage());
    }

    private void finishWithError(PluginError error) {
        finishWithError(error.getValue(), error.getMessage());
    }

    private void finishWithError(PluginError error, String message) {
        finishWithError(error.getValue(), message);
    }

    private void finishWithError(int code, String message) {
        Intent data = new Intent();
        data.putExtra("code", code);
        data.putExtra("message", message);
        setResult(RESULT_CANCELED, data);
        finish();
    }
}
