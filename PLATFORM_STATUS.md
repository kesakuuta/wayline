# Platform status

Wayline is under active development. CI compilation is not a substitute for real-device VPN tests.

| Platform | Package | Current notes |
|---|---|---|
| Windows x64 | NSIS installer + portable ZIP | The app requests UAC elevation so sing-box can create/configure a TUN adapter. Proxy mode sets the Windows user proxy and restores the previous values on disconnect. |
| Linux x86_64 | DEB + AppImage + portable ZIP | Proxy mode integrates with GNOME desktop proxy settings. TUN requires `CAP_NET_ADMIN` on the sing-box process; other desktop environments may need manual proxy setup. |
| Android arm64 | APK | Uses Android `VpnService` and sing-box libbox. Both Proxy and TUN modes create a VPN interface; Proxy mode also publishes the local mixed inbound as Android's per-VPN HTTP proxy on API 29+. Device testing remains necessary. |

## Known limitations

- The Android interface is still rendered by the Tauri WebView. A fully native Jetpack Compose interface is a separate migration and is not included in this change set.
- Windows Proxy mode uses the Windows user proxy setting. Applications that ignore system proxy settings or use non-proxy UDP traffic may require TUN mode.
- Linux automatic Proxy mode currently targets GNOME's `gsettings` proxy schema. TUN requires network-administration capability.
- macOS is not a supported target in the current CI workflows.
- Imported subscriptions and local backups contain sensitive credentials. Keep backup files private.
