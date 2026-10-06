#!/usr/bin/env bash
#
# Open the IME composition fixture in the real desktop editor, for a human to type into.
#
# Why this needs a person. `AP1Z9PXG` asks whether in-progress composition survives
# live decoration. It cannot be answered by a test, and the reason is an API wall
# rather than missing hardware:
#
#   TextFieldBuffer.setComposition   compiles to setComposition$foundation
#   TextFieldState.composition       compiles to getComposition-MzsxiRA$foundation
#   TextFieldBuffer                  has no public constructor at all
#
# All three are internal to compose-foundation. No code outside that module can put
# an active composition on a buffer, so a test cannot construct the state it would
# need to observe. The only source of a real composition is a real platform IME
# session, driven by a person with a CJK input source configured.
#
# This script does not verify anything. It opens the right document in the right
# process and then gets out of the way. What it removes is setup: the tester should
# be typing within a minute of running it, not hunting for a fixture file.
#
# Usage:
#   scripts/ime_verification.sh
#   scripts/ime_verification.sh path/to/other-fixture.md
#
# Then follow docs/ime-verification.md and record what happened.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fixture="${1:-$root/docs/fixtures/ime-composition.md}"

if [[ ! -f "$fixture" ]]; then
  echo "not a file: $fixture" >&2
  exit 1
fi

if [[ "$(uname -s)" != "Darwin" ]]; then
  cat >&2 <<'EOF'
This fixture is written for macOS, where Compose's desktop text input runs on the
platform IME. The verification is still valid elsewhere, but the failure modes are
different and the recorded expectations will not be.

On macOS, check that a CJK input source is enabled in
System Settings > Keyboard > Input Sources before continuing.
EOF
fi

echo "fixture:  $fixture"
echo "starting: the demo host, which loads the fixture via FUDE_DEMO_FILE"
echo
echo "Type Japanese or Chinese into the marked spots in that document."
echo "Record the outcome per docs/ime-verification.md — nine cases, pass or fail."
echo

# The demo reads FUDE_DEMO_FILE and refuses to start if it is not a file, which is
# the behaviour we want: a tester must never end up typing into the sample document
# and reporting a result about the wrong thing.
export FUDE_DEMO_FILE="$fixture"

cd "$root"
exec ./gradlew :fude-demo:run
