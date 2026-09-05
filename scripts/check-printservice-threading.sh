#!/usr/bin/env bash
#
# Keeps every main-thread-only print-framework call inside one file.
#
# Every accessor and transition on PrintJob and PrintDocument, generatePrinterId
# on PrintService, and addPrinters / removePrinters and their siblings on
# PrinterDiscoverySession begin with PrintService.throwIfNotCalledOnMainThread().
# Only PrintJob's methods are annotated @MainThread; the rest just throw, so
# Android Lint's WrongThread check cannot see them.
#
# What they throw is an IllegalAccessError - an Error, not an Exception. A
# `catch (e: Exception)` lets it through; runCatching does not. This app wrapped
# the calls in runCatching, the Error was logged at warn level, and the system
# print dialog searched forever. Three separate violations presented that way,
# in three places, before this check existed.
#
# So the rule is structural rather than a search through coroutine bodies, which
# would be a fragile thing to grep for. One file owns the framework handles and
# may not dispatch onto any other thread; every other file in the package may
# receive a PrintJob only as an override parameter and only hand it straight to
# that file. If the handle never leaves the callback, there is no thread for it
# to be on except the right one.
set -euo pipefail

cd "$(dirname "$0")/.."

PKG="app/src/main/java/com/gulshan/pocketprint/printservice"
OWNER="$PKG/PrintFramework.kt"
STATUS=0

if [[ ! -f "$OWNER" ]]; then
  echo "FAIL: $OWNER is missing. It is the one place allowed to call the print" >&2
  echo "      framework's main-thread-only API; without it this check has nothing" >&2
  echo "      to hold the line against." >&2
  exit 1
fi

# Emits "file:line:text" for every code line in $2 matching $1. Comment lines
# are skipped: the rules below are about calls, and every one of them is
# explained in a comment that has to be allowed to name the thing it forbids.
matches_in_code() {
  local pattern="$1" file="$2" hit text stripped
  grep -nE "$pattern" "$file" 2>/dev/null | while IFS= read -r hit; do
    text="${hit#*:}"
    stripped="${text#"${text%%[![:space:]]*}"}"
    case "$stripped" in
      //*|\**|/\**) continue ;;
    esac
    printf '%s:%s\n' "$file" "$hit"
  done
}

# --- 1. Main-thread-only members of PrintService and PrinterDiscoverySession
#        appear only in the owner. These are the Java names and the Kotlin
#        property forms of the same accessors.
GUARDED='generatePrinterId|activePrintJobs|getActivePrintJobs|addPrinters|removePrinters|trackedPrinters|getTrackedPrinters|isPrinterDiscoveryStarted|\bisDestroyed\b|getPrinters\(\)|\.printers\b'

for file in "$PKG"/*.kt; do
  [[ "$file" == "$OWNER" ]] && continue
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    echo "FAIL: $hit" >&2
    echo "      calls a main-thread-only framework method outside $OWNER." >&2
    echo "      Off the main thread it throws IllegalAccessError, which runCatching" >&2
    echo "      swallows. Route it through PrintFramework, which posts to the looper." >&2
    STATUS=1
  done < <(matches_in_code "$GUARDED" "$file")
done

# --- 2. Outside the owner, a PrintJob or PrintDocument may only be received by
#        an override and passed straight to PrintFramework. Any other mention -
#        an accessor, a capture into a coroutine, a field - is the bug.
for file in "$PKG"/*.kt; do
  [[ "$file" == "$OWNER" ]] && continue
  while IFS= read -r hit; do
    [[ -z "$hit" ]] && continue
    text="${hit#*:*:}"
    stripped="${text#"${text%%[![:space:]]*}"}"
    case "$stripped" in
      import\ android.printservice.*) continue ;;
    esac
    if [[ "$stripped" == *"override fun"* ]] || [[ "$stripped" == *"PrintFramework."* ]]; then
      continue
    fi
    echo "FAIL: $hit" >&2
    echo "      touches a print-framework job handle outside $OWNER. A PrintJob" >&2
    echo "      must be handed to PrintFramework.take() on the callback's own thread" >&2
    echo "      and never referenced again; every one of its methods throws off the" >&2
    echo "      main thread." >&2
    STATUS=1
  done < <(matches_in_code '\bprintJob\b|\bPrintJob\b|\bPrintDocument\b' "$file")
done

# --- 3. The owner must not be able to leave the main thread. If it can start a
#        coroutine or a thread, rule 1 proves nothing.
DISPATCH='\blaunch\b|withContext|Dispatchers|\bThread\(|Executor|\basync\(|runBlocking|CoroutineScope'
while IFS= read -r hit; do
  [[ -z "$hit" ]] && continue
  echo "FAIL: $hit" >&2
  echo "      $OWNER may not dispatch onto another thread. Every framework call in" >&2
  echo "      it is safe only because the file cannot run anywhere but the looper." >&2
  STATUS=1
done < <(matches_in_code "$DISPATCH" "$OWNER")

if ! grep -q 'Looper.getMainLooper()' "$OWNER"; then
  echo "FAIL: $OWNER no longer posts through Looper.getMainLooper()." >&2
  STATUS=1
fi

if [[ $STATUS -eq 0 ]]; then
  echo "OK: print-framework handles stay inside $(basename "$OWNER"), which cannot leave the main thread."
fi

exit $STATUS
