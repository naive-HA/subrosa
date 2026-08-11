# Privacy Policy for sub rosa

**Last updated: August 2, 2026**

## Summary

sub rosa does not collect, store, or transmit your personal data. Everything it touches stays on your device and your hardware security key.

## What sub rosa Does

- **Talks to your hardware security key.** sub rosa communicates with connected YubiKey, Nitrokey 3, Nitrokey Pro or Librem Key devices over NFC or USB using standard smart-card (APDU) commands, 
to read device info and manage the Static Password, OpenPGP keys and PINs.
- **Handles the Static Password transiently.** When programming the Static Password, you enter it in the app. It's used only to talk to the security key and is cleared from memory
immediately after — never stored, never transmitted.
- **Handles PINs transiently.** When an operation needs your security key's User or Admin PIN, you enter it in the app. It's used only to talk to the security key and is cleared from memory 
immediately after — never stored, never transmitted.
- **Imports OpenPGP key backups.** You can import an existing OpenPGP key to write it to your security key. During import, your private key material is briefly held in the app's memory 
to complete the write — it's never written to any file sub rosa creates itself, and never leaves your device.

## What sub rosa Doesn't Do

- No account or sign-in
- No analytics, telemetry, or crash reporting
- No ads or ad-tracking SDKs
- No network access of any kind
- No data shared with third parties

## Permissions

- NFC: Communicate with NFC-enabled hardware security keys
- USB: Communicate with hardware security keys over USB

## Data Retention

sub rosa doesn't run any servers and has nothing to retain. All app data lives on your device; uninstalling removes it.

## Third-Party Services

If you install sub rosa from Google Play, Google collects some data as part of operating the Play Store (e.g. install and OS-level crash data) under Google's own privacy policy — 
outside sub rosa's control.

sub rosa uses no third-party services.

## Your Rights (GDPR / CCPA)

Because sub rosa doesn't collect personal data, there's no data of yours for us to give you access to, correct, delete, or export.

## Children's Privacy

sub rosa is not directed at children and does not knowingly collect data from anyone, of any age, because it does not collect data.

## Changes to This Policy

If sub rosa's data practices change — for example, a future feature that needs network access — this page will be updated and the date above revised.