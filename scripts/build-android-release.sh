#!/usr/bin/env bash
# Signing credentials must be provided by the local caller, never from the repo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for name in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required signing variable: $name" >&2
    exit 1
  fi
done
[[ -f "$ANDROID_KEYSTORE_PATH" ]] || { echo "Signing keystore does not exist" >&2; exit 1; }
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$SDK" ]] || { echo "Set ANDROID_HOME to Android SDK 35" >&2; exit 1; }
APKSIGNER="$SDK/build-tools/35.0.0/apksigner"
[[ -x "$APKSIGNER" ]] || { echo "Android Build Tools 35.0.0 are required" >&2; exit 1; }
"$ROOT/scripts/prepare-trusttunnel-android.sh"
"$ROOT/android/gradlew" -p "$ROOT/android" --no-daemon --max-workers=1   -Dorg.gradle.jvmargs='-Xmx1024m -Dfile.encoding=UTF-8'   -Pkotlin.compiler.execution.strategy=in-process   :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"
AAB="$ROOT/android/app/build/outputs/bundle/release/app-release.aab"
"$APKSIGNER" verify --verbose "$APK"
# keytool resolves the alias with an environment password, never a command-line value.
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
JARSIGNER="${JAVA_HOME:+$JAVA_HOME/bin/}jarsigner"
CERT="$(mktemp)"
TRUST="$(mktemp)"
rm -f "$TRUST"
trap 'rm -f "$CERT" "$TRUST"' EXIT
"$KEYTOOL" -exportcert -keystore "$ANDROID_KEYSTORE_PATH" -alias "$ANDROID_KEY_ALIAS"   -storepass:env ANDROID_KEYSTORE_PASSWORD -file "$CERT"
# Match the APK signer to the selected keystore, not merely any valid signature.
EXPECTED="$(shasum -a 256 "$CERT" | awk '{print $1}')"
ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" | awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print $NF}')"
[[ "$ACTUAL" == "$EXPECTED" ]] || { echo "APK signer does not match the selected keystore" >&2; exit 1; }
# Trust the owner's certificate for verifying this self-signed app-signing identity.
"$KEYTOOL" -importcert -noprompt -alias release -file "$CERT" -keystore "$TRUST"   -storepass:env ANDROID_KEYSTORE_PASSWORD
"$JARSIGNER" -verify -strict -keystore "$TRUST"   -storepass:env ANDROID_KEYSTORE_PASSWORD "$AAB"
echo "Signed release APK: $APK"
echo "Signed Android App Bundle: $AAB"
