#!/usr/bin/env bash
# Fallback: build the broker APK inside a proot-distro Ubuntu rootfs when the
# host SDK/Gradle path fails. Run on a device/host that has proot-distro (e.g. Termux).
set -euo pipefail

DISTRO="${PROOT_DISTRO:-ubuntu}"
PROJECT_HOST="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_IN="/workspace/shizuku-ai-action-broker"
SDK_IN="/opt/android-sdk"

echo "==> Ensure distro: $DISTRO"
if ! proot-distro list 2>/dev/null | grep -qi "$DISTRO"; then
  proot-distro install "$DISTRO"
fi

echo "==> Sync project into distro ($PROJECT_IN)"
proot-distro login "$DISTRO" -- bash -lc "mkdir -p $PROJECT_IN /opt"
# Copy sources (host path visible depending on bind; Termux often needs explicit copy)
if [ -d /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs ]; then
  ROOTFS=$(echo /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/$DISTRO*)
  mkdir -p "$ROOTFS$PROJECT_IN"
  rsync -a --delete --exclude build --exclude .gradle "$PROJECT_HOST/" "$ROOTFS$PROJECT_IN/" || \
    cp -a "$PROJECT_HOST/." "$ROOTFS$PROJECT_IN/"
fi

proot-distro login "$DISTRO" -- bash -lc "
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk-headless wget unzip curl ca-certificates
export JAVA_HOME=\$(dirname \$(dirname \$(readlink -f \$(which javac))))
export ANDROID_HOME=$SDK_IN
export ANDROID_SDK_ROOT=\$ANDROID_HOME
mkdir -p \$ANDROID_HOME/cmdline-tools
if [ ! -x \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager ]; then
  cd /tmp
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdtools.zip
  unzip -qo cmdtools.zip
  mkdir -p \$ANDROID_HOME/cmdline-tools/latest
  mv cmdline-tools/* \$ANDROID_HOME/cmdline-tools/latest/
fi
yes | \$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=\$ANDROID_HOME --licenses >/tmp/licenses.log || true
\$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=\$ANDROID_HOME \
  'platform-tools' 'platforms;android-34' 'build-tools;34.0.0'
cd $PROJECT_IN
echo \"sdk.dir=\$ANDROID_HOME\" > local.properties
chmod +x gradlew || true
./gradlew :app:assembleDebug --no-daemon
ls -lh app/build/outputs/apk/debug/app-debug.apk
"

echo "==> Done. Pull APK from distro rootfs if needed:"
echo "    $PROJECT_IN/app/build/outputs/apk/debug/app-debug.apk"
