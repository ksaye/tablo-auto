#!/usr/bin/env bash
# Build a signed Tablo Auto APK.
#
# Usage:  ./build-apk.sh <version> <versionCode>
#         ./build-apk.sh 1.0.0 1
#
# <versionCode> is Android's integer version and must increase with every release; <version> is
# what people see, and what the self-updater compares against a release tag.
#
# SIGNING: Android only installs an update over an app signed with the SAME key, so keep the
# keystore safe and reuse it for every release. Create one once:
#
#   keytool -genkeypair -v -keystore tabloauto.keystore -alias tabloauto \
#           -keyalg RSA -keysize 4096 -validity 10950
#
# and put the details in a keystore.env beside this script (it is not committed):
#
#   KEYSTORE_PATH=/path/to/tabloauto.keystore
#   KEYSTORE_PASSWORD=...
#   KEY_ALIAS=tabloauto
#   KEY_PASSWORD=...
#
# Without that file the build still works and produces a debug-signed APK, which is fine for
# trying the app but cannot upgrade a real install.
#
# Needs a JDK 17 and the Android SDK (platform 36). JAVA_HOME and ANDROID_HOME are honoured.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION=${1:?version, e.g. 1.0.0}
BUILD=${2:?versionCode, e.g. 1}

cd "$HERE"

# Write the version into the build file rather than passing it in, so that what was released and
# what is in the repository cannot drift apart.
sed -i -E "s/^( *versionCode = ).*/\1$BUILD/" app/build.gradle.kts
sed -i -E "s/^( *versionName = ).*/\1\"$VERSION\"/" app/build.gradle.kts

./gradlew :app:assembleRelease --no-daemon

OUT="app/build/outputs/apk/release"
APK="$OUT/app-release.apk"
[ -f "$APK" ] || APK="$OUT/app-release-unsigned.apk"
[ -f "$APK" ] || { echo "no APK was produced"; exit 1; }

NAMED="$OUT/io.github.ksaye.tabloauto-$VERSION.apk"
cp "$APK" "$NAMED"

echo
echo "built $NAMED"
if [ -f keystore.env ]; then
    echo "signed with the release key:"
    "${ANDROID_HOME:-$HOME/android-sdk}"/build-tools/36.0.0/apksigner verify --print-certs "$NAMED" \
        | grep -i "SHA-256 digest" | head -1
else
    echo "NOTE: no keystore.env, so this is debug-signed and cannot upgrade a real install."
fi
