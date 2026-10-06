#!/usr/bin/env bash
#
# Measure one keystroke, in a real window, across document sizes and configurations.
#
# Why a shell loop rather than a sweep inside the app: the app measures one
# configuration per process and exits. An in-app sweep could not sequence its
# variants — mutating the variant from inside the measuring effect changed a key that
# effect depended on, and it restarted. Driving it from here means a hung run costs
# one measurement instead of the whole sweep, and each measurement starts from a clean
# JIT.
#
# Three configurations per size, because one number cannot say which phase is
# responsible:
#
#   editor  the real MarkdownEditor — the number a user experiences
#   plain   a bare BasicTextField, same text, no decoration
#   static  static text, no field — window and frame overhead
#
# editor - static is the editor's real cost with harness overhead removed.
# editor - plain  is what decoration costs.
#
# Usage:
#   scripts/keystroke_sweep.sh                 # the standard six sizes
#   scripts/keystroke_sweep.sh 250 500 1000    # your own sizes
#
# Results go to stdout, one SWEEP line per measurement. Assembling them into a curve,
# and subtracting the baselines, is a judgement call and belongs to a person.

set -uo pipefail

cd "$(dirname "$0")/.."

SIZES=("$@")
if [ ${#SIZES[@]} -eq 0 ]; then
  SIZES=(250 500 1000 2000 4000 5000)
fi

# A single measurement must not be able to wedge the machine.
PER_RUN_TIMEOUT=${PER_RUN_TIMEOUT:-600}

# `timeout` is GNU coreutils. macOS ships no such program, so the bare name this script
# used to call fails there with "command not found" -- on the machine that wrote it. It
# works on a Mac with Homebrew coreutils installed, which is why the breakage is easy to
# miss: `gtimeout` exists there, and often `timeout` too.
#
# So: try the name, then the coreutils-prefixed name, then do it in the shell. The
# fallback is not an approximation of `timeout` -- it backgrounds the command, waits for
# it with `sleep`, and kills the whole process group on expiry, which is what the real
# one does. A hung JVM holds no lock the sweep needs, so a plain kill suffices.
run_with_timeout() {
    local seconds=$1
    shift

    if command -v timeout >/dev/null 2>&1; then
        timeout "$seconds" "$@"
        return $?
    fi
    if command -v gtimeout >/dev/null 2>&1; then
        gtimeout "$seconds" "$@"
        return $?
    fi

    # Portable path. `setsid` is Linux-only, so the process group is addressed by the
    # job's own PID and `kill -- -PID` is attempted but not depended on.
    "$@" &
    local pid=$!
    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$seconds" ]; then
            kill -TERM "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
            sleep 1
            kill -KILL "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null
            wait "$pid" 2>/dev/null
            return 124   # the exit status GNU timeout uses for a timeout
        fi
        sleep 1
        waited=$((waited + 1))
    done
    wait "$pid"
}

echo "# keystroke sweep — real window, ${PER_RUN_TIMEOUT}s cap per measurement"
echo "# date: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"

for lines in "${SIZES[@]}"; do
  for variant in editor plain static; do
    output=$(
      FUDE_SWEEP_LINES="$lines" FUDE_SWEEP_VARIANT="$variant" \
        run_with_timeout "$PER_RUN_TIMEOUT" ./gradlew --quiet --console=plain :fude-demo:run 2>&1
    )
    line=$(printf '%s\n' "$output" | grep -E '^SWEEP variant=' | tail -1)

    if [ -n "$line" ]; then
      echo "$line"
    else
      # Say so rather than printing nothing: a missing row in a cost curve reads as
      # "no cost", which is how a gap becomes a wrong decision. Distinguish a timeout
      # from any other failure, because "it took too long" and "it did not run" lead to
      # opposite conclusions.
      status=$?
      if [ "$status" -eq 124 ]; then
        echo "SWEEP variant=$variant lines=$lines TIMED OUT after ${PER_RUN_TIMEOUT}s"
        continue
      fi
      echo "SWEEP variant=$variant lines=$lines FAILED (exit $status) no measurement produced"
      printf '%s\n' "$output" | tail -5 | sed 's/^/    /'
    fi
  done
done

echo "# done — $(date -u '+%Y-%m-%dT%H:%M:%SZ')"