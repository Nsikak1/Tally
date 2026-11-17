#!/usr/bin/env bash
set -e

# Export Android SDK & user directories for AGP
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$HOME/.android}"
mkdir -p "$ANDROID_USER_HOME"

# Ensure valid JDK 17 or 21 is used for Gradle
if [ -z "$JAVA_HOME" ] || [[ "$(java -version 2>&1)" == *"25"* ]]; then
    if [ -d "/usr/lib/jvm/java-1.17.0-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-1.17.0-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
    elif [ -d "/usr/lib/jvm/java-1.21.0-openjdk-amd64" ]; then
        export JAVA_HOME="/usr/lib/jvm/java-1.21.0-openjdk-amd64"
        export PATH="$JAVA_HOME/bin:$PATH"
    fi
fi

# Target directory where APKs, patches, and update-manifest.json are stored (default: ~/olib)
TARGET_DIR="${UPDATE_OUTPUT_DIR:-$HOME/olib}"
mkdir -p "$TARGET_DIR"

MANIFEST_FILE="$TARGET_DIR/update-manifest.json"
GRADLE_PROPS="gradle.properties"

# 1. Determine current versionCode and versionName
CURRENT_VC=0
CURRENT_VN=""

# Try reading from update-manifest.json in target directory
if [ -f "$MANIFEST_FILE" ]; then
    CURRENT_VC=$(grep -o '"latestVersionCode": *[0-9]*' "$MANIFEST_FILE" | head -n1 | awk '{print $2}')
    CURRENT_VN=$(grep -o '"latestVersionName": *"[^"]*"' "$MANIFEST_FILE" | head -n1 | cut -d'"' -f4)
fi

# Fallback to gradle.properties
if [ -z "$CURRENT_VC" ] || [ "$CURRENT_VC" -eq 0 ]; then
    if grep -q "^versionCode=" "$GRADLE_PROPS" 2>/dev/null; then
        CURRENT_VC=$(grep "^versionCode=" "$GRADLE_PROPS" | cut -d'=' -f2)
    fi
fi

if [ -z "$CURRENT_VN" ]; then
    if grep -q "^versionName=" "$GRADLE_PROPS" 2>/dev/null; then
        CURRENT_VN=$(grep "^versionName=" "$GRADLE_PROPS" | cut -d'=' -f2)
    fi
fi

# Default fallback if empty
CURRENT_VC=${CURRENT_VC:-9}
CURRENT_VN=${CURRENT_VN:-"0.8.0"}

# 2. Auto-increment versionCode
NEW_VC=$((CURRENT_VC + 1))

# 3. Determine new versionName and release notes
CUSTOM_NOTES="$1"
CUSTOM_VN="$2"

if [ -n "$CUSTOM_VN" ]; then
    NEW_VN="$CUSTOM_VN"
else
    # Increment patch version (e.g. 0.8.0 -> 0.8.1)
    BASE_VERSION=$(echo "$CURRENT_VN" | awk -F. '{print $1"."$2}')
    PATCH_VERSION=$(echo "$CURRENT_VN" | awk -F. '{print $3}')
    PATCH_VERSION=${PATCH_VERSION:-0}
    NEW_PATCH=$((PATCH_VERSION + 1))
    NEW_VN="${BASE_VERSION}.${NEW_PATCH}"
fi

RELEASE_NOTES="${CUSTOM_NOTES:-Release version ${NEW_VN} (build ${NEW_VC}).}"

echo "=================================================="
echo "Publishing Tally Delta Update to: $TARGET_DIR"
echo "Current Version : $CURRENT_VN (versionCode $CURRENT_VC)"
echo "New Version     : $NEW_VN (versionCode $NEW_VC)"
echo "Release Notes   : $RELEASE_NOTES"
echo "=================================================="

# 4. Sync gradle.properties
if grep -q "^versionCode=" "$GRADLE_PROPS"; then
    sed -i "s/^versionCode=.*/versionCode=$NEW_VC/" "$GRADLE_PROPS"
else
    echo "versionCode=$NEW_VC" >> "$GRADLE_PROPS"
fi

if grep -q "^versionName=" "$GRADLE_PROPS"; then
    sed -i "s/^versionName=.*/versionName=$NEW_VN/" "$GRADLE_PROPS"
else
    echo "versionName=$NEW_VN" >> "$GRADLE_PROPS"
fi

# 5. Run Gradle publish task
./gradlew generateDeltaUpdate \
  -PversionCode="$NEW_VC" \
  -PversionName="$NEW_VN" \
  -PupdateOutputDir="$TARGET_DIR" \
  -PreleaseNotes="$RELEASE_NOTES"

echo ""
echo "=================================================="
echo "Publishing complete!"
echo "Artifacts stored in: $TARGET_DIR"
echo "  - Manifest : $TARGET_DIR/update-manifest.json"
echo "  - APKs     : $TARGET_DIR/apks/"
echo "  - Patches  : $TARGET_DIR/patches/"
echo "=================================================="
