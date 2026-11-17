#!/usr/bin/env python3
"""
Automated release helper script for Tally Delta Updates.
Generates BSDiff patches against previous release APKs and updates the JSON manifest.
"""

import os
import sys
import json
import hashlib
import subprocess
from pathlib import Path

# Base configuration
BASE_DIR = Path("/var/www/tally-updates")
APKS_DIR = BASE_DIR / "apks"
PATCHES_DIR = BASE_DIR / "patches"
MANIFEST_PATH = BASE_DIR / "update-manifest.json"

SERVER_BASE_URL = "http://192.168.1.117:4321/updates"


def sha256_file(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(8192):
            h.update(chunk)
    return h.hexdigest()


def generate_patch(old_apk: Path, new_apk: Path, patch_out: Path):
    print(f"Generating BSDiff patch: {old_apk.name} -> {new_apk.name}")
    cmd = ["bsdiff", str(old_apk), str(new_apk), str(patch_out)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"bsdiff failed: {res.stderr}")
    print(f"Created patch {patch_out.name} ({patch_out.stat().st_size / 1024:.1f} KB)")


def publish_release(new_apk_path: Path, new_version_code: int, new_version_name: str, notes: str):
    APKS_DIR.mkdir(parents=True, exist_ok=True)
    PATCHES_DIR.mkdir(parents=True, exist_ok=True)

    # Move/Copy new APK to server apks directory
    target_apk_name = f"tally-v{new_version_name}-vc{new_version_code}.apk"
    target_apk_path = APKS_DIR / target_apk_name
    if new_apk_path != target_apk_path:
        with open(new_apk_path, "rb") as src, open(target_apk_path, "wb") as dst:
            dst.write(src.read())

    full_sha256 = sha256_file(target_apk_path)
    full_size = target_apk_path.stat().st_size

    # Look for existing older APKs to create patches from
    patches = []
    for old_apk in APKS_DIR.glob("*.apk"):
        if old_apk.name == target_apk_name:
            continue

        # Extract versionCode from filename (e.g. tally-v0.8.0-vc9.apk -> 9)
        if "-vc" in old_apk.name:
            try:
                from_vc = int(old_apk.name.split("-vc")[1].replace(".apk", ""))
            except ValueError:
                continue

            patch_name = f"patch-v{from_vc}-to-v{new_version_code}.patch"
            patch_path = PATCHES_DIR / patch_name

            if not patch_path.exists():
                generate_patch(old_apk, target_apk_path, patch_path)

            patch_sha256 = sha256_file(patch_path)
            patch_size = patch_path.stat().st_size

            patches.append({
                "fromVersionCode": from_vc,
                "patchUrl": f"{SERVER_BASE_URL}/patches/{patch_name}",
                "patchSha256": patch_sha256,
                "patchSizeBytes": patch_size
            })

    manifest = {
        "latestVersionCode": new_version_code,
        "latestVersionName": new_version_name,
        "minSupportedVersionCode": 1,
        "releaseNotes": notes,
        "fullApkUrl": f"{SERVER_BASE_URL}/apks/{target_apk_name}",
        "fullApkSha256": full_sha256,
        "fullApkSizeBytes": full_size,
        "patches": patches
    }

    with open(MANIFEST_PATH, "w") as f:
        json.dump(manifest, f, indent=2)

    print(f"Update manifest published successfully to {MANIFEST_PATH}")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        print("Usage: python3 publish_update.py <path_to_new_apk> <version_code> <version_name> [release_notes]")
        sys.exit(1)

    apk_file = Path(sys.argv[1])
    code = int(sys.argv[2])
    name = sys.argv[3]
    rel_notes = sys.argv[4] if len(sys.argv) > 4 else "General improvements and bug fixes."

    publish_release(apk_file, code, name, rel_notes)
