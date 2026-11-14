# Compatibility and verification

- Android app minimum: API 26 (Android 8.0).
- Intended ABI: ARM64 / arm64-v8a.
- Root manager: KernelSU.
- Package checked: `com.pubg.krmobile`.

## PUBG KR 4.7

The app displays the installed package versionName. A version string containing `4.7` is only a text match, not proof of compatibility. Package names and version reporting can vary by distribution channel. The project does not inspect the game process or claim runtime compatibility with PUBG KR 4.7.

CPU is sampled from readable `/proc/stat`. GPU temperature appears only if a readable thermal zone type contains `gpu`. Thermal status uses PowerManager on API 29+. Missing sensors are reported as unavailable. The refresh request is scoped to the companion Activity window; no global refresh policy, governor, thermal limit, or system property is changed.

## Device checks

1. Confirm ABI is `arm64-v8a`.
2. Install module with KernelSU Manager and reboot.
3. Open APK and approve root-shell request.
4. Verify module, Android API, sensors, and display modes.
5. Confirm ESP renders only synthetic sample entities.
6. Confirm AIM preferences persist after app restart.

No physical-device test has been run by this repository workflow.
