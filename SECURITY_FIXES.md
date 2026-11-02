# Security Fixes (v1.0.6 → hardened)

This fork addresses 5 security issues identified in the upstream v1.0.6 release.
All fixes are in the `main` branch of this fork.

## Fix 1 [HIGH]: Remove public debug keystore

**Problem:** Release APK was signed with `androidApp/debug.keystore` committed to the
repo (password "android" in build.gradle.kts). Anyone could forge updates.

**Fix:**
- Deleted `androidApp/debug.keystore` from git history (file removed).
- `androidApp/build.gradle.kts`: Release signing now requires a private keystore
  via `androidApp/keystore.properties` (gitignored) or env vars:
  `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Added `.gitignore` rules for `*.keystore`, `*.jks`, `keystore.properties`.
- Added `androidApp/keystore.properties.example` as a template.

**Action for builder:** Generate your own keystore:
```bash
keytool -genkeypair -alias release -keyalg RSA -keysize 2048 \
  -validity 10000 -keystore release.keystore
```
Then create `androidApp/keystore.properties` with your values. NEVER commit it.

## Fix 2 [HIGH]: Encrypt credentials + disable backup

**Problem:** Xiaomi session tokens (serviceToken, ssecurity, passToken) stored in
plaintext DataStore. Manifest allowed backup (`allowBackup="true"`) with no exclusions.

**Fix:**
- New `SecureTokenStorage` expect/actual:
  - Android: `EncryptedSharedPreferences` (AES-256-GCM, master key in Android Keystore)
  - iOS: Keychain (`kSecClassGenericPassword`)
- `CredentialsStore` now writes the 3 sensitive tokens to secure storage;
  non-sensitive metadata (user ID, region, device ID) stays in DataStore.
- Migration: old plaintext tokens are removed from DataStore on first save.
- `AndroidManifest.xml`: `allowBackup="false"` + `fullBackupOnly="false"`.
- Added `androidx.security:security-crypto:1.1.0` dependency.

## Fix 3 [MEDIUM]: Lock region to cn

**Problem:** `MiRegionDiscovery.discover()` probed cn/sg/us/de/ru/i2 concurrently
with the same login session, leaking the session to all regions.

**Fix:**
- `discover()` now defaults to `allowMultiRegionProbe = false`, returning "cn"
  immediately without any network probing.
- Multi-region probing is opt-in only (pass `true` explicitly).

## Fix 4 [MEDIUM]: One-way weight sync by default

**Problem:** `syncWeightBidirectional()` uploaded Health Connect weight back to
Xiaomi cloud; README implied one-way sync.

**Fix:**
- Function signature: `syncWeightBidirectional(allowUploadToMi: Boolean = false)`.
- Default behavior is now ONE-WAY (Mi → Health Connect only).
- Bidirectional requires explicit opt-in.

## Fix 5 [LOW]: Redirect host whitelist

**Problem:** Login flow followed `Location` header redirects without validating the host.

**Fix:**
- `MiAuth.kt`: Redirect host must end with `.xiaomi.com` or `.mi.com`
  (or be exactly those domains); otherwise throws `MiAuthException`.

## Building

You need Android SDK + JDK 17. See README.md for the original build instructions.
Remember to create `androidApp/keystore.properties` before building release.

## Disclaimer

These fixes address the identified issues but have not undergone a full security audit.
The iOS Keychain implementation is functional but has not been tested on-device.
Review the changes before using with a primary Xiaomi account.
