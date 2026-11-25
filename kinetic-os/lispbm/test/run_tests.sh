#!/usr/bin/env bash
# Builds (if needed) the real LispBM REPL and runs kinetic.lisp's tests in it.
#   LBM_SRC   LispBM checkout   (default /tmp/claude-0/lbm/src; cloned if missing)
#   LBM_REPL  prebuilt repl binary (skips build)
# Not a hardware test: VESC-only functions are faked by test/stubs.lisp.
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../kinetic.lisp"
LBM_SRC="${LBM_SRC:-/tmp/claude-0/lbm/src}"
REPL="${LBM_REPL:-$LBM_SRC/repl/repl}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

if [ ! -x "$REPL" ]; then
  if [ ! -d "$LBM_SRC/repl" ]; then
    GIT_LFS_SKIP_SMUDGE=1 git clone --depth 1 https://github.com/svenssonjoel/lispbm "$LBM_SRC" || exit 2
  fi
  # needs gcc, make, libreadline-dev, libpng-dev. FEATURES=64 = 64-bit build.
  (cd "$LBM_SRC/repl" && make -j"$(nproc)" FEATURES="64") >"$WORK/build.log" 2>&1 \
    || { tail -20 "$WORK/build.log"; echo "repl build failed"; exit 2; }
fi
echo "LispBM commit: $(git -C "$LBM_SRC" rev-parse --short HEAD 2>/dev/null)  repl: $REPL"

# kinetic.lisp minus the autostart tail (spawn/event-enable/forever loop).
sed '/^;;;@@ AUTOSTART-BEGIN/,$d' "$SCRIPT" > "$WORK/kinetic-lib.lisp"
rc=0

echo "== unit/logic tests =="
"$REPL" --terminate --silent -H 16384 -M 131072 -s "$HERE/stubs.lisp" -s "$WORK/kinetic-lib.lisp" -s "$HERE/run_tests.lisp" >"$WORK/unit.out" 2>&1
grep -E '^(FAIL|RESULT)|^ +Error' "$WORK/unit.out"
grep -q '^ALL-TESTS-PASSED' "$WORK/unit.out" || { rc=1; echo "unit tests did not report ALL-TESTS-PASSED; raw output tail:"; tail -30 "$WORK/unit.out"; }
echo "PASS lines: $(grep -c '^PASS' "$WORK/unit.out")"

echo "== smoke test: full unmodified kinetic.lisp (autostart, spawn, loopwhile) =="
timeout 8 stdbuf -o0 "$REPL" --terminate --silent -H 16384 -M 131072 -s "$HERE/stubs.lisp" -s "$HERE/smoke.lisp" -s "$SCRIPT" >"$WORK/smoke.out" 2>&1
grep -E '^SMOKE|^ +Error' "$WORK/smoke.out"
grep -q '^SMOKE-ALL-OK' "$WORK/smoke.out" || { rc=1; echo "smoke test FAILED; raw output tail:"; tail -30 "$WORK/smoke.out"; }

[ $rc -eq 0 ] && echo "ALL OK" || echo "FAILURES"
exit $rc
