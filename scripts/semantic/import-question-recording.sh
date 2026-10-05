#!/usr/bin/env bash
# Imports a question corpus recording made on the phone by the debug build's "AI test set" screen
# ("Run question set"; app-phone src/debug, debugtools.TagCorpusRunnerActivity) and shared off the
# phone, e.g. to Google Drive.
#
# Usage:
#   scripts/semantic/import-question-recording.sh <path-to-question-device-recording.json>
#   e.g. scripts/semantic/import-question-recording.sh "G:/My Drive/question-device-recording.json"
#
# What it does, no questions asked:
#   1. copies the given file into the git-ignored scratch folder build/question-recording-import/
#      (the original is never modified),
#   2. checks it cheaply: JSON parses (python or node if available), "source" is DEVICE,
#      "formatVersion" is 1 and it has entries; with neither available, a basic text check,
#   3. only if that passes, keeps a backup of the current
#      core-testing/src/test/resources/semantic-corpus/recordings/question-device-latest.json (if any)
#      in the scratch folder and overwrites it with the new recording,
#   4. runs the structural check QuestionRecordingFilesTest (decodes strictly, matches the current
#      question corpus hash, DEVICE source, one entry per case), then the question scorer/gate
#      QuestionRegressionGateTest, which replays it through the real LookupService, writes
#      core-testing/build/reports/semantic-corpus/question-device.md and, once
#      recordings/question-baseline.json exists, gates on it.
#
# No phone, adb or network is involved. The only repository file it overwrites is
# question-device-latest.json. Works in Git Bash on Windows and on macOS/Linux.
#
# Exit codes: 0 imported and structural check passed;
#             1 usage/setup problem or the file failed the cheap check (nothing overwritten);
#             3 imported but the structural check or the question gate FAILED (the new file is kept;
#               the previous one, if any, is in the backup named in the output).
set -euo pipefail

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit "${2:-1}"; }

[ "$#" -eq 1 ] || die "Usage: $0 <path-to-question-device-recording.json>"
SOURCE_ARG="$1"
[ -f "$SOURCE_ARG" ] || die "No such file: $SOURCE_ARG"
[ -s "$SOURCE_ARG" ] || die "The file is empty: $SOURCE_ARG. Nothing in the repository was changed."

CHECK_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRecordingFilesTest"
GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.QuestionRegressionGateTest"
REPORT_FILE="core-testing/build/reports/semantic-corpus/question-device.md"
DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/question-device-latest.json"
WORK_DIR="build/question-recording-import"
STAGED_FILE="${WORK_DIR}/question-device-recording.imported.json"
BACKUP_FILE="${WORK_DIR}/question-device-latest.previous.json"

# Copy the file in BEFORE moving to the repository root, so a path relative to the caller's
# directory still works. Every later path is relative to the repository root (relative paths also
# avoid Git Bash rewriting absolute paths handed to Windows programs such as python.exe).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$REPO_ROOT/$WORK_DIR"
rm -f "$REPO_ROOT/$STAGED_FILE"
cp "$SOURCE_ARG" "$REPO_ROOT/$STAGED_FILE" \
    || die "Could not copy $SOURCE_ARG. Nothing in the repository was changed."
cd "$REPO_ROOT"

say "== Question corpus: importing a recording made on the phone =="
say "From: $SOURCE_ARG"
say ""

# --- cheap check before overwriting anything ------------------------------------------------------
validate_with_python() {
    "$1" - "$STAGED_FILE" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    doc = json.load(f)
if not isinstance(doc, dict):
    sys.exit("recording is not a JSON object")
if doc.get("formatVersion") != 1:
    sys.exit("recording formatVersion is not 1")
if doc.get("source") != "DEVICE":
    sys.exit("recording source is not DEVICE")
entries = doc.get("entries")
if not isinstance(entries, list) or not entries:
    sys.exit("recording has no entries")
print(len(entries))
PY
}
validate_with_node() {
    node -e '
const doc = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
if (doc === null || typeof doc !== "object" || Array.isArray(doc)) { console.error("recording is not a JSON object"); process.exit(1); }
if (doc.formatVersion !== 1) { console.error("recording formatVersion is not 1"); process.exit(1); }
if (doc.source !== "DEVICE") { console.error("recording source is not DEVICE"); process.exit(1); }
if (!Array.isArray(doc.entries) || doc.entries.length === 0) { console.error("recording has no entries"); process.exit(1); }
console.log(doc.entries.length);' "$STAGED_FILE"
}

