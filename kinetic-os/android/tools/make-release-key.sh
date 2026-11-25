#!/bin/sh
# Creates YOUR private release signing key for Kinetic OS and a keystore.properties next to settings.gradle.kts.
# Keep both files secret and backed up: Android only installs updates signed with the same key.
set -eu
cd "$(dirname "$0")/.."
[ -f kinetic-release.jks ] && { echo "kinetic-release.jks already exists – refusing to overwrite"; exit 1; }
printf "Keystore password (min 6 chars): "; stty -echo 2>/dev/null || true; read -r PW; stty echo 2>/dev/null || true; echo
[ ${#PW} -ge 6 ] || { echo "password too short"; exit 1; }
keytool -genkeypair -v -keystore kinetic-release.jks -alias kinetic -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Kinetic OS, O=Private"
umask 077
cat > keystore.properties <<P
storeFile=kinetic-release.jks
storePassword=$PW
keyAlias=kinetic
keyPassword=$PW
P
echo "Done. Build signed APKs with: ./gradlew assembleRelease"
