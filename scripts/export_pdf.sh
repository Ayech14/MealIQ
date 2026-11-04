#!/bin/sh
# Rebuild docs/stage2/stage2-report.docx from the Markdown and export it to PDF with
# Microsoft Word (macOS). Usage: scripts/export_pdf.sh
set -e
cd "$(dirname "$0")/.."
DIR="$PWD/docs/stage2"
python3 scripts/build_report.py
rm -f "$DIR/stage2-report.pdf"
open -a "Microsoft Word" "$DIR/stage2-report.docx"
# Wait until Word has opened the document.
i=0
until [ "$(osascript -e 'tell application "Microsoft Word" to count documents')" != "0" ]; do
  i=$((i + 1)); [ $i -gt 40 ] && { echo "Word did not open the document" >&2; exit 1; }
  sleep 1
done
osascript <<APPLESCRIPT
tell application "Microsoft Word"
  set d to document "stage2-report.docx"
  save as d file name (POSIX file "$DIR/stage2-report.pdf" as text) file format format PDF
  close d saving no
end tell
APPLESCRIPT
echo "Wrote $DIR/stage2-report.pdf"
