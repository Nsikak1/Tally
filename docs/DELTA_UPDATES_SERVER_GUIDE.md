# Server-Side Guide: Hosting and Publishing Delta Updates for Tally

This guide explains how to generate, host, and publish binary delta patches and app update manifests for the Tally Android application.

---

## 1. Overview of the Update Pipeline

When a new version of the Tally APK is built, the server host:
1. Keeps previous release APKs (e.g. `tally-v0.8.0.apk` = `versionCode 9`).
2. Takes the new release APK (e.g. `tally-v0.9.0.apk` = `versionCode 10`).
3. Generates a binary delta patch file using `bsdiff`:
   $$\text{bspatch}(\text{Old APK (v9)}, \text{Patch (v9}\rightarrow\text{v10)}) = \text{New APK (v10)}$$
4. Computes SHA-256 checksums and file sizes.
5. Updates `update-manifest.json` served at `GET /api/v1/app/check-update`.

---

## 2. Update Manifest JSON Specification

Endpoint: `GET http://<server_address>/api/v1/app/check-update`

### JSON Schema & Example
```json
{
  "latestVersionCode": 10,
  "latestVersionName": "0.9.0",
  "minSupportedVersionCode": 1,
  "releaseNotes": "- Added support for self-updating and delta binary patches.\n- Faster barcode scanner startup.",
  "fullApkUrl": "http://192.168.1.117:4321/updates/apks/tally-v0.9.0.apk",
  "fullApkSha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "fullApkSizeBytes": 26214400,
  "patches": [
    {
      "fromVersionCode": 9,
      "patchUrl": "http://192.168.1.117:4321/updates/patches/patch-v9-to-v10.patch",
      "patchSha256": "a1b2c3d4e5f67890123456789abcdef0123456789abcdef0123456789abcdef0",
      "patchSizeBytes": 850000
    }
  ]
}
```

#### Field Description
- `latestVersionCode` (*integer*): The target `versionCode` (from `build.gradle.kts`).
- `latestVersionName` (*string*): Human-readable version string (e.g. `"0.9.0"`).
- `minSupportedVersionCode` (*integer*): Minimum version code allowed to run.
- `releaseNotes` (*string*): Markdown or plain text description of changes.
- `fullApkUrl` (*string*): Absolute URL to download the complete full `.apk` file.
- `fullApkSha256` (*string*): Hex SHA-256 checksum of the full new `.apk` file.
- `fullApkSizeBytes` (*integer*): Size of full `.apk` file in bytes.
- `patches` (*array*): List of available delta patch objects for upgrading from older version codes.
  - `fromVersionCode` (*integer*): The version code this patch upgrades **from**.
  - `patchUrl` (*string*): Absolute URL to download the binary `.patch` file.
  - `patchSha256` (*string*): SHA-256 checksum of the `.patch` file.
  - `patchSizeBytes` (*integer*): Size of the `.patch` file in bytes.

---

## 3. Tooling Setup: Generating BSDiff Patches

### Prerequisites
On Debian/Ubuntu Linux (or server OS):
```bash
sudo apt-get update
sudo apt-get install -y bsdiff
```
On macOS:
```bash
brew install bsdiff
```

### Manual Command Example
To create a delta patch upgrading from `v9` (`tally-v9.apk`) to `v10` (`tally-v10.apk`):
```bash
bsdiff tally-v9.apk tally-v10.apk patch-v9-to-v10.patch
```

---

## 4. Server Directory Layout

Organize your update storage directory on the server as follows:

```
/var/www/tally-updates/
├── update-manifest.json
├── apks/
│   ├── tally-v0.8.0-vc9.apk
│   └── tally-v0.9.0-vc10.apk
└── patches/
    └── patch-v9-to-v10.patch
```

---

## 5. Automated Python Publishing Script (`publish_update.py`)

Save this script as `publish_update.py` in your server workspace or release pipeline.

```python
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
```

---

## 6. Server Endpoint Examples

### Option A: Python FastAPI / Express / Node.js
If hosting via Python FastAPI or Node.js, serve the JSON file at `/api/v1/app/check-update` and static files at `/updates`:

#### FastAPI Example:
```python
from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
import json

app = FastAPI()

# Serve updates static directory
app.mount("/updates", StaticFiles(directory="/var/www/tally-updates"), name="updates")

@app.get("/api/v1/app/check-update")
def check_update():
    with open("/var/www/tally-updates/update-manifest.json", "r") as f:
        return json.load(f)
```

#### Node.js / Express Example:
```javascript
const express = require('express');
const path = require('path');
const fs = require('fs');

const app = express();

app.use('/updates', express.static('/var/www/tally-updates'));

app.get('/api/v1/app/check-update', (req, res) => {
  const manifest = fs.readFileSync('/var/www/tally-updates/update-manifest.json', 'utf8');
  res.setHeader('Content-Type', 'application/json');
  res.send(manifest);
});

app.listen(4321, () => console.log('Server running on port 4321'));
```

### Option B: Nginx Static Server Configuration
If serving static files directly using Nginx:

```nginx
server {
    listen 4321;
    server_name school.local 192.168.1.117;

    # Endpoint mapping
    location /api/v1/app/check-update {
        alias /var/www/tally-updates/update-manifest.json;
        default_type application/json;
        add_header Cache-Control "no-cache";
    }

    # Static downloads
    location /updates/ {
        alias /var/www/tally-updates/;
        autoindex off;
    }
}
```

---

## 7. Verification Checklist

1. **Test Update Check**: Run `curl -i http://192.168.1.117:4321/api/v1/app/check-update` and confirm `latestVersionCode`, SHA-256 hashes, and patch URLs return valid JSON.
2. **Test File Reachability**: Run `curl -I <patchUrl>` to ensure binary patch files download properly without CORS or 404 issues.
3. **App Verification**: Open **Settings** in Tally and tap **Check for Updates**. The app will report the available version, download the smaller patch, reconstruct the APK, verify signature integrity, and trigger `PackageInstaller`.
