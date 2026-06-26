# Runtime execution proof

This directory proves **ColorPopRush.apk actually runs** — not just that it
compiles and signs.

`./runtime-test/run.sh` executes the **real** game code (the exact
`GameView`, `Board`, `Level`, `World`, `Storage`, `SoundManager`, `Palette`,
`Effects` sources that ship inside the APK) on a plain JVM, against a small
hand-built Android shim in [`shim/`](shim/src/android). It drives the game
through the same entry points a device uses:

- `View.layout()` → `onSizeChanged()` → board layout
- `attach()` → `onAttachedToWindow()` → `resume()`
- `Choreographer` frame callback → `doFrame()` → `update()`
- `View.draw()` → `onDraw()` (the full custom-canvas renderer)
- `onTouchEvent(MotionEvent …)` for every tap/drag
- `onBack()`, `detach()`, `destroy()`

…across **every screen and full playthroughs**: menu, daily reward, stats +
chest claim, settings (sound/haptics/colourblind toggles), level map (with
scroll), pregame (booster purchase), a normal pop, **undo**, all six boosters
(**bomb, rainbow, hammer, +5 moves, shuffle, swap**), **power-tile
detonation**, **power fusion** (bomb+bomb), **locked-bubble / BREAK** levels
(including freeing the last lock with the hammer), pause↔settings round-trips,
pausing mid-animation and resuming, a natural **win → moves-sweep → complete**,
and three forced **fail → continue / retry / home** paths.

## How it catches crashes

The shim reproduces Android's real crash-class behaviour:

- `RadialGradient` throws `IllegalArgumentException` when `radius <= 0`
- `LinearGradient` throws on `NaN` coordinates
- `Canvas` throws on `NaN`/`Inf` draw coordinates and on `null`
  `Path`/`RectF`/text

GameView wraps `onDraw`/`onTouchEvent`/`doFrame` in `try/catch → safeRecover()`,
so on a device those would merely glitch back to the menu rather than crash.
To make sure nothing hides, the runner compiles an **instrumented copy** of
`GameView` (the shipping source under `app/src` is never touched) whose
`safeRecover()` call-sites also record the swallowed throwable, and the shim
records every crash-class condition into `Faults.LOG`. The harness then asserts
`Faults` is empty.

## Running

```bash
./runtime-test/run.sh
```

Expected tail:

```
Total frames driven: 1599, canvas draws: 1162348, faults: 0
RESULT: PASS — game ran cleanly across every screen and full playthroughs, zero faults.
```

Only a JDK is required (no Android SDK, no emulator, no network). The shim is a
test harness only — it is **not** part of the APK; the shipped app links against
the real `android.jar` via [`../build.sh`](../build.sh).
