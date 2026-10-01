#!/usr/bin/env bash
# Records the semantic regression corpus on a real phone and scores it.
#
# One command, no questions asked:
#   1. checks exactly one phone is connected (or uses ANDROID_SERIAL),
#   2. builds and runs ONLY the on-device recorder test
#      (com.mcfrenchpants.activityledger.semantic.SemanticCorpusRecorderTest) with its opt-in
#      argument semanticCorpus=true,
#   3. pulls the phone's recording, checks it, and only then overwrites
#      core-testing/src/test/resources/semantic-corpus/recordings/device-latest.json,
#   4. runs the JVM regression gate (SemanticRegressionGateTest) and says where the report is.
#
# With --tags as the first argument it records the TAG corpus (subject + action, 72 sentences)
# through the extraction prompt instead: it runs TagCorpusRecorderTest with tagCorpus=true, pulls
# .../files/semantic-corpus/tag-device-recording.json, does the same checks before overwriting,
# writes recordings/tag-device-latest.json, then runs the structural check TagRecordingFilesTest
# and the tag scorer/gate TagRegressionGateTest (report core-testing/build/reports/semantic-corpus/
# tag-device.md; gated by recordings/tag-baseline.json once that exists). Without --tags nothing
# changes.
#
# Never downloads the on-device model (if it is not ready the recorder skips and this script
# says so). Never uninstalls the app or clears its data. The only file it overwrites in the
# repository is device-latest.json (tag-device-latest.json with --tags). Works in Git Bash on
# Windows and on macOS/Linux.
#
# Exit codes: 0 recorded and gate passed; 1 setup/run problem (nothing overwritten);
#             2 model not ready, recorder skipped (nothing overwritten);
#             3 recording saved but the regression gate FAILED
#               (with --tags: saved but its structural check or the tag gate FAILED).
set -euo pipefail

# Optional first argument: --tags records the tag corpus instead (see the header).
TAG_MODE=0
if [ "${1:-}" = "--tags" ]; then
    TAG_MODE=1
    shift
fi

PACKAGE="com.mcfrenchpants.activityledger"
RECORDER_CLASS="com.mcfrenchpants.activityledger.semantic.SemanticCorpusRecorderTest"
GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest"
# The recorder writes to context.getExternalFilesDir(null)/semantic-corpus/device-recording.json.
DEVICE_FILE="/sdcard/Android/data/${PACKAGE}/files/semantic-corpus/device-recording.json"
DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/device-latest.json"
REPORT_FILE="core-testing/build/reports/semantic-corpus/device.md"
WORK_DIR="app-phone/build/semantic-corpus-run"
PULLED_FILE="${WORK_DIR}/device-recording.pulled.json"
RESULTS_DIR="app-phone/build/outputs/androidTest-results"
OPT_IN_ARG="semanticCorpus"
LOG_TAG="SemanticCorpusRecorder"
if [ "$TAG_MODE" -eq 1 ]; then
    RECORDER_CLASS="com.mcfrenchpants.activityledger.semantic.TagCorpusRecorderTest"
    # Structural check of the recording files first, then the tag scorer/gate.
    GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.TagRecordingFilesTest"
    TAG_GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.TagRegressionGateTest"
    # The recorder writes to context.getExternalFilesDir(null)/semantic-corpus/tag-device-recording.json.
    DEVICE_FILE="/sdcard/Android/data/${PACKAGE}/files/semantic-corpus/tag-device-recording.json"
    DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/tag-device-latest.json"
    REPORT_FILE="core-testing/build/reports/semantic-corpus/tag-device.md"
    PULLED_FILE="${WORK_DIR}/tag-device-recording.pulled.json"
    OPT_IN_ARG="tagCorpus"
    LOG_TAG="TagCorpusRecorder"
fi

# Run from the repository root; every local path below is relative to it (relative paths also
# avoid Git Bash rewriting absolute paths handed to the Windows adb.exe).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit "${2:-1}"; }