ENTRY_COUNT=""
VALIDATED=""
for py in python3 python; do
    # Skip stubs (e.g. the Windows Store "python3" alias) that exist but cannot run code.
    if command -v "$py" >/dev/null 2>&1 && "$py" -c 'import json' >/dev/null 2>&1; then
        ENTRY_COUNT="$(validate_with_python "$py")" \
            || die "The recording is not valid (see above). Nothing in the repository was changed."
        VALIDATED="$py"
        break
    fi
done
if [ -z "$VALIDATED" ] && command -v node >/dev/null 2>&1; then
    ENTRY_COUNT="$(validate_with_node)" \
        || die "The recording is not valid (see above). Nothing in the repository was changed."
    VALIDATED="node"
fi
if [ -z "$VALIDATED" ]; then
    # No JSON parser available: a cheap text check. QuestionRecordingFilesTest below parses it fully.
    grep -q '"entries"' "$STAGED_FILE" && grep -q '"caseId"' "$STAGED_FILE" \
        && grep -q '"source": *"DEVICE"' "$STAGED_FILE" \
        || die "The recording does not look complete. Nothing in the repository was changed."
    ENTRY_COUNT="$(grep -c '"caseId"' "$STAGED_FILE" || true)"
    VALIDATED="grep (no python/node found; basic check only)"
fi
say "Recording checked with $VALIDATED: $ENTRY_COUNT entries."

# --- overwrite, keeping the previous file ----------------------------------------------------------
rm -f "$BACKUP_FILE"
if [ -f "$DEST_FILE" ]; then
    cp "$DEST_FILE" "$BACKUP_FILE"
    say "Previous recording backed up to $BACKUP_FILE"
fi
cp "$STAGED_FILE" "$DEST_FILE"
say "Saved to $DEST_FILE"

# --- structural check, then score and gate ---------------------------------------------------------
say ""
say "== Checking the question recording's structure =="
set +e
./gradlew :core-testing:test --tests "$CHECK_CLASS"
CHECK_STATUS=$?
set -e
say ""
if [ "$CHECK_STATUS" -ne 0 ]; then
    say "CHECK FAILED (Gradle exit $CHECK_STATUS): the question recording does not match the current"
    say "question corpus"
    say "(for example it was recorded before the test questions changed), or could not be decoded."
    say "The new recording was kept at $DEST_FILE."
    [ -f "$BACKUP_FILE" ] && say "The previous one is at $BACKUP_FILE (copy it back to undo)."
    say "Details: core-testing/build/reports/tests/test/index.html"
    exit 3
fi

say ""
say "== Scoring the question recording (question regression gate) =="
set +e
./gradlew :core-testing:test --tests "$GATE_CLASS"
GATE_STATUS=$?
set -e
say ""
if [ "$GATE_STATUS" -ne 0 ]; then
    say "QUESTION GATE FAILED (Gradle exit $GATE_STATUS): the new question recording regressed"
    say "against question-baseline.json, or could not be replayed. The new recording was kept at $DEST_FILE."
    [ -f "$BACKUP_FILE" ] && say "The previous one is at $BACKUP_FILE (copy it back to undo)."
    [ -f "$REPORT_FILE" ] && say "Report: $REPORT_FILE"
    say "Details: core-testing/build/reports/tests/test/index.html"
    exit 3
fi
say "Done. Phone recording imported, checked and scored."
say "(Gated only once recordings/question-baseline.json exists; until then the report is informational.)"
if [ -f "$REPORT_FILE" ]; then
    say "Report: $REPORT_FILE"
else
    say "Note: expected report $REPORT_FILE was not found."
fi
