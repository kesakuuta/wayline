# Wayline Android VPN layer

Android uses the pinned `com.github.singbox-android:libbox:1.14.0` AAR and Android `VpnService` (min API 24). The native layer now contains:

- `MainActivity` with an Android VPN consent launcher and a restricted `WaylineAndroid` JavaScript bridge exposed only to the bundled Tauri WebView;
- `WaylineVpnService`, which runs as a foreground VPN service, starts/stops the sing-box engine, and publishes the real service state through app-private preferences;
- `LibboxBridge`, which creates the libbox `CommandServer`, implements its platform callbacks, protects sing-box sockets from VPN routing, establishes the Android TUN from the `TunOptions` passed by libbox, and returns the detached TUN file descriptor;
- a frontend Android path that builds a sing-box TUN config from the selected imported outbound, invokes the native permission flow, and polls service state.

## Prepare the generated Tauri Android project

From the repository root:

```bash
npm install
npm run android:prepare
```

The preparation script runs `tauri android init` when needed, merges the VPN service into the generated manifest, copies the Kotlin sources, pins `minSdk` to 24, limits ABIs to `arm64-v8a`, `armeabi-v7a`, and `x86_64`, and adds the libbox dependency plus JitPack repository.

Then build with the Android SDK/NDK installed:

```bash
cd src-tauri/gen/android
./gradlew assembleDebug
```

## Important

The implementation is source-complete as an integration pass, but it has **not** been compiled or run against an Android SDK/device in this environment. In particular, the generated gomobile Java API surface in the exact `libbox:1.14.0` artifact, Android lifecycle behavior, and routing on real devices still need an actual build/device test. The adapter uses reflection around the gomobile interfaces to avoid hard-coding generated method casing; if the pinned AAR changes its exported class/interface names, the startup error will identify the missing API instead of falsely showing Connected.

No APK is included. A real imported server profile is required before connecting. Android always uses sing-box/TUN; the desktop Xray/sing-box selector is not used on Android.

**Licensing:** the selected `singbox-android/libbox` artifact is GPL-3.0. Review the implications for distributing Wayline before publishing a closed-source build.
