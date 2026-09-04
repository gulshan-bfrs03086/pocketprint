#!/usr/bin/env bash
#
# Guards the one place this app reaches outside the public SDK.
#
# WebView's PrintDocumentAdapter is the only way to turn a page into a properly
# paginated PDF, and the only way to drive it without the system print dialog is
# to put a class inside the android.print package, where the two result
# callbacks have package-private constructors. That is a non-SDK dependency: it
# works today, it is unsupported, and the day it stops working every shared link
# and every HTML document stops printing at once.
#
# So two things are checked. Nothing new may appear in a platform package - one
# knowing exception is a decision, a second is a habit. And the public-API
# fallback must still be wired in, so the unsupported path can never quietly
# become the only path again.
set -euo pipefail

cd "$(dirname "$0")/.."

STATUS=0

# --- 1. Only the known exception may declare a platform package.
EXPECTED="app/src/main/java/android/print/PdfPrint.kt"
# shellcheck disable=SC2207
FOUND=($(grep -rl --include="*.kt" --include="*.java" \
  -E '^package (android|com\.android|dalvik|libcore)\b' app/src 2>/dev/null | sort))

for file in "${FOUND[@]:-}"; do
  [[ -z "$file" ]] && continue
  if [[ "$file" != "$EXPECTED" ]]; then
    echo "FAIL: $file declares a package inside the platform's own namespace." >&2
    echo "      That reaches package-private platform API, which is unsupported" >&2
    echo "      and can stop working on any Android release. If it is genuinely" >&2
    echo "      the only way, add it to EXPECTED here with the reason - and give" >&2
    echo "      it a public-API fallback, as android/print/PdfPrint.kt has." >&2
    STATUS=1
  fi
done

if [[ ! -f "$EXPECTED" ]]; then
  echo "NOTE: $EXPECTED is gone. If the print adapter is now reachable through" >&2
  echo "      public API, delete this check with it." >&2
fi

# --- 2. The fallback must still be reachable from the caller.
CALLER="app/src/main/java/com/gulshan/pocketprint/render/WebToPdf.kt"
FALLBACK="app/src/main/java/com/gulshan/pocketprint/render/WebCanvasToPdf.kt"

if [[ -f "$EXPECTED" ]]; then
  if [[ ! -f "$FALLBACK" ]]; then
    echo "FAIL: the public-API fallback $FALLBACK is missing." >&2
    STATUS=1
  elif ! grep -q "WebCanvasToPdf" "$CALLER"; then
    echo "FAIL: $CALLER no longer calls the fallback, so the unsupported print" >&2
    echo "      adapter path is the only one left. Every shared link and HTML" >&2
    echo "      document would fail together the day it breaks." >&2
    STATUS=1
  fi
fi

if [[ $STATUS -eq 0 ]]; then
  echo "OK: one known platform-package exception, and its fallback is wired in."
fi

exit $STATUS
