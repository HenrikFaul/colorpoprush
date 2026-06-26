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
- **Power bubbles** — big matches forge specials that chain into cascades:
  5+ → 🚀 Rocket (clears a row/column), 7+ → 💣 Bomb (5×5), 9+ → 🌈 Rainbow
  (clears a colour). Tap to detonate; power tiles trigger each other.
- **Varied objectives** — clear N bubbles, collect N of a colour, or reach a score.
- **Boosters** — 💣 Bomb, 🌈 Rainbow, 🔨 Hammer, ➕ +5 Moves and 🔀 Shuffle.
  Buy more with coins before a level, or **continue with +5 Moves** when you run out.
- **Smarter play** — touch-and-hold previews the group (and the power it would
  forge); an idle **hint** pulses a good move; stars are earned by **score**.
- **Pause menu & Settings** — sound, haptics and a **colourblind symbol** mode;
  plus a first-level coach.
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
