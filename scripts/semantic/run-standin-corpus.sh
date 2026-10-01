#!/usr/bin/env bash
# Records the semantic regression corpus against a STAND-IN model on THIS computer, then scores it.
#
# The stand-in is an open-source model (default gemma3n:e4b) served locally by Ollama. It is for
# fast prompt iteration without the phone. ITS RESULTS ARE NOT OFFICIAL: only a recording from the
# real on-device model (scripts/semantic/run-device-corpus.sh) is the official measurement.
#
#   1. checks Ollama answers on http://127.0.0.1:11434 (loopback only; nothing leaves this machine),
#   2. checks the model is already pulled -- this script NEVER installs or pulls anything,
#   3. runs ONLY core-ai's StandInCorpusRecorderTest with -PsemanticStandIn=true, which overwrites
#      core-testing/src/test/resources/semantic-corpus/recordings/standin-latest.json,
#   4. runs the JVM regression gate (SemanticRegressionGateTest) and says where standin.md is.
#
# With --tags as the first argument it records the TAG corpus (subject + action, 72 sentences)
# through the extraction prompt instead: it runs ONLY *StandInTagCorpusRecorderTest with
# -PtagStandIn=true, which overwrites recordings/tag-standin-latest.json, then runs the structural
# check TagRecordingFilesTest (the tag corpus has no gate or report yet). Without --tags nothing
# changes.
#
# Model override:  MODEL=gemma3n:e2b scripts/semantic/run-standin-corpus.sh [--tags]
# Works in Git Bash on Windows and on macOS/Linux. Needs curl.
#
# Exit codes: 0 recorded and reported; 1 setup/run problem.
set -euo pipefail

# Optional first argument: --tags records the tag corpus instead (see the header).
TAG_MODE=0
if [ "${1:-}" = "--tags" ]; then
    TAG_MODE=1
    shift
fi

MODEL="${MODEL:-gemma3n:e4b}"
BASE_URL="http://127.0.0.1:11434"
RECORDER_TEST="*StandInCorpusRecorderTest"
GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest"
DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/standin-latest.json"
REPORT_FILE="core-testing/build/reports/semantic-corpus/standin.md"
if [ "$TAG_MODE" -eq 1 ]; then
    RECORDER_TEST="*StandInTagCorpusRecorderTest"
    # No tag gate or report exists yet: only the structural check of the recording files runs.
    GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.TagRecordingFilesTest"
    DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/tag-standin-latest.json"
    REPORT_FILE=""
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit "${2:-1}"; }

if [ "$TAG_MODE" -eq 1 ]; then
say "== Tag corpus (subject + action): STAND-IN recording (NOT official) =="
say "This will run every tag corpus sentence (72) through the local model, one request each,"
say "then check the recording's structure (there is no score or gate for the tag corpus yet)."
else
say "== Semantic corpus: STAND-IN recording (NOT official) =="
fi
say "Model: ${MODEL} via Ollama at ${BASE_URL} (this computer only)."
say ""

command -v curl >/dev/null 2>&1 || die "curl not found; it is needed to check the local Ollama server."

# --- Ollama answering? -------------------------------------------------------------------
TAGS="$(curl -fsS --noproxy "*" --max-time 5 "${BASE_URL}/api/tags" 2>/dev/null)" \
    || die "No Ollama server answered at ${BASE_URL}.
Install Ollama from https://ollama.com if it is not installed, then start it
(open the Ollama app, or run 'ollama serve') and run this script again."

# --- Model pulled? (never pulls) ---------------------------------------------------------
case "$MODEL" in
    *:*) WANTED="$MODEL" ;;
    *)   WANTED="${MODEL}:latest" ;;
esac
COMPACT="$(printf '%s' "$TAGS" | tr -d ' \n\r\t')"
if ! printf '%s' "$COMPACT" | grep -qF "\"name\":\"${WANTED}\""; then
    die "Model '${MODEL}' is not pulled on the local Ollama server.
Pull it yourself first (this script never downloads anything):
    ollama pull ${MODEL}"
fi

# --- Record (tag corpus) -------------------------------------------------------------------
if [ "$TAG_MODE" -eq 1 ]; then
    say "Running the stand-in tag recorder (one request per tag corpus sentence)..."
    ./gradlew :core-ai:testDebugUnitTest --tests "$RECORDER_TEST" \
        -PtagStandIn=true "-PsemanticStandIn.model=${MODEL}" \
        || die "The stand-in tag recorder failed; see the Gradle output above. ${DEST_FILE} was not replaced unless the recorder said so."

    [ -f "$DEST_FILE" ] || die "The recorder finished but ${DEST_FILE} does not exist."

    say ""
    say "Checking the stand-in tag recording (structure only)..."
    ./gradlew :core-testing:test --tests "$GATE_CLASS" \
        || die "The stand-in tag recording does not match the tag corpus; see core-testing/build/reports/tests/test/index.html."

    say ""
    say "Stand-in tag recording: ${DEST_FILE}"
    say "Scoring the tag corpus is not built yet, so no report was produced."
    say "Reminder: these are STAND-IN results from ${MODEL}, not Gemini Nano on the phone. They are"
    say "useful for comparing prompt changes, but they are NOT the official measurement."
    exit 0
fi

# --- Record ------------------------------------------------------------------------------
say "Running the stand-in recorder (one request per corpus sentence)..."
./gradlew :core-ai:testDebugUnitTest --tests "$RECORDER_TEST" \
    -PsemanticStandIn=true "-PsemanticStandIn.model=${MODEL}" \
    || die "The stand-in recorder failed; see the Gradle output above. ${DEST_FILE} was not replaced unless the recorder said so."

[ -f "$DEST_FILE" ] || die "The recorder finished but ${DEST_FILE} does not exist."

# --- Score -------------------------------------------------------------------------------
say ""
say "Scoring the stand-in recording..."
./gradlew :core-testing:test --tests "$GATE_CLASS" \
    || die "The regression replay could not read the stand-in recording; see ${REPORT_FILE}."

say ""
say "Stand-in recording: ${DEST_FILE}"
say "Stand-in report:    ${REPORT_FILE}"
say "Reminder: these are STAND-IN results from ${MODEL}, not Gemini Nano on the phone. They are"
say "useful for comparing prompt changes, but they are NOT the official measurement."
