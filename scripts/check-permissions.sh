#!/usr/bin/env bash
#
# Fails if the APK's permission set differs from the checked-in expectation.
#
# This exists because of what a permission set *is*: not an implementation
# detail, but a promise made to whoever installs the app. It is described in the
# manifest comments, in the README, and in the table the release notes generator
# prints - and every one of those is prose that no compiler checks.
#
# All of them drifted at once. Phase 4 removed ACCESS_FINE_LOCATION and
# BLUETOOTH_SCAN; the build file went on explaining that the legacy flavour
# "carries the pre-API-31 Bluetooth permissions, which drag in
# ACCESS_FINE_LOCATION", and the release notes shipped a table telling readers
# the legacy build wanted their location. That table was caught by hand, minutes
# before v1.1.0 went public, and only because someone happened to read it.
#
# So the expectation is checked in as data. Changing what the app asks for now
# means changing this file, and changing this file is the prompt to change every
# sentence that describes it. The check cannot verify the prose - but it can
# refuse to let the permission set move quietly underneath it.
#
# Note this is a two-way check. An unexpected *removal* fails too: a permission
# silently dropped by a manifest-merger change is how a feature stops working on
# a device nobody tests on.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPECTED="$HERE/expected-permissions.txt"

if [[ $# -gt 0 ]]; then
  APKS=("$@")
else
  # shellcheck disable=SC2207
  APKS=($(find app/build/outputs/apk -name "*.apk" 2>/dev/null | sort))
fi

if [[ ${#APKS[@]} -eq 0 ]]; then
  echo "check-permissions: no APKs found; build one first" >&2
  exit 1
fi

if [[ ! -f "$EXPECTED" ]]; then
  echo "check-permissions: no expectation checked in at $EXPECTED" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AAPT2="$(find "$SDK/build-tools" -name aapt2 -type f 2>/dev/null | sort -V | tail -1)"

if [[ -z "$AAPT2" ]]; then
  echo "check-permissions: no aapt2 found under $SDK/build-tools" >&2
  exit 1
fi

STATUS=0
for APK in "${APKS[@]}"; do
  BASE="$(basename "$APK")"

  if [[ ! -f "$APK" ]]; then
    echo "check-permissions: no APK at $APK" >&2
    STATUS=1
    continue
  fi

  # Debug builds carry applicationIdSuffix ".debug", which lands in the
  # androidx-generated DYNAMIC_RECEIVER permission. Normalise it away so one
  # expectation covers debug and release: the suffix is a build-type artefact,
  # not something the app asks the user for.
  ACTUAL="$("$AAPT2" dump badging "$APK" |
    grep -E "^uses-permission" |
    sed "s/^uses-permission: name='com\.gulshan\.pocketprint\.debug\./uses-permission: name='com.gulshan.pocketprint./" |
    sort)"

  # `|| true` so set -e does not kill the run on the branch this exists to
  # report. diff exits 1 for "files differ", which is a finding, not a crash.
  DELTA="$(diff -u "$EXPECTED" <(printf '%s\n' "$ACTUAL") | tail -n +3 || true)"

  if [[ -z "$DELTA" ]]; then
    echo "OK: $BASE asks for exactly the documented permissions."
  else
    echo "FAIL: $BASE does not ask for the permissions $EXPECTED describes." >&2
    echo "      (-) expected and missing, (+) present and unexpected" >&2
    echo >&2
    printf '%s\n' "$DELTA" >&2
    echo >&2
    echo "If the change is intended, update $EXPECTED - and then update every" >&2
    echo "place that describes the permission set in words, because none of" >&2
    echo "them are checked by anything:" >&2
    echo "  - the comments in app/src/main/AndroidManifest.xml" >&2
    echo "  - the permissions table in README.md" >&2
    echo "  - the table in .github/workflows/release.yml, which is published" >&2
    echo "    verbatim as the release notes" >&2
    STATUS=1
  fi
done

exit $STATUS
