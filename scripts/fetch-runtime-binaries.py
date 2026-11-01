#!/usr/bin/env python3
"""Fetch official sing-box and Xray release binaries for Wayline desktop bundles."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "src-tauri" / "binaries"
HEADERS = {"User-Agent": "Wayline-build", "Accept": "application/vnd.github+json"}


def request_json(url: str) -> dict:
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=60) as response:
        return json.load(response)


def download(asset: dict, target: Path) -> None:
    req = urllib.request.Request(asset["browser_download_url"], headers={"User-Agent": HEADERS["User-Agent"]})
    digest = hashlib.sha256()
    with urllib.request.urlopen(req, timeout=180) as response, target.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            output.write(chunk)
            digest.update(chunk)
    expected = asset.get("digest") or ""
    if expected.startswith("sha256:") and digest.hexdigest().lower() != expected.split(":", 1)[1].lower():
        target.unlink(missing_ok=True)
        raise RuntimeError(f"SHA256 mismatch for {asset['name']}")


def pick_asset(release: dict, predicate, description: str) -> dict:
    for asset in release.get("assets", []):
        if predicate(asset["name"].lower()):
            return asset
    names = ", ".join(a["name"] for a in release.get("assets", []))
    raise RuntimeError(f"Could not find {description} in {release.get('html_url')}: {names}")


def download_url(url: str, target: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": HEADERS["User-Agent"]})
    with urllib.request.urlopen(req, timeout=180) as response, target.open("wb") as output:
        shutil.copyfileobj(response, output)


def extract_members(archive: Path, wanted: set[str], temp: Path) -> dict[str, Path]:
    temp.mkdir(parents=True, exist_ok=True)
    found: dict[str, Path] = {}
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as bundle:
            for name in bundle.namelist():
                base = Path(name).name.lower()
                if base in wanted and base not in found:
                    target = temp / base
                    with bundle.open(name) as src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    found[base] = target
    else:
        with tarfile.open(archive, "r:*") as bundle:
            for member in bundle.getmembers():
                if not member.isfile():
                    continue
                base = Path(member.name).name.lower()
                if base in wanted and base not in found:
                    src = bundle.extractfile(member)
                    if src is None:
                        continue
                    target = temp / base
                    with src, target.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
                    found[base] = target
    return found


def main(platform: str) -> None:
    if platform not in {"windows", "linux"}:
        raise SystemExit("Usage: fetch-runtime-binaries.py windows|linux")
    DEST.mkdir(parents=True, exist_ok=True)
    windows = platform == "windows"
    sing_release = request_json("https://api.github.com/repos/SagerNet/sing-box/releases/tags/v1.14.0")
    xray_release = request_json("https://api.github.com/repos/XTLS/Xray-core/releases/latest")
    sing_predicate = (lambda n: n.startswith("sing-box") and "windows" in n and "amd64" in n and n.endswith(".zip")) if windows else (lambda n: n.startswith("sing-box") and "linux" in n and "amd64" in n and (n.endswith(".tar.gz") or n.endswith(".zip")) )
    xray_predicate = (lambda n: "xray" in n and "windows" in n and ("64" in n or "amd64" in n) and n.endswith(".zip")) if windows else (lambda n: "xray" in n and "linux" in n and ("64" in n or "amd64" in n) and (n.endswith(".zip") or n.endswith(".tar.gz")) )
    sing_asset = pick_asset(sing_release, sing_predicate, f"sing-box {platform} x64 archive")
    xray_asset = pick_asset(xray_release, xray_predicate, f"Xray {platform} x64 archive")

    with tempfile.TemporaryDirectory(prefix="wayline-cores-") as tmp_name:
        tmp = Path(tmp_name)
        sing_archive, xray_archive = tmp / sing_asset["name"], tmp / xray_asset["name"]
        download(sing_asset, sing_archive)
        download(xray_asset, xray_archive)
        exe_suffix = ".exe" if windows else ""
        wanted = {"sing-box" + exe_suffix, "xray" + exe_suffix}
        found = extract_members(sing_archive, wanted, tmp / "sing")
        found.update(extract_members(xray_archive, wanted, tmp / "xray"))

        for data_name in ("geoip.dat", "geosite.dat"):
            target = tmp / data_name
            download_url(f"https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/{data_name}", target)
            found[data_name] = target

        required = {"sing-box" + exe_suffix, "xray" + exe_suffix, "geoip.dat", "geosite.dat"}
        missing = required - found.keys()
        if missing:
            raise RuntimeError("Archives did not contain: " + ", ".join(sorted(missing)))

        for name in required:
            target = DEST / name
            shutil.copy2(found[name], target)
            if not windows and name in {"sing-box", "xray"}:
                target.chmod(0o755)

    (DEST / "versions.txt").write_text(
        f"sing-box: {sing_release.get('tag_name')}\nXray-core: {xray_release.get('tag_name')}\n",
        encoding="utf-8",
    )
    print(f"Prepared {platform} runtime binaries in {DEST}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
