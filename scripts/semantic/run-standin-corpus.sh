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
# Model override:  MODEL=gemma3n:e2b scripts/semantic/run-standin-corpus.sh
# Works in Git Bash on Windows and on macOS/Linux. Needs curl.
#
# Exit codes: 0 recorded and reported; 1 setup/run problem.
set -euo pipefail

MODEL="${MODEL:-gemma3n:e4b}"
BASE_URL="http://127.0.0.1:11434"
RECORDER_TEST="*StandInCorpusRecorderTest"
GATE_CLASS="com.mcfrenchpants.activityledger.core.testing.corpus.SemanticRegressionGateTest"
DEST_FILE="core-testing/src/test/resources/semantic-corpus/recordings/standin-latest.json"
REPORT_FILE="core-testing/build/reports/semantic-corpus/standin.md"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit "${2:-1}"; }

say "== Semantic corpus: STAND-IN recording (NOT official) =="
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
