export interface FingerprintOptions {
  clientId: string;
  clientSecret?: string;
  disableBackup?: boolean;
  localizedFallbackTitle?: string;
  localizedReason?: string;
  title?: string;
  subtitle?: string;
  description?: string;
  fallbackButtonTitle?: string;
  cancelButtonTitle?: string;
  maxAttempts?: number; // Android: default 5, shared across modalities
}

export interface FingerprintPlugin {
  isAvailable(success: (type: string) => void, error?: (err: any) => void, opts?: any): void;
  show(options: FingerprintOptions, success: () => void, error?: (err: any) => void): void;
  registerBiometricSecret(options: FingerprintOptions, success: () => void, error?: (err: any) => void): void;
  loadBiometricSecret(options: FingerprintOptions, success: (secret: string) => void, error?: (err: any) => void): void;

  BIOMETRIC_UNKNOWN_ERROR: number;
  BIOMETRIC_UNAVAILABLE: number;
  BIOMETRIC_AUTHENTICATION_FAILED: number;
  BIOMETRIC_SDK_NOT_SUPPORTED: number;
  BIOMETRIC_HARDWARE_NOT_SUPPORTED: number;
  BIOMETRIC_PERMISSION_NOT_GRANTED: number;
  BIOMETRIC_NOT_ENROLLED: number;
  BIOMETRIC_INTERNAL_PLUGIN_ERROR: number;
  BIOMETRIC_DISMISSED: number;
  BIOMETRIC_PIN_OR_PATTERN_DISMISSED: number;
  BIOMETRIC_SCREEN_GUARD_UNSECURED: number;
  BIOMETRIC_LOCKED_OUT: number;
  BIOMETRIC_LOCKED_OUT_PERMANENT: number;
  BIOMETRIC_NO_SECRET_FOUND: number;
}

declare const Fingerprint: FingerprintPlugin;
export default Fingerprint;
