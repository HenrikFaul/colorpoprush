#!/usr/bin/env bash
#
# Runtime execution proof for Color Pop Rush.
#
# Executes the REAL game code (the exact GameView/Board/Level/... sources that
# ship inside ColorPopRush.apk) on a plain JVM, against a hand-built Android
# shim (runtime-test/shim). It drives the game through the same entry points a
# device uses -- layout/attach/onTouchEvent/doFrame(Choreographer)/draw/onBack --
# across every screen and full playthroughs (locks/BREAK, power tiles, fusion,
# all six boosters, undo, swap, pause, settings, daily, stats, moves-sweep, a
# natural win, and forced fails), asserting ZERO runtime faults.
#
# Why a shim instead of an emulator: this environment's network policy blocks
# Google-hosted Maven/SDK endpoints, so a real emulator/Robolectric image can't
# be fetched. The shim reproduces Android's crash-class behaviour faithfully
# (RadialGradient throws on radius<=0; Canvas throws on NaN/Inf coords and on
# null Path/RectF/text) and records every such condition -- including exceptions
# GameView would normally swallow via its safeRecover() guards -- so a latent
# crash cannot hide.
#
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RT="$ROOT/runtime-test"
GAME="$ROOT/app/src/com/colorpop/rush"
OUT="$RT/.build"
unset JAVA_TOOL_OPTIONS 2>/dev/null || true

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gamesrc/com/colorpop/rush"

# Copy the shipping game sources, EXCEPT MainActivity (it needs Activity/Window
# stubs that are irrelevant to gameplay; MainActivity merely hosts GameView).
for f in "$GAME"/*.java; do
  base=$(basename "$f")
  [ "$base" = "MainActivity.java" ] && continue
  cp "$f" "$OUT/gamesrc/com/colorpop/rush/$base"
done

# Patch ONLY the copy of GameView so each safeRecover() call (all of which sit
# inside catch(Throwable t) blocks) also records the swallowed throwable. The
# shipping sources under app/src are never modified.
python3 - "$OUT/gamesrc/com/colorpop/rush/GameView.java" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
needle = "            safeRecover();"
repl = ("            android.graphics.Faults.record(\"EXC:\"+t+\" @\""
        "+(t.getStackTrace().length>0?t.getStackTrace()[0]:\"?\")); safeRecover();")
s = s.replace(needle, repl)
assert "Faults.record(\"EXC:\"" in s, "probe patch did not apply"
open(p, "w").write(s)
print("instrumented GameView copy: %d catch-block probe(s)" % s.count("Faults.record(\"EXC:\""))
PY

echo ">> compiling Android shim"
javac -d "$OUT/classes" $(find "$RT/shim/src" -name '*.java')

echo ">> compiling real game code against the shim"
javac -cp "$OUT/classes" -d "$OUT/classes" $(find "$OUT/gamesrc" -name '*.java')

echo ">> compiling execution harness"
javac -cp "$OUT/classes" -d "$OUT/classes" "$RT/TestRun.java"

echo ">> running"
java -cp "$OUT/classes" TestRun
