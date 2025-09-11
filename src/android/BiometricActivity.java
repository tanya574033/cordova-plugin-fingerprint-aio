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
import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;
import android.util.Log;

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
    // Handoff guard (avoid double-Launching Keyguard)
    private boolean mHandoffScheduled = false;

    private final long attemptWindowMs = 10000L; // conservative for face
    private final Runnable mWatchdog = () -> {
        Log.d(TAG, "watchdog -> schedule handoff to Keyguard");
        if (mPromptInfo.isDeviceCredentialAllowed()) {
            scheduleHandoffToKeyguard();
        } else {
            finishWithError(PluginError.BIOMETRIC_LOCKED_OUT);
        }
    };

    private void resetWatchdog() {
        if (attemptWindowMs <= 0) return;
        mUi.removeCallbacks(mWatchdog);
        mUi.postDelayed(mWatchdog, attemptWindowMs);
    }

    private void stopWatchdog() {
        mUi.removeCallbacks(mWatchdog);
    }
    
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
        // Q / ≤P: if biometrics aren't usable right now (incl. throttle), go straight to PIN
        if (precheckLockoutLegacy()) {
            return;
        }
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
    }

    private void justAuthenticate() {
        mBiometricPrompt.authenticate(createPromptInfo());
    }

    private void authenticateToDecrypt() throws CryptoException {
        byte[] initializationVector = EncryptedData.loadInitializationVector(this);
        Cipher cipher = mCryptographyManager
                .getInitializedCipherForDecryption(SECRET_KEY, initializationVector, this);
        mBiometricPrompt.authenticate(createPromptInfo(), new BiometricPrompt.CryptoObject(cipher));
    }

    private BiometricPrompt.PromptInfo createPromptInfo() {
        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
            .setTitle(mPromptInfo.getTitle())
            .setSubtitle(mPromptInfo.getSubtitle())
            .setConfirmationRequired(mPromptInfo.getConfirmationRequired())
            .setDescription(mPromptInfo.getDescription());

        boolean allowBackup = mPromptInfo.isDeviceCredentialAllowed()
                && mPromptInfo.getType() == BiometricActivityType.JUST_AUTHENTICATE;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int allowed;
            if (mPromptInfo.getType() == BiometricActivityType.JUST_AUTHENTICATE) {
                // ✅ Use WEAK so Face (Class-2) and Fingerprint (Class-3) are both eligible.
                allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK;
                if (allowBackup) {
                    allowed |= BiometricManager.Authenticators.DEVICE_CREDENTIAL;
                }
            } else {
                // Crypto flows: STRONG only. Do NOT add DEVICE_CREDENTIAL with CryptoObject.
                allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG;
            }
            b.setAllowedAuthenticators(allowed);

            // Only set negative if DEVICE_CREDENTIAL is NOT present
            boolean hasCredential = (allowed & BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0;
            if (!hasCredential && mPromptInfo.getType() == BiometricActivityType.JUST_AUTHENTICATE) {
                b.setNegativeButtonText(mPromptInfo.getCancelButtonTitle());
            }
            return b.build();
        }

        // Android 10 (Q)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            if (allowBackup) {
                //noinspection deprecation
                b.setDeviceCredentialAllowed(true); // No negative button
            } else {
                b.setNegativeButtonText(mPromptInfo.getCancelButtonTitle());
            }
            return b.build();
        }

        // Android 9 (P) and below: legacy negative-button flow
        if (allowBackup) {
            b.setNegativeButtonText(mPromptInfo.getFallbackButtonTitle());
        } else {
            b.setNegativeButtonText(mPromptInfo.getCancelButtonTitle());
        }
        return b.build();
    }

    /**
     * Only allow device-credential fallback for JUST_AUTHENTICATE when backup is enabled.
     * Crypto flows must not fall back to credential.
     */
    private boolean canUseCredentialFallback() {
        return mPromptInfo.isDeviceCredentialAllowed()
                && mPromptInfo.getType() == BiometricActivityType.JUST_AUTHENTICATE;
    }

    /**
     * Legacy pre-check for Android 10 (Q) and below.
     * Some OEM UIs show "too many attempts" without emitting ERROR_LOCKOUT callbacks.
     * If biometrics aren't usable now and credential fallback is allowed, hand off to Keyguard.
     * (On R+ the framework routes inline via allowedAuthenticators; no pre-check needed.)
     */
    private boolean precheckLockoutLegacy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false;
        if (!canUseCredentialFallback()) return false;
        try {
            int status = BiometricManager.from(this).canAuthenticate(); // legacy API (no flags)
            if (status != BiometricManager.BIOMETRIC_SUCCESS) {
                scheduleHandoffToKeyguard();
                return true;
            }
        } catch (Throwable t) {
            Log.d(TAG, "precheckLockoutLegacy() ignored: " + t.getMessage());
        }
        return false;
    }

    private BiometricPrompt.AuthenticationCallback mAuthenticationCallback =
            new BiometricPrompt.AuthenticationCallback() {

                @Override
                public void onAuthenticationFailed() {
                    Log.d(TAG, "failed++ -> " + (mFailedAttempts+1) + " / limit=" + mPromptInfo.getMaxAttempts());
                    super.onAuthenticationFailed();
                    mFailedAttempts++;
                    resetWatchdog(); // arm after first real signal
                    int limit = mPromptInfo.getMaxAttempts();
                    if (limit > 0 && mFailedAttempts >= limit) {
                        Log.d(TAG, "limit reached -> schedule handoff to Keyguard");
                        if (mPromptInfo.isDeviceCredentialAllowed()) {
                            scheduleHandoffToKeyguard();
                        } else {
                            finishWithError(PluginError.BIOMETRIC_LOCKED_OUT);
                        }
                    }
                }

                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    super.onAuthenticationSucceeded(result);
                    stopWatchdog();
                    try {
                        finishWithSuccess(result.getCryptoObject());
                    } catch (CryptoException e) {
                        finishWithError(e);
                    }
                }

                @Override
                public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                    Log.d(TAG, "onError code=" + errorCode + " msg=" + errString
                            + " launchingKeyguard=" + mLaunchingDeviceCredential
                            + " suppress=" + mSuppressCancelError);
                    super.onAuthenticationError(errorCode, errString);
                    stopWatchdog();

                    switch (errorCode) {
                        case BiometricPrompt.ERROR_USER_CANCELED:
                        case BiometricPrompt.ERROR_CANCELED:
                            if (mSuppressCancelError || mLaunchingDeviceCredential) return;
                            finishWithError(PluginError.BIOMETRIC_DISMISSED);
                            return;

                        case BiometricPrompt.ERROR_TIMEOUT:
                            // Treat as soft failure to progress toward fallback (helps Face).
                            Log.d(TAG, "timeout -> count as failed");
                            mFailedAttempts++;
                            int limit = mPromptInfo.getMaxAttempts();
                            if (limit > 0 && mFailedAttempts >= limit) {
                                if (mPromptInfo.isDeviceCredentialAllowed()) {
                                    scheduleHandoffToKeyguard();
                                } else {
                                    finishWithError(PluginError.BIOMETRIC_LOCKED_OUT);
                                }
                                return;
                            }
                            resetWatchdog(); // re-arm while still in attempt window
                            return;

                        case BiometricPrompt.ERROR_LOCKOUT:
                        case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                            if (mPromptInfo.isDeviceCredentialAllowed()) {
                                scheduleHandoffToKeyguard();
                                return;
                            }
                            if (errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                                finishWithError(PluginError.BIOMETRIC_LOCKED_OUT.getValue(), errString.toString());
                            } else {
                                finishWithError(PluginError.BIOMETRIC_LOCKED_OUT_PERMANENT.getValue(), errString.toString());
                            }
                            return;

                        case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                            if (mPromptInfo.isDeviceCredentialAllowed()) {
                                scheduleHandoffToKeyguard();
                                return;
                            }
                            finishWithError(PluginError.BIOMETRIC_DISMISSED);
                            return;

                        default:
                            finishWithError(errorCode, errString.toString());
                    }
                }
            };

    /** Dismiss BiometricPrompt and reliably launch Keyguard (PIN/Pattern/Password), avoiding double-launch. */
    private void scheduleHandoffToKeyguard() {
        // Only JUST_AUTHENTICATE with backup may use credential fallback
        if (!canUseCredentialFallback()) {
            finishWithError(PluginError.BIOMETRIC_LOCKED_OUT);
            return;
        }
        if (mHandoffScheduled) return;
        stopWatchdog();
        mHandoffScheduled = true;
        mSuppressCancelError = true;
        try { mBiometricPrompt.cancelAuthentication(); } catch (Exception ignored) {}

        mUi.postDelayed(() -> {
            Log.d(TAG, "handoff->Keyguard t1");
            if (!mLaunchingDeviceCredential) launchDeviceCredential();
        }, 400);
        mUi.postDelayed(() -> {
            Log.d(TAG, "handoff->Keyguard t2");
            if (!mLaunchingDeviceCredential) launchDeviceCredential();
        }, 1200);
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
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
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
