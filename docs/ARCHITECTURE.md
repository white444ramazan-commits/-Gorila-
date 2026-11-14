# Architecture

- Android UI: Kotlin + Jetpack Compose.
- Local tuning: app-private SharedPreferences.
- JNI/NDK: C++17 response-curve preview only.
- KernelSU module: boot diagnostics, config defaults, and read-only status.
- ESP demo: Canvas renderer with two synthetic entities inside the companion Activity.
- Performance: best-effort CPU, thermal, GPU sensor, and display refresh telemetry.

There is no game-process handle, memory reader, input injector, hook, overlay permission, anti-cheat interaction, or remote service.
