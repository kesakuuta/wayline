# Wayline Android build handoff

## What is included
- Tauri 2 + React frontend.
- Android source overlay: `VpnService`, activity bridge, and a `libbox` adapter.
- Gradle preparation script that copies the overlay into the generated Tauri Android project.
- `libbox` dependency declaration is injected from JitPack by the preparation script.

## Important status
This archive is **build-preparation source**, not a verified ready-to-build APK.
The uploaded source has not been compiled against the exact `libbox 1.14.0` AAR or tested on a device.
The Kotlin adapter uses reflection against gomobile-generated APIs; exact method/interface names and TUN callbacks must be verified against the resolved AAR. A successful frontend `npm run build` does not prove Android VPN works.

## Required external toolchain
- Node.js + npm
- Rust toolchain
- Tauri CLI 2.12.4 (`cargo install tauri-cli --version 2.12.4 --locked`)
- Android SDK Platform 35 + Build Tools 35.0.0
- A full Android NDK (the Termux `ndk-sysroot` package is not a substitute)
- Java 17 or 21
- Network access to JitPack and Maven repositories for `com.github.singbox-android:libbox:1.14.0`

No separate libbox source archive or AAR needs to be supplied by the user if JitPack is reachable; Gradle should resolve the declared Maven dependency.

## Prepare the generated Android project
From the repository root:

```bash
npm install
npm run build
cargo install tauri-cli --version 2.12.4 --locked
bash scripts/prepare-android.sh
```

The script uses `cargo tauri android init` when the Rust-installed CLI is available, avoiding the npm native CLI binding on Termux. It then copies the Android overlay and injects the dependency.

After preparation, the Gradle project should be at:

```text
src-tauri/gen/android/
```

It should contain `gradlew`, `settings.gradle.kts`, and `app/`.

## Build
From the repository root:

```bash
cargo tauri android build --apk
```

If your Tauri CLI is exposed as `tauri` instead of `cargo tauri`, use:

```bash
tauri android build --apk
```

## What is still required before calling this production-ready
1. Compile the Android Gradle project against the exact resolved `libbox` artifact.
2. Fix any Kotlin/API mismatches reported by the compiler.
3. Verify the `PlatformInterface` callbacks and file-descriptor ownership against the libbox version.
4. Test VPN consent, foreground service behavior, IPv4/IPv6 routes, DNS, stop/revoke, and process death on a physical Android device.
5. Test with a valid user-supplied VLESS or other supported sing-box profile.
6. Review credential storage, kill switch, reconnect behavior, and traffic accounting.

Do not describe the app as connected merely because the Android service starts; only report connected after libbox has started and the TUN is established.