if [ "$TAG_MODE" -eq 1 ]; then
say "== Tag corpus (subject + action): on-device recording =="
say "This will:"
say "  - build the app and its test package and install them on ONE connected phone"
say "    (the app is NOT uninstalled afterwards and its data is left alone),"
say "  - run every tag corpus sentence (72) through the phone's on-device model,"
say "    at roughly 6-7 seconds each: about 8 minutes, plus a few minutes of build/install,"
say "  - bring the recording back into the repository, check its structure and score it"
say "    (report tag-device.md; gated once tag-baseline.json exists)."
else
say "== Semantic corpus: on-device recording =="
say "This will:"
say "  - build the app and its test package and install them on ONE connected phone"
say "    (the app is NOT uninstalled afterwards and its data is left alone),"
say "  - run every corpus sentence (about 45-50) through the phone's on-device model,"
say "    at roughly 6-7 seconds each: about 5-6 minutes, plus a few minutes of build/install,"
say "  - bring the recording back into the repository and score it."
fi
say "Keep the phone unlocked with the screen on for the whole run: the model only answers"
say "while the app is in the foreground. Nothing is downloaded to the phone."
say ""

# --- adb -----------------------------------------------------------------------------------
ADB=""
if command -v adb >/dev/null 2>&1; then
    ADB="adb"
else
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        if [ -n "$sdk" ]; then
            for candidate in "$sdk/platform-tools/adb" "$sdk/platform-tools/adb.exe"; do
                if [ -x "$candidate" ]; then ADB="$candidate"; break 2; fi
            done
        fi
    done
fi
[ -n "$ADB" ] || die "adb not found. Install Android platform-tools, or put adb on PATH, or set ANDROID_HOME."

# Git Bash would otherwise rewrite device paths such as /sdcard/... into Windows paths.
adb_raw() { MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "$ADB" "$@" | tr -d '\r'; }

# --- exactly one device ----------------------------------------------------------------------
DEVICE_LIST="$(adb_raw devices)" || die "'adb devices' failed; is the adb server able to start?"
READY_SERIALS="$(printf '%s\n' "$DEVICE_LIST" | awk 'NR > 1 && $2 == "device" { print $1 }')"
OTHER_STATES="$(printf '%s\n' "$DEVICE_LIST" | awk 'NR > 1 && NF >= 2 && $2 != "device" { print "  " $1 " (" $2 ")" }')"
READY_COUNT="$(printf '%s\n' "$READY_SERIALS" | grep -c . || true)"

if [ -n "${ANDROID_SERIAL:-}" ]; then
    printf '%s\n' "$READY_SERIALS" | grep -qx -- "$ANDROID_SERIAL" \
        || die "ANDROID_SERIAL is '$ANDROID_SERIAL', but no ready device with that serial is connected.
Ready devices:
$(printf '%s\n' "$READY_SERIALS" | sed 's/^/  /')
$( [ -n "$OTHER_STATES" ] && printf 'Not ready:\n%s' "$OTHER_STATES" )"
    SERIAL="$ANDROID_SERIAL"
elif [ "$READY_COUNT" -eq 0 ]; then
    die "No phone is connected and ready.
Connect the phone by USB, or over Wi-Fi with 'adb connect <phone-ip>:<port>', and accept the
debugging prompt on the phone.$( [ -n "$OTHER_STATES" ] && printf '\nSeen but not ready (unlock the phone / accept the prompt):\n%s' "$OTHER_STATES" )"
elif [ "$READY_COUNT" -gt 1 ]; then
    die "More than one device is connected; pick one by setting ANDROID_SERIAL, e.g.
  ANDROID_SERIAL=<serial> $0
Connected devices:
$(printf '%s\n' "$READY_SERIALS" | sed 's/^/  /')"
else
    SERIAL="$READY_SERIALS"
