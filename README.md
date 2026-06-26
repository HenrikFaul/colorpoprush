# 🎨 Color Pop Rush

**Pop Colors. Clear Stress.** — a bright, one-tap, offline bubble-popping puzzle
game for Android, built as a fully native app from the design sheet in
`Color Pop Rush.png`.

Tap groups of 2+ connected same-colour bubbles to pop them. Bubbles fall and new
ones drop in, chain your taps into **combos** for huge scores, use **boosters**
when you get stuck, and clear the level goal before you run out of moves.

<p align="center"><img src="app/icon-512.png" width="160" alt="Color Pop Rush icon"></p>

## Features

- **One-tap gameplay** — tap connected same-colour bubbles to pop them.
- **Combos & chains** — keep popping to build a combo multiplier (up to ×10).
- **Power bubbles & fusion** — big matches forge specials: 5+ → 🚀 Rocket
  (row/column), 7+ → 💣 Bomb (5×5), 9+ → 🌈 Rainbow (a colour). Tap to detonate;
  power tiles chain into cascades, and tapping one next to another **fuses**
  them into a bigger blast.
- **Obstacles** — 🔒 locked/chained bubbles you free by popping next to them,
  with dedicated **BREAK** levels.
- **Varied objectives** — clear N bubbles, collect N of a colour, reach a score,
  or break all locks. Difficulty, colours and obstacles ramp across **worlds**
  (each with its own theme), and stars are earned by **score**.
- **Boosters** — 💣 Bomb, 🌈 Rainbow, 🔨 Hammer, ➕ +5 Moves, 🔀 Shuffle and
  🔃 Colour-swap, plus a one-step **Undo** and a +5 **continue** on fail.
- **Juice & smarts** — touch-and-hold previews the group (and the power it would
  forge); an idle **hint**; an end-of-level **moves-sweep** bonus; "almost there"
  and low-moves warnings.
- **Meta** — coins, a shop, daily reward, **star-milestone chests**, per-level
  best scores, and a stats screen.
- **Pause & Settings** — sound, haptics and a **colourblind symbol** mode, plus
  one-time tip cards and a first-level coach.
- **Endless procedural levels** — difficulty (moves, target, colours) scales
  smoothly across thousands of levels.
- **Level map** with per-level star ratings (1–3 stars based on efficiency).
- **Daily reward** — a 7-day login streak calendar with escalating coin rewards.
- **Coins, persistence & settings** — progress, stars, coins, boosters and the
  sound toggle are all saved between sessions.
- **Procedural sound** — pop/combo/win effects are synthesised at runtime
  (no audio assets shipped), and can be toggled off.
- **100% offline**, no permissions, no ads, no network.

## Project layout

```
app/
  AndroidManifest.xml          # minSdk 21, targetSdk 23, no permissions
  src/com/colorpop/rush/
    Board.java                 # pure-Java grid model (flood-fill, gravity, refill)
    Level.java                 # procedural level params + scoring (pure Java)
    GameView.java              # all rendering, input, animation & game flow
    MainActivity.java          # fullscreen host activity
    Palette.java  Effects.java Storage.java SoundManager.java
  res/mipmap-*/ic_launcher.png # generated launcher icons
tools/IconGen.java             # AWT launcher-icon generator
build.sh                       # offline APK build pipeline
```

`Board` and `Level` carry no Android dependencies and are unit-tested on the
plain JVM.

## Verifying it actually runs

Beyond unit-testing the logic, [`runtime-test/`](runtime-test/) executes the
**real** game code (the exact `GameView`/`Board`/`Level`/… that ship in the
APK) on a plain JVM against a hand-built Android shim, driving it through the
same entry points a device uses (`layout`/`attach`/`onTouchEvent`/
`Choreographer.doFrame`/`draw`/`onBack`) across **every screen and full
playthroughs** — boosters, power tiles, fusion, locked-bubble/BREAK levels,
undo, swap, pause, the moves-sweep, a natural win and forced fails. The shim
reproduces Android's crash-class behaviour (illegal shader radius, NaN draw
coords, null draw args) and records anything GameView would otherwise swallow,
so the run asserts **zero runtime faults**:

```bash
./runtime-test/run.sh
# -> RESULT: PASS — game ran cleanly across every screen and full playthroughs, zero faults.
```

## Building

The build uses only the Debian/Ubuntu Android packages — **no Android Gradle
Plugin and no Google-hosted downloads** — so it works in restricted/offline
environments:

```bash
sudo apt-get install -y android-sdk-build-tools android-sdk-platform-23 \
    aapt apksigner zipalign dalvik-exchange default-jdk
./build.sh
```

This produces a signed, installable **`ColorPopRush.apk`** in the repo root.

The pipeline: `aapt2 compile/link` → `javac` (Java 8 bytecode) →
`dalvik-exchange` (dex) → `zipalign` → `apksigner`.

## Installing

```bash
adb install -r ColorPopRush.apk
```

Or copy the APK to an Android device and open it (enable "install from unknown
sources"). Runs on Android 5.0 (API 21) and newer.

## How to play

1. Tap any cluster of **2 or more** touching bubbles of the same colour to pop them.
2. Bigger clusters score far more; keep popping quickly to grow your **combo**.
3. **Clear the required number of bubbles** (the goal shown up top) before your
   **moves** run out. Every pop counts toward the goal, so each level is always
   beatable — bigger groups and combos just finish it faster (and earn more stars).
4. Stuck? Tap a **booster** in the bottom bar, then tap the board to use it.
