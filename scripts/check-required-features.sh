#!/usr/bin/env bash
#
# Fails if the APK declares any REQUIRED hardware feature.
#
# Android refuses to install a package whose required features the device lacks,
# and the installer reports only a generic "Can't install the app". This bit us
# for real: ACCESS_FINE_LOCATION (declared solely for pre-API-31 Bluetooth
# discovery) made aapt imply android.hardware.location as required, which made
# the app uninstallable on a rugged handheld with no GPS.
#
# aapt2 distinguishes the two cases in `dump badging`:
#   uses-feature-not-required: name='...'   <- fine, optional
#   uses-feature: name='...'                <- REQUIRED, gates installation
#
# A printing app should require no hardware at all: every transport is optional.
set -euo pipefail

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"

if [[ ! -f "$APK" ]]; then
  echo "check-required-features: no APK at $APK" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f 2>/dev/null | sort -V | tail -1)"

if [[ -z "$AAPT2" ]]; then
  echo "check-required-features: no aapt2 found under $SDK/build-tools" >&2
  exit 1
fi

# `|| true` because grep exits 1 when it matches nothing, which is the good case.
REQUIRED="$("$AAPT2" dump badging "$APK" | grep -E "^  uses-feature: name=" || true)"

if [[ -n "$REQUIRED" ]]; then
  echo "FAIL: $APK declares required hardware features:" >&2
  echo "$REQUIRED" >&2
  echo >&2
  echo "The package manager will refuse to install on any device lacking these." >&2
  echo "Declare each one optional in AndroidManifest.xml:" >&2
  echo '  <uses-feature android:name="NAME" android:required="false" />' >&2
  echo >&2
  echo "If a permission implied it, the implication is shown by:" >&2
  echo "  $AAPT2 dump badging $APK | grep uses-implied-feature" >&2
  exit 1
fi

echo "OK: $APK requires no hardware features."
