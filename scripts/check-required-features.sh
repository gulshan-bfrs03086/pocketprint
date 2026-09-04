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

# Check every APK given, or every APK in the build output by default. Release
# builds are included: they are the ones that get published, and R8 and resource
# shrinking sit between the manifest and what actually ships.
if [[ $# -gt 0 ]]; then
  APKS=("$@")
else
  # shellcheck disable=SC2207
  APKS=($(find app/build/outputs/apk -name "*.apk" 2>/dev/null | sort))
fi

if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "check-required-features: no APKs found; build one first" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f 2>/dev/null | sort -V | tail -1)"

if [[ -z "$AAPT2" ]]; then
  echo "check-required-features: no aapt2 found under $SDK/build-tools" >&2
  exit 1
fi

STATUS=0
for APK in "${APKS[@]}"; do
  if [[ ! -f "$APK" ]]; then
    echo "check-required-features: no APK at $APK" >&2
    STATUS=1
    continue
  fi

  # `|| true` because grep exits 1 when it matches nothing, which is the good case.
  REQUIRED="$("$AAPT2" dump badging "$APK" | grep -E "^  uses-feature: name=" || true)"

  if [[ -n "$REQUIRED" ]]; then
    echo "FAIL: $APK declares required hardware features:" >&2
    echo "$REQUIRED" >&2
    echo >&2
    echo "The package manager will refuse to install on any device lacking these." >&2
    echo "Declare each one optional in the manifest for that flavour:" >&2
    echo '  <uses-feature android:name="NAME" android:required="false" />' >&2
    echo >&2
    echo "To see which permission implied it:" >&2
    echo "  $AAPT2 dump badging $APK | grep uses-implied-feature" >&2
    STATUS=1
  else
    echo "OK: $(basename "$APK") requires no hardware features."
  fi
done

exit $STATUS
