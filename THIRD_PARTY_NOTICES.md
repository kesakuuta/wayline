# Third-party core notices

Wayline downloads the upstream core release archives during the Windows CI build. The binaries are not committed to this repository; the workflow pins their versions and verifies the archive SHA-256 digests before packaging.

## Desktop Windows x64

- **Xray-core v26.10.29** — [upstream project](https://github.com/XTLS/Xray-core), [release](https://github.com/XTLS/Xray-core/releases/tag/v26.10.29), Mozilla Public License 2.0.
- **sing-box v1.14.4** — [upstream project](https://github.com/SagerNet/sing-box), [release](https://github.com/SagerNet/sing-box/releases/tag/v1.14.4), GNU General Public License v3 or later.

The Windows installer includes the upstream Xray MPL-2.0 license text and the sing-box license notice plus the GPL-3.0 license text in its bundled resources.

## Android

Android currently integrates **sing-box libbox v1.14.4** through the [singbox-android/libbox](https://github.com/singbox-android/libbox) JitPack package and runs it through Wayline's native Android VpnService integration.

The Xray Android release publishes command-line binaries, but Wayline does not bundle or expose Xray as an Android engine yet: the Android VpnService/TUN adapter is currently implemented for libbox only. A standalone Xray executable would not by itself provide a working Android VPN connection.

## Reproducibility

Pinned versions and Windows x64 archive SHA-256 checksums are maintained in [windows-build.yml](.github/workflows/windows-build.yml). Core sources and licenses remain available from the linked upstream repositories.