fi
# Gradle's connected test tasks honour ANDROID_SERIAL, so this pins the run to one device.
export ANDROID_SERIAL="$SERIAL"
adb_dev() { adb_raw -s "$SERIAL" "$@"; }

MODEL_NAME="$(adb_dev shell getprop ro.product.model || true)"
say "Device: ${MODEL_NAME:-unknown model} (serial $SERIAL)"
say ""

# What was on the phone before, so a stale recording from an earlier run is never taken as new.
device_file_stamp() { adb_dev shell "stat -c %Y '$DEVICE_FILE' 2>/dev/null || echo none" | tail -n 1; }
BEFORE_STAMP="$(device_file_stamp || echo none)"

mkdir -p "$WORK_DIR"
rm -f "$PULLED_FILE"
START_MARKER="${WORK_DIR}/started"
touch "$START_MARKER"

# --- run only the recorder -------------------------------------------------------------------
say "== Building, installing and recording (this is the long part) =="
set +e
./gradlew :app-phone:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$RECORDER_CLASS" \
    -Pandroid.testInstrumentationRunnerArguments."$OPT_IN_ARG"=true \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
RUN_STATUS=$?
set -e
if [ "$RUN_STATUS" -ne 0 ]; then
    die "The recorder run failed (Gradle exit $RUN_STATUS). Nothing in the repository was changed.
See the Gradle output above and app-phone/build/reports/androidTests/connected/."
fi

# --- did it actually record, or did it skip? --------------------------------------------------
# AGP names the result file after the device (with spaces) and, as of AGP 9.4, reports an
# Assume-skip as a <failure> carrying AssumptionViolatedException rather than <skipped>, while the
# Gradle task still succeeds. So both forms are checked, file by file.
RESULTS_TEXT=""
while IFS= read -r xml; do
    [ -n "$xml" ] && RESULTS_TEXT="${RESULTS_TEXT}$(cat "$xml")"$'\n'
done <<EOF
$(find "$RESULTS_DIR" -name 'TEST-*.xml' -newer "$START_MARKER" 2>/dev/null || true)
EOF
if printf '%s' "$RESULTS_TEXT" | grep -q -e '<skipped' -e 'AssumptionViolatedException'; then
    STATE="$(printf '%s' "$RESULTS_TEXT" | grep -o 'readiness is [A-Z_]*' | head -n 1 | sed 's/readiness is //' || true)"
    if printf '%s' "$RESULTS_TEXT" | grep -q 'recorder is opt-in'; then
        die "The recorder skipped itself because it did not receive the opt-in argument.
This is a bug in this script; nothing in the repository was changed." 1
    fi
    die "NOT RECORDED: the phone's on-device model is not ready${STATE:+ (state: $STATE)}, so the
recorder skipped itself. Nothing was downloaded and nothing in the repository was changed.
Getting the model onto the phone is a separate, deliberate step." 2
fi

AFTER_STAMP="$(device_file_stamp || echo none)"
if [ "$AFTER_STAMP" = "none" ] || [ "$AFTER_STAMP" = "$BEFORE_STAMP" ]; then
    die "NOT RECORDED: the test run finished but no new recording appeared on the phone
(most likely the model was not ready and the recorder skipped). Nothing in the repository was changed." 2
fi

# Numbers only: the recorder never logs sentence text or model output.
SUMMARY="$(adb_dev logcat -d -s "$LOG_TAG":I 2>/dev/null | grep 'cases=' | tail -n 1 || true)"
[ -n "$SUMMARY" ] && say "Recorder summary: ${SUMMARY#*: }"

# --- pull and check before overwriting anything ------------------------------------------------
say ""
say "== Bringing the recording back =="
adb_dev pull "$DEVICE_FILE" "$PULLED_FILE" >/dev/null \
    || die "Could not copy the recording off the phone ($DEVICE_FILE). Nothing in the repository was changed."
[ -s "$PULLED_FILE" ] || die "The copied recording is empty. Nothing in the repository was changed."

