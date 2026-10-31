#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! command -v npx >/dev/null 2>&1; then
  echo "Node.js/npm is required (npx not found)." >&2; exit 1
fi
if [ ! -d src-tauri/gen/android ]; then
  # Prefer the Rust-installed CLI: npm's platform-specific native CLI binding
  # is not available on every Android/Termux host.
  if command -v cargo >/dev/null 2>&1 && cargo tauri --help >/dev/null 2>&1; then
    cargo tauri android init
  elif npx --no-install tauri --help >/dev/null 2>&1; then
    npx --no-install tauri android init
  else
    echo "Tauri CLI is unavailable. Install the matching Rust CLI first:" >&2
    echo "  cargo install tauri-cli --version 2.12.4 --locked" >&2
    exit 1
  fi
fi

GEN="src-tauri/gen/android/app/src/main"
PKG="$GEN/java/app/wayline/vpn"
mkdir -p "$PKG" "$GEN/res/values"
python3 - "$GEN/AndroidManifest.xml" src-tauri/android/app/src/main/AndroidManifest.xml <<'PYMANIFEST'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
android = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", "http://schemas.android.com/apk/res/android")
dst, src = map(Path, sys.argv[1:])
base = ET.parse(dst).getroot()
overlay = ET.parse(src).getroot()
for perm in overlay.findall("uses-permission"):
    name = perm.get(android + "name")
    if not any(x.get(android + "name") == name for x in base.findall("uses-permission")):
        base.append(perm)
app = base.find("application")
source_app = overlay.find("application")
if app is None or source_app is None:
    raise SystemExit("Generated/overlay manifest has no application element")
# Keep Tauri's generated Activity, metadata, providers and theme. Add only Wayline VPN service.
for child in source_app.findall("service"):
    name = child.get(android + "name")
    if not any(x.tag == "service" and x.get(android + "name") == name for x in app.findall("service")):
        app.append(child)
dst.write_text(ET.tostring(base, encoding="unicode", xml_declaration=True))
PYMANIFEST
cp src-tauri/android/app/src/main/java/app/wayline/vpn/*.kt "$PKG/"
cp src-tauri/android/app/src/main/res/values/strings.xml "$GEN/res/values/strings.xml"

# Preserve Tauri's generated Gradle plugin/configuration; inject libbox and ABI constraints.
APP_GRADLE="src-tauri/gen/android/app/build.gradle.kts"
python3 - "$APP_GRADLE" <<'PYGRADLE'
from pathlib import Path
import re, sys
p = Path(sys.argv[1]); s = p.read_text()
if re.search(r'minSdk\s*=\s*\d+', s):
    s = re.sub(r'minSdk\s*=\s*\d+', 'minSdk = 24', s, count=1)
elif re.search(r'minSdkVersion\(\d+\)', s):
    s = re.sub(r'minSdkVersion\(\d+\)', 'minSdkVersion(24)', s, count=1)
if 'abiFilters' not in s:
    m = re.search(r'defaultConfig\s*\{', s)
    if m:
        pos = m.end()
        s = s[:pos] + '\n        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }\n' + s[pos:]
if 'com.github.singbox-android:libbox:1.14.0' not in s:
    s += '\ndependencies {\n    implementation("androidx.core:core-ktx:1.15.0")\n    implementation("androidx.appcompat:appcompat:1.7.0")\n    implementation("com.github.singbox-android:libbox:1.14.0")\n}\n'
p.write_text(s)
PYGRADLE

SETTINGS=""
for candidate in src-tauri/gen/android/settings.gradle.kts src-tauri/gen/android/settings.gradle; do
  if [ -f "$candidate" ]; then SETTINGS="$candidate"; break; fi
done
if [ -z "$SETTINGS" ]; then
  echo "Could not find generated Android settings.gradle(.kts)." >&2
  exit 1
fi
if ! grep -q 'jitpack.io' "$SETTINGS"; then
  python3 - "$SETTINGS" <<'PYSETTINGS'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if p.suffix == ".kts":
    repo = 'repositories { maven { url = uri("https://jitpack.io") } }'
else:
    repo = 'repositories { maven { url "https://jitpack.io" } }'
needle = "dependencyResolutionManagement {"
if needle in s:
    pos = s.find(needle)
    brace = s.find("{", pos) + 1
    s = s[:brace] + "\n    " + repo + "\n" + s[brace:]
else:
    s += "\ndependencyResolutionManagement { " + repo + " }\n"
p.write_text(s)
PYSETTINGS
fi

echo "Android source overlay synced into src-tauri/gen/android."
echo "Next: build with your Android SDK/NDK."
