# Wayline platform status

## Android

The Android source overlay includes the VPN permission flow, foreground service, libbox CommandServer adapter, TUN descriptor handoff and frontend connect/disconnect bridge. The Gradle overlay and preparation script target libbox 1.14.4.

**Not yet verified:** this repository has not been validated by a successful Android SDK/NDK build and physical-device connection test. The CI workflow regenerates the Tauri Android project, applies the overlay and builds a signed debug APK. Treat Android as experimental until that workflow passes and TUN routing is tested on a device.

## Windows

The Windows release workflow downloads official, checksum-verified Xray-core and sing-box archives, packages the engines and Xray geoip/geosite assets, then builds the NSIS installer. The app resolves the bundled engines automatically; users do not need to enter executable paths.

**Not yet verified:** TUN connectivity still requires a real Windows device test. Xray is supported in local-proxy mode; use sing-box for TUN.

## Linux

The Linux workflow downloads official, checksum-verified Xray-core and sing-box archives, packages the engines and Xray geoip/geosite assets, and builds DEB and AppImage artifacts.

Linux TUN routing requires network-administration privileges (CAP_NET_ADMIN). This is an operating-system permission requirement, not a missing executable path. A successful CI package build does not by itself prove a live tunnel works on every distribution.

## Shared desktop core resolution

The desktop backend resolves engine binaries from Tauri's bundled resource directory first, then from the unpacked application directory, and finally from PATH for development setups. There is no arbitrary executable-path parameter in the frontend-to-Rust connection command.