validate_with_python() {
    "$1" - "$PULLED_FILE" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    doc = json.load(f)
entries = doc.get("entries")
if not isinstance(entries, list) or not entries:
    sys.exit("recording has no entries")
if doc.get("source") != "DEVICE":
    sys.exit("recording source is not DEVICE")
print(len(entries))
PY
}
validate_with_node() {
    node -e '
const doc = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
if (!Array.isArray(doc.entries) || doc.entries.length === 0) { console.error("recording has no entries"); process.exit(1); }
if (doc.source !== "DEVICE") { console.error("recording source is not DEVICE"); process.exit(1); }
console.log(doc.entries.length);' "$PULLED_FILE"
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
    # No JSON parser available: a cheap structural check. The gate below parses it fully.
    grep -q '"entries"' "$PULLED_FILE" && grep -q '"caseId"' "$PULLED_FILE" \
        && grep -q '"source": *"DEVICE"' "$PULLED_FILE" \
        || die "The recording does not look complete. Nothing in the repository was changed."
    ENTRY_COUNT="$(grep -c '"caseId"' "$PULLED_FILE" || true)"
    VALIDATED="grep (no python/node found; basic check only)"
fi
say "Recording checked with $VALIDATED: $ENTRY_COUNT entries."

cp "$PULLED_FILE" "$DEST_FILE"
say "Saved to $DEST_FILE"

# --- tag corpus: structural check, then score and gate --------------------------------------
if [ "$TAG_MODE" -eq 1 ]; then
    say ""
    say "== Checking the tag recording's structure =="
    set +e
    ./gradlew :core-testing:test --tests "$GATE_CLASS"
    CHECK_STATUS=$?
    set -e
    say ""
    if [ "$CHECK_STATUS" -ne 0 ]; then
        say "CHECK FAILED (Gradle exit $CHECK_STATUS): the tag recording does not match the tag corpus."
        say "The recording itself was kept at $DEST_FILE."
        say "Details: core-testing/build/reports/tests/test/index.html"
        exit 3
    fi
    say ""
    say "== Scoring the tag recording (tag regression gate) =="
    set +e
    ./gradlew :core-testing:test --tests "$TAG_GATE_CLASS"
    GATE_STATUS=$?
    set -e
    say ""
    if [ "$GATE_STATUS" -ne 0 ]; then
        say "TAG GATE FAILED (Gradle exit $GATE_STATUS): the new tag recording regressed against"
        say "tag-baseline.json, or could not be replayed. The recording itself was kept at $DEST_FILE."
        [ -f "$REPORT_FILE" ] && say "Report: $REPORT_FILE"
        say "Details: core-testing/build/reports/tests/test/index.html"
        exit 3
    fi
    say "Done. Tag recording from ${MODEL_NAME:-the phone} saved, checked and scored."
    say "(Gated only once recordings/tag-baseline.json exists; until then the report is informational.)"
    if [ -f "$REPORT_FILE" ]; then
        say "Report: $REPORT_FILE"
    else
        say "Note: expected report $REPORT_FILE was not found."
    fi
    exit 0
fi

# --- score it -------------------------------------------------------------------------------
say ""
say "== Scoring the recording (regression gate) =="
set +e
./gradlew :core-testing:test --tests "$GATE_CLASS"
GATE_STATUS=$?
set -e

say ""
if [ "$GATE_STATUS" -ne 0 ]; then
    say "GATE FAILED (Gradle exit $GATE_STATUS): the new recording regressed against the baseline,"
    say "or could not be replayed. The recording itself was kept at $DEST_FILE."
    [ -f "$REPORT_FILE" ] && say "Report: $REPORT_FILE"
    say "Details: core-testing/build/reports/tests/test/index.html"
    exit 3
fi
say "Done. Recording from ${MODEL_NAME:-the phone} scored; gate passed."
if [ -f "$REPORT_FILE" ]; then
    say "Report: $REPORT_FILE"
else
    say "Note: expected report $REPORT_FILE was not found."
fi
