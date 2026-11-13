#!/usr/bin/env bash
# Builds the release APK and puts a copy at dist/Tally.apk (always the same path, so the phone can pull
# only the DIFFERENCE with rsync). Run on the build server:   bash scripts/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew --no-daemon assembleRelease

APK="$(ls -t app/build/outputs/apk/release/*.apk | head -n 1)"
mkdir -p dist
cp -f "$APK" dist/Tally.apk
( cd dist && sha256sum Tally.apk | tee Tally.apk.sha256 && ls -lh Tally.apk )
echo
echo "On the phone (Termux):"
echo "  rsync -P --partial --stats ils@ssh.nsikak.name.ng:~/dev/Tally/dist/Tally.apk ~/storage/downloads/Tally.apk"
echo "  sha256sum ~/storage/downloads/Tally.apk     # must equal the line above"
