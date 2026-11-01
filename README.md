# Wayline

<p align="center">
  <strong>A focused, cross-platform client for modern proxy protocols.</strong><br/>
  <sub>Built with Tauri 2, React, Rust, Xray-core and sing-box.</sub>
</p>

<p align="center">
  <a href="https://github.com/kesakuuta/wayline/actions/workflows/windows-build.yml"><img src="https://github.com/kesakuuta/wayline/actions/workflows/windows-build.yml/badge.svg" alt="Windows build"/></a>
  <a href="https://github.com/kesakuuta/wayline/actions/workflows/linux-build.yml"><img src="https://github.com/kesakuuta/wayline/actions/workflows/linux-build.yml/badge.svg" alt="Linux build"/></a>
  <a href="https://github.com/kesakuuta/wayline/actions/workflows/android-build.yml"><img src="https://github.com/kesakuuta/wayline/actions/workflows/android-build.yml/badge.svg" alt="Android build"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue.svg" alt="GPL-3.0"/></a>
</p>

Wayline brings server subscriptions, connection controls and routing options into one compact desktop interface. It uses the upstream Xray and sing-box engines rather than implementing proxy protocols itself.

> **Development status:** Wayline is experimental. A successful CI build verifies compilation and packaging, not a live VPN connection on every OS, device or network. Read [Platform status](PLATFORM_STATUS.md) before using it as your primary VPN.

## Contents

