#!/usr/bin/env bash
#
# Fails if an AlertDialog stacks content in a Column without making it scroll.
#
# Material3's AlertDialog does not scroll its own content. That is not a guess:
# decompiling material3-android 1.3.2 shows zero references to verticalScroll,
# ScrollState or ScrollKt anywhere in AlertDialogKt, AlertDialogImpl,
# AlertDialogContent or BasicAlertDialog. Content taller than the dialog is
# simply clipped - and because the buttons are laid out below the text slot,
# what gets clipped first is the buttons.
#
# The failure needs a short screen to appear, so it survives every test on a
# tall emulator and shows up on a phone in landscape, or with the keyboard up,
# or at a large font scale. Two dialogs in this app had it:
#
#   CertificatePromptDialog  the certificate-changed state carries a paragraph
#                            of prose plus two 95-character fingerprints, and
#                            it is the one dialog that must be readable, since
#                            it is shown when the thing answering at the
#                            printer's address might not be the printer.
#   AddPrinterDialog         three text fields and a chip row, used only ever
#                            with the soft keyboard covering half the screen.
#
# Three other dialogs already did the right thing. This gate is here because
# the convention existed and was applied unevenly - which is exactly the kind
# of rule that needs a machine to remember it rather than a reviewer.
#
# The rule: if a dialog's `text = {` slot lays its children out in a Column,
# that Column applies verticalScroll. A slot holding a single Text is left
# alone; one paragraph is not the shape that pushes buttons off a screen.
set -euo pipefail

ROOT="${1:-app/src/main/java}"

if [[ ! -d "$ROOT" ]]; then
  echo "check-dialog-scroll: no source tree at $ROOT" >&2
  exit 1
fi

STATUS=0
CHECKED=0

while IFS= read -r FILE; do
  # awk walks each AlertDialog from its opening line to the first button slot,
  # which always follows the text slot. Line comments are stripped first so
  # that prose about scrolling cannot satisfy - or trip - the check.
  RESULT="$(
    awk '
      { code = $0; sub(/\/\/.*/, "", code) }

      code ~ /AlertDialog\(/ {
        in_dialog = 1; start = FNR
        in_text = 0; saw_column = 0; saw_scroll = 0
        next
      }

      in_dialog && code ~ /text[ ]*=[ ]*\{/ { in_text = 1; next }

      in_dialog && in_text && code ~ /(confirmButton|dismissButton)[ ]*=/ {
        if (saw_column && !saw_scroll) print start
        in_dialog = 0; in_text = 0
        next
      }

      in_dialog && in_text && code ~ /Column/       { saw_column = 1 }
      in_dialog && in_text && code ~ /verticalScroll/ { saw_scroll = 1 }
    ' "$FILE"
  )"

  CHECKED=$((CHECKED + 1))

  if [[ -n "$RESULT" ]]; then
    while IFS= read -r LINE; do
      echo "FAIL: $FILE:$LINE - AlertDialog Column does not scroll" >&2
      STATUS=1
    done <<< "$RESULT"
  fi
done < <(grep -rl "AlertDialog(" "$ROOT" --include="*.kt" | sort)

if [[ $STATUS -ne 0 ]]; then
  echo >&2
  echo "Material3 clips dialog content it cannot fit, buttons first." >&2
  echo "Give the Column in the text slot a scroll container:" >&2
  echo >&2
  echo "  Column(Modifier.verticalScroll(rememberScrollState())) {" >&2
  echo >&2
  echo "Check it against a short preview, not a full-height one:" >&2
  echo '  @Preview(widthDp = 360, heightDp = 380)' >&2
  exit 1
fi

echo "OK: every AlertDialog with stacked content scrolls it ($CHECKED file(s) checked)."
