# Wayline platform status

## Android

Android native VPN integration has been implemented in source: VpnService permission flow, foreground service, libbox CommandServer adapter, TUN descriptor handoff, frontend connect/disconnect bridge, and live service-state polling. Android is pinned to API 24+ and ABIs arm64-v8a / armeabi-v7a / x86_64.

**Not verified:** no Android SDK/NDK build or physical-device test was possible in the current environment. The exact libbox 1.14.0 gomobile API and runtime routing must be checked on-device. Do not treat this as a tested release APK.

## Desktop

Desktop continues to use the existing Tauri/Rust core integration. This Android update does not change Windows or Linux engine packaging.