- [Highlights](#highlights)
- [Platforms](#platforms)
- [Engines and modes](#engines-and-modes)
- [Download](#download)
- [Importing a server](#importing-a-server)
- [Build from source](#build-from-source)
- [Troubleshooting](#troubleshooting)
- [Privacy and security](#privacy-and-security)
- [Credits](#credits)

## Highlights

- Import supported share links and sing-box outbound JSON.
- Refresh HTTPS subscriptions.
- Use Xray-core or sing-box on desktop; Android uses sing-box/libbox.
- Choose local proxy mode or a system TUN interface where supported.
- Filter the server list by protocol, region and favourites.
- Run a TCP reachability check against imported server endpoints.
- Customize themes, component styles, background, panel transparency and blur.
- Inspect and copy core logs.

Supported imports currently include VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS, SOCKS and HTTP outbounds, subject to the selected engine and transport.

## Platforms

| Platform | Build target | Notes |
| --- | --- | --- |
| Windows | x64 | Installer and portable ZIP are planned in CI. TUN may require elevation and a working Wintun driver. |
| Linux | x86_64 | DEB and AppImage. TUN requires `CAP_NET_ADMIN` and distribution-specific networking support. |
| Android | arm64 | Experimental Tauri Android shell with native `VpnService` and sing-box libbox integration. Device testing is still required. |

See [PLATFORM_STATUS.md](PLATFORM_STATUS.md) for verified build status and known limitations.

## Engines and modes

### Xray-core

- Desktop only.
- Local SOCKS proxy mode.
- Uses Xray's `geoip.dat` and `geosite.dat` assets when packaged.
- Xray TUN is not enabled in the current adapter.

### sing-box

- Desktop: local proxy and TUN modes.
- Android: sing-box runs through the libbox Android binding and Android `VpnService`.
- TUN requires OS-level permissions and a compatible network stack.

Desktop Proxy mode configures the Windows user proxy or GNOME desktop proxy automatically and restores the previous values on disconnect. Android Proxy mode uses VpnService and Android's per-VPN HTTP proxy on API 29+, while the TUN interface remains active underneath. Xray publishes its SOCKS endpoint; sing-box publishes its mixed HTTP/SOCKS endpoint. Android Proxy mode uses VpnService and Android's per-VPN HTTP proxy on API 29+, while the TUN interface remains active underneath. Use TUN mode when you need protocol-independent system-wide routing.

## Download

Open [GitHub Actions](https://github.com/kesakuuta/wayline/actions) for CI artifacts, or [Releases](https://github.com/kesakuuta/wayline/releases) for published versions.

- **Windows installer:** standard desktop installation.
- **Windows portable ZIP:** extract the complete folder and launch `wayline-vpn.exe`.
- **Linux AppImage:** mark the file executable and run it.
- **Linux portable ZIP:** contains the AppImage and quick-start notes.
- **Android APK:** experimental debug build for testing; it is not a Play Store release.

Do not download core executables from unofficial mirrors. Release workflows obtain Xray-core and sing-box from their upstream projects and verify the published archive checksums.

## Importing a server

1. Start Wayline.
2. Press **+** beside the profile list.
3. Paste a supported share link, sing-box outbound JSON, or an HTTPS subscription URL.
4. Choose a server from the list.
5. Select the engine and network mode while disconnected.
6. Press the connection button and grant any OS VPN permission prompts.

Subscription links and imported credentials are sensitive. Do not share diagnostic logs without checking them for addresses, domains and account identifiers.

## Build from source

### Requirements

- Node.js 22 or later and npm
- Rust stable toolchain
- Tauri 2 system prerequisites for your OS
- For Android: Android Studio/SDK, NDK, Java 17 and the Rust Android target

### Desktop development

```bash
npm install
npm run dev
```

The browser preview is UI-only. It cannot start a native proxy process or create a TUN interface.

Run the native desktop app:

```bash
npm run tauri dev
```

Build a desktop package:

```bash
npm run build
npm test
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

For a local development run, place the matching Xray and sing-box executables in `src-tauri/binaries/` or make them available in `PATH`. Release workflows bundle the required engine binaries and Xray data files.

### Android preparation

```bash
npm install
cargo install tauri-cli --version 2.12.4 --locked
bash scripts/prepare-android.sh
cargo tauri android dev
# or
cargo tauri android build --apk --target aarch64
```

The preparation script generates the Tauri Android project and overlays the Wayline Kotlin VPN service and libbox adapter. Do not run `tauri android init` again without re-running the preparation script.

## Troubleshooting

### TUN reports access denied

TUN is a privileged network operation. The Windows executable requests administrator elevation through UAC at launch because sing-box must create and configure the TUN adapter. Approve the UAC prompt and make sure the Wintun driver can be installed/created. On Linux, grant the application the required `CAP_NET_ADMIN` capability or run it from an appropriately privileged session. Do not grant capabilities to binaries you did not obtain from a trusted build.

### Proxy mode does not cover every application

That is expected for a loopback SOCKS/HTTP proxy. Applications must use the proxy explicitly unless the operating system's proxy settings are configured. For system-wide traffic interception, use sing-box TUN where it is supported.

### Android disconnects or stays on “connecting”

Check the Android VPN permission, battery/background restrictions and the service log. Android CI compilation does not replace a physical-device test. If the app becomes unresponsive, capture logcat output for the Wayline and libbox tags.

### A core binary is missing

Use a complete installer/portable package. Development builds need the matching core executable in `src-tauri/binaries/` or in `PATH`. Wayline does not ask users to browse for a binary path.

## Privacy and security

- Imported profiles contain credentials and are stored locally by the current application build.
- Treat exported backups and logs as sensitive.
- Subscription fetching is restricted to HTTPS in the Android bridge.
- Do not use Wayline on a shared or untrusted device with private credentials.
- Review the generated core configuration and the platform permissions before enabling TUN.

## Credits

Wayline is built on the work of these projects:

- [Xray-core](https://github.com/XTLS/Xray-core) — proxy engine and GeoIP/GeoSite data format.
- [sing-box](https://github.com/SagerNet/sing-box) — proxy engine and TUN routing.
- [NekoBox for Android](https://github.com/MatsuriDayo/NekoBoxForAndroid) — reference for Android `VpnService` lifecycle, TUN establishment and per-app routing.
- [Throne](https://github.com/throneproj/Throne) — reference for desktop TUN and system-proxy behavior.
- [MaterialVless](https://github.com/) — reference for subscription, node ping and routing configuration UX.
- [Tauri](https://tauri.app/) — cross-platform desktop and Android application framework.
- [React](https://react.dev/) — interface layer.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and the upstream projects' licences for attribution and licence details.

---

<p align="center"><sub>Wayline is an independent project and is not affiliated with XTLS, SagerNet, NekoBox or Throne.</sub></p>
