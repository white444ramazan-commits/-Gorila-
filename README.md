# GORILLA KernelSU Companion

Android ARM64 KernelSU module and companion APK for device diagnostics and a self-contained test-scene visualizer.

## Safety boundary

This project does not inject into PUBG Mobile, read or modify its memory, automate aiming/touch input, hook the game, or bypass anti-cheat. AIM controls are local tuning/profile controls and a response-curve preview only. ESP is rendered only in the companion app's synthetic test scene.

## Project

- `module/`: KernelSU module scripts, metadata, config, and logs directory.
- `app/`: Android companion app (Kotlin, Jetpack Compose, JNI/NDK).
- `.github/workflows/`: CI build and validation.

Compatibility with a particular PUBG KR build is informational only and must be verified on-device. No PUBG 4.7 compatibility is claimed by this repository.
