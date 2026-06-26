package com.colorpop.rush;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The entire Color Pop Rush front-end: a single custom view that renders every
 * screen on a Canvas, runs a Choreographer-driven animation loop, handles touch
 * input and drives the game-state machine. Game rules live in {@link Board} and
 * {@link Level}; this class owns presentation, animation and flow.
 *
 * Robustness: onDraw, onTouchEvent and the frame loop are each wrapped so that
 * an unexpected runtime exception can never crash the app, and all shader/canvas
 * geometry is guarded against degenerate (zero/negative) sizes.
 */
public class GameView extends View implements Choreographer.FrameCallback {

    private enum State { MENU, MAP, PREGAME, PLAYING, ANIM, COMPLETE, FAILED, DAILY, STATS, PAUSED, SETTINGS }

    // Booster ids (match Storage booster ids).
    private static final int B_NONE = -1, B_BOMB = 0, B_RAINBOW = 1, B_HAMMER = 2, B_MOVES = 3,
            B_SHUFFLE = 4, B_SWAP = 5;
    private static final int BOOSTER_COUNT = 6;
    private static final String[] B_NAMES = {"BOMB", "COLOR", "HAMMER", "+5", "MIX", "SWAP"};
    private static final int[] B_PRICE = {120, 150, 100, 80, 90, 110};

    private static final float COMBO_WINDOW = 2.4f;
    private static final float POP_DUR = 0.13f;
    private static final float FALL_DUR = 0.30f;
    private static final int[] DAILY_REWARDS = {50, 100, 150, 200, 250, 300, 500};

    private final Storage store;
    private final SoundManager sound;
    private final Random rng = new Random();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint bgPaint;

    private State state = State.MENU;
    private float density = 3f;
    private int W, H;

    // Board geometry
    private float boardX, boardY, cell, boardW, boardH, BR;
    private RadialGradient[] bubbleShader;

    // Decorative background bubbles: {x,y,r,vy,colorIdx,alpha}
    private final List<float[]> bgBubbles = new ArrayList<float[]>();

    // Active game
    private Level level;
    private Board board;
    private int selectedLevel = 1;
    private int score, movesLeft, collected, target;
    private float displayScore;
    private int combo;
    private float comboTimer, comboFlash;
    private int armed = B_NONE;

    // Animation
    private int animPhase;     // 0 = pop shrink, 1 = fall/refill
    private float animT;
    private final List<int[]> popping = new ArrayList<int[]>();
    private final List<Integer> poppingColors = new ArrayList<Integer>();
    private int bufR = -1, bufC = -1; // tap buffered during animation

    // Tap-to-preview + idle hint
    private List<int[]> previewCells;
    private List<int[]> hintCells;
    private float idleTimer, hintPulse;
    private int[] pendingSpawn; // a freshly-created power tile, for a "forming" pulse
    private float spawnT;

    // Effects
    private final List<Effects.Particle> particles = new ArrayList<Effects.Particle>();
    private final List<Effects.FloatingText> floats = new ArrayList<Effects.FloatingText>();

    // Result screen
    private int resultStars, resultCoins, resultBooster = -1;
    private float resultT;

    // Map scrolling
    private float mapScroll, mapScrollMax, mapVel;

    // Stats screen: reset needs a confirmation tap
    private boolean resetArmed;
    private float resetArmedT;

    // Settings / accessibility (cached so onDraw avoids prefs reads per bubble)
    private boolean symbolsCache, hapticsCache;
    private State settingsReturn = State.MENU;
    private boolean firstPopDone; // level-1 coach prompt

    // World theme (cosmetic, per 20-level band)
    private int worldBgTop = Palette.BG_TOP, worldBgBottom = Palette.BG_BOTTOM, worldAccent = Palette.GOLD;

    // Undo (one-step) + Colour-swap
    private Board.Snapshot undoSnap, pendingUndo;
    private int undoMoves, undoScore, undoCollected;
    private int pendMoves, pendScore, pendCollected;
    private int[] swapFirst; // first cell picked for a swap

    // Touch tracking
    private float downX, downY, lastY;
    private boolean movedWhileDown;
    private float transFade = 1f;

    // Loop
    private long lastNanos;
    private boolean running;

    public GameView(Context ctx) {
        super(ctx);
        store = new Storage(ctx);
        sound = new SoundManager();
        sound.setEnabled(store.soundOn());
        density = getResources().getDisplayMetrics().density;
        selectedLevel = store.unlockedLevel();
        setFocusable(true);
        setClickable(true);
    }

    private float dp(float v) {
        return v * density;
    }

    // ===================================================================
    //  Lifecycle / loop
    // ===================================================================

    public void resume() {
        sound.setEnabled(store.soundOn());
        symbolsCache = store.symbolsOn();
        hapticsCache = store.hapticsOn();
        if (!running) {
            running = true;
            lastNanos = 0;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    public void pause() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    public void destroy() {
        pause();
        sound.release();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        resume();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        pause();
    }

    @Override
    public void doFrame(long now) {
        if (!running) {
            return;
        }
        float dt = (lastNanos == 0) ? 0.016f : (now - lastNanos) / 1_000_000_000f;
        lastNanos = now;
        if (dt > 0.05f) {
            dt = 0.05f;
        }
        try {
            update(dt);
        } catch (Throwable t) {
            // Never let a stray exception kill the loop; recover to a safe screen.
            safeRecover();
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void safeRecover() {
        try {
            state = State.MENU;
            armed = B_NONE;
            popping.clear();
            poppingColors.clear();
            particles.clear();
            floats.clear();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) {
            return; // ignore degenerate layout passes (prevents negative shader radii)
        }
        W = w;
        H = h;
        applyWorldTheme(selectedLevel);
        layoutBoard();
        initBgBubbles();
    }

    private void applyWorldTheme(int lvl) {
        World wd = World.forLevel(Math.max(1, lvl));
        worldBgTop = wd.bgTop;
        worldBgBottom = wd.bgBottom;
        worldAccent = wd.accent;
        if (W > 0 && H > 0) {
            bgPaint = new Paint();
            bgPaint.setShader(new LinearGradient(0, 0, 0, H, worldBgTop, worldBgBottom, Shader.TileMode.CLAMP));
        }
    }

    private void layoutBoard() {
        if (W <= 0 || H <= 0) {
            return;
        }
        if (level == null) {
            level = new Level(selectedLevel);
        }
        int cols = level.cols, rows = level.rows;
        float availTop = H * 0.235f;
        float availBottom = H * 0.14f;
        float availH = H - availTop - availBottom - dp(12);
        // Floor everything strictly positive so every radius stays > 0.
        cell = Math.max(dp(8), Math.min((W * 0.95f) / cols, availH / rows));
        boardW = cell * cols;
        boardH = cell * rows;
        boardX = (W - boardW) / 2f;
        boardY = availTop + Math.max(0f, (availH - boardH) / 2f);
        BR = Math.max(1f, cell * 0.44f); // slightly smaller -> visible gap between bubbles
        bubbleShader = new RadialGradient[Palette.BUBBLE.length];
        for (int i = 0; i < bubbleShader.length; i++) {
            int base = Palette.BUBBLE[i];
            bubbleShader[i] = new RadialGradient(-BR * 0.34f, -BR * 0.36f, BR * 1.35f,
                    new int[]{Palette.lighten(base, 0.7f), base, Palette.scale(base, 0.78f)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    private void initBgBubbles() {
        bgBubbles.clear();
        for (int i = 0; i < 16; i++) {
            float r = dp(8) + rng.nextFloat() * dp(26);
            bgBubbles.add(new float[]{
                    rng.nextFloat() * W, rng.nextFloat() * H, r,
                    -(dp(6) + rng.nextFloat() * dp(16)),
                    rng.nextInt(Palette.BUBBLE.length),
                    0.05f + rng.nextFloat() * 0.10f
            });
        }
    }

    // ===================================================================
    //  Update
    // ===================================================================

    private void update(float dt) {
        if (transFade < 1f) {
            transFade = Math.min(1f, transFade + dt * 3.5f);
        }
        if (comboFlash > 0f) {
            comboFlash = Math.max(0f, comboFlash - dt * 2.6f);
        }
        if (resetArmed) {
            resetArmedT -= dt;
            if (resetArmedT <= 0) {
                resetArmed = false;
            }
        }
        if (state == State.MAP && Math.abs(mapVel) > 0.4f) {
            mapScroll = clamp(mapScroll + mapVel, 0, mapScrollMax);
            mapVel *= 0.92f;
            if (mapScroll <= 0f || mapScroll >= mapScrollMax) {
                mapVel = 0f;
            }
        }
        if (pendingSpawn != null) {
            spawnT += dt;
            if (spawnT > 0.5f) {
                pendingSpawn = null;
            }
        }
        boolean canHint = state == State.PLAYING && armed == B_NONE && previewCells == null
                && movesLeft > 0 && board != null;
        if (canHint) {
            idleTimer += dt;
            if (idleTimer >= 4f && hintCells == null) {
                hintCells = board.bestHintGroup(level.accentColor);
            }
        } else {
            idleTimer = 0f;
            hintCells = null;
        }
        hintPulse += dt;
        for (float[] b : bgBubbles) {
            b[1] += b[3] * dt;
            if (b[1] + b[2] < 0) {
                b[1] = H + b[2];
                b[0] = rng.nextFloat() * W;
            }
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            if (!particles.get(i).update(dt, dp(900))) {
                particles.remove(i);
            }
        }
        for (int i = floats.size() - 1; i >= 0; i--) {
            if (!floats.get(i).update(dt)) {
                floats.remove(i);
            }
        }
        if (Math.abs(displayScore - score) > 0.5f) {
            displayScore += (score - displayScore) * Math.min(1f, dt * 9f);
        } else {
            displayScore = score;
        }
        if (combo > 0) {
            comboTimer -= dt;
            if (comboTimer <= 0) {
                combo = 0;
            }
        }
        if (state == State.COMPLETE || state == State.FAILED) {
            resultT += dt;
        }
        if (state == State.ANIM) {
            advanceAnim(dt);
        }
    }

    private void advanceAnim(float dt) {
        animT += dt;
        if (animPhase == 0 && animT >= POP_DUR) {
            for (int[] cellPos : popping) {
                board.clearCell(cellPos[0], cellPos[1]);
            }
            List<int[]> freed = board.damageLocksAround(popping);
            for (int[] f : freed) {
                spawnParticle(colToX(f[1]), rowToY(f[0]), board.colorAt(f[0], f[1]), dp(280), true);
            }
            board.collapse();
            sound.playSwoosh();
            animPhase = 1;
            animT = 0;
        } else if (animPhase == 1 && animT >= FALL_DUR) {
            popping.clear();
            poppingColors.clear();
            state = State.PLAYING;
            checkEndConditions();
            // Apply a tap that was buffered during the animation (keeps combos flowing).
            if (state == State.PLAYING && bufR >= 0 && board != null
                    && (board.typeAt(bufR, bufC) != Board.T_NORMAL || board.isPoppable(bufR, bufC))) {
                int r = bufR, c = bufC;
                bufR = bufC = -1;
                if (board.typeAt(r, c) != Board.T_NORMAL) {
                    triggerPower(r, c);
                } else {
                    tryPop(r, c);
                }
            } else {
                bufR = bufC = -1;
            }
        }
    }

    private void checkEndConditions() {
        boolean done;
        if (level.goalType == Level.BREAK) {
            done = board.lockedRemaining() == 0;
        } else if (level.goalType == Level.REACH_SCORE) {
            done = score >= target;
        } else {
            done = collected >= target;
        }
        if (done) {
            win();
        } else if (movesLeft <= 0) {
            lose();
        }
    }

    /** Live goal progress for HUD/result readouts. */
    private int goalProgress() {
        if (level.goalType == Level.BREAK) {
            return Math.max(0, target - board.lockLevelsRemaining());
        }
        if (level.goalType == Level.REACH_SCORE) {
            return score;
        }
        return collected;
    }

    // ===================================================================
    //  Game actions
    // ===================================================================

    private void startPregame(int lvl) {
        selectedLevel = Math.max(1, lvl);
        level = new Level(selectedLevel);
        applyWorldTheme(selectedLevel);
        layoutBoard();
        clearFx();
        state = State.PREGAME;
        transFade = 0f;
        sound.playClick();
    }

    private void startGame() {
        level = new Level(selectedLevel);
        applyWorldTheme(selectedLevel);
        layoutBoard();
        board = new Board(level.cols, level.rows, level.numColors, level.seed());
        if (level.lockCount > 0) {
            board.seedLocks(level.lockCount, level.lockMax, new java.util.Random(level.seed() ^ 0x5DEECE66DL));
        }
        score = 0;
        displayScore = 0;
        movesLeft = level.moves;
        target = level.target;
        collected = 0;
        combo = 0;
        armed = B_NONE;
        bufR = bufC = -1;
        pendingSpawn = null;
        undoSnap = null;
        pendingUndo = null;
        swapFirst = null;
        firstPopDone = false;
        symbolsCache = store.symbolsOn();
        hapticsCache = store.hapticsOn();
        clearHintPreview();
        clearFx();
        state = State.PLAYING;
        transFade = 0f;
        sound.playSwoosh();
    }

    private void win() {
        resultStars = level.starsForScore(score, true);
        resultCoins = Level.coinReward(score, resultStars);
        store.recordResult(selectedLevel, resultStars);
        store.addCoins(resultCoins);
        store.submitScore(score);
        resultBooster = -1;
        if (selectedLevel % 5 == 0) {
            resultBooster = rng.nextInt(4);
            store.addBooster(resultBooster, 1);
        }
        resultT = 0;
        state = State.COMPLETE;
        sound.playWin();
        for (int i = 0; i < 60; i++) {
            spawnParticle(boardX + rng.nextFloat() * boardW, boardY + boardH * 0.35f,
                    rng.nextInt(Palette.BUBBLE.length), dp(460), true);
        }
    }

    private void lose() {
        resultT = 0;
        state = State.FAILED;
        sound.playFail();
    }

    private void tryPop(int r, int c) {
        List<int[]> g = board.group(r, c);
        if (g.size() < 2) {
            sound.playClick();
            return;
        }
        int n = g.size();
        // Big matches forge a power tile at the tapped cell (it survives the pop).
        int powerType = n >= 9 ? Board.T_RAINBOW
                : n >= 7 ? Board.T_BOMB
                : n >= 5 ? (rng.nextBoolean() ? Board.T_ROCKET_H : Board.T_ROCKET_V)
                : Board.T_NORMAL;
        if (powerType != Board.T_NORMAL) {
            board.setType(r, c, powerType);
            pendingSpawn = new int[]{r, c};
            spawnT = 0f;
            List<int[]> clearList = new ArrayList<int[]>();
            for (int[] cell : g) {
                if (!(cell[0] == r && cell[1] == c)) {
                    clearList.add(cell);
                }
            }
            floatText(powerName(powerType) + "!", colToX(c), rowToY(r) - BR, dp(17), Palette.COMBO, true);
            startClear(clearList, true, false);
        } else {
            startClear(g, true, false);
        }
    }

    /** Tapping a power tile detonates it; if it touches another power tile they FUSE. */
    private void triggerPower(int r, int c) {
        final int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
        for (int i = 0; i < 4; i++) {
            int nr = r + dr[i], nc = c + dc[i];
            if (board.typeAt(nr, nc) != Board.T_NORMAL && board.colorAt(nr, nc) != Board.EMPTY
                    && !board.isLocked(nr, nc)) {
                List<int[]> fused = board.cellsForFusion(r, c, nr, nc);
                addIfPlayable(fused, r, c);
                addIfPlayable(fused, nr, nc);
                floatText("FUSION!", colToX(c), rowToY(r) - BR, dp(20), Palette.COMBO_HOT, true);
                comboFlash = 1f;
                startClear(fused, true, false);
                return;
            }
        }
        List<int[]> seed = new ArrayList<int[]>();
        seed.add(new int[]{r, c});
        startClear(seed, true, false);
    }

    private void addIfPlayable(List<int[]> list, int r, int c) {
        if (!board.isPlayable(r, c)) {
            return;
        }
        for (int[] cell : list) {
            if (cell[0] == r && cell[1] == c) {
                return;
            }
        }
        list.add(new int[]{r, c});
    }

    private static String powerName(int type) {
        if (type == Board.T_RAINBOW) return "RAINBOW";
        if (type == Board.T_BOMB) return "BOMB";
        return "ROCKET";
    }

    /** Expand a clear set so any power tiles inside it detonate, chaining cascades. */
    private List<int[]> expandCascade(List<int[]> initial) {
        boolean[][] inSet = new boolean[board.rows][board.cols];
        ArrayDeque<int[]> q = new ArrayDeque<int[]>();
        List<int[]> out = new ArrayList<int[]>();
        for (int[] cell : initial) {
            addCascade(cell[0], cell[1], inSet, out, q);
        }
        while (!q.isEmpty()) {
            int[] cell = q.poll();
            if (board.typeAt(cell[0], cell[1]) != Board.T_NORMAL) {
                for (int[] pc : board.cellsForPower(cell[0], cell[1])) {
                    addCascade(pc[0], pc[1], inSet, out, q);
                }
            }
        }
        return out;
    }

    private void addCascade(int r, int c, boolean[][] inSet, List<int[]> out, ArrayDeque<int[]> q) {
        if (r < 0 || c < 0 || r >= board.rows || c >= board.cols || inSet[r][c]
                || board.colorAt(r, c) == Board.EMPTY) {
            return;
        }
        inSet[r][c] = true;
        out.add(new int[]{r, c});
        q.add(new int[]{r, c});
    }

    private void clearHintPreview() {
        previewCells = null;
        hintCells = null;
        idleTimer = 0f;
    }

    private void setPreview(int r, int c) {
        if (board == null) {
            previewCells = null;
            return;
        }
        if (board.typeAt(r, c) != Board.T_NORMAL) {
            List<int[]> one = new ArrayList<int[]>();
            one.add(new int[]{r, c});
            previewCells = one;
        } else {
            List<int[]> g = board.previewGroup(r, c);
            previewCells = g.isEmpty() ? null : g;
        }
        idleTimer = 0f;
        hintCells = null;
    }

    private boolean onBoosterBar(float x, float y) {
        for (RectF s : boosterBar()) {
            if (s.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private void applyBooster(int r, int c) {
        List<int[]> cells;
        if (armed == B_BOMB) {
            cells = board.cellsInBlast(r, c, 2); // 5x5 — a meaningfully "large area"
        } else if (armed == B_RAINBOW) {
            cells = board.cellsOfColor(board.colorAt(r, c));
        } else { // hammer
            if (board.isLocked(r, c)) {
                board.setLock(r, c, board.lockAt(r, c) - 1);
                store.addBooster(B_HAMMER, -1);
                armed = B_NONE;
                spawnParticle(colToX(c), rowToY(r), board.colorAt(r, c), dp(260), true);
                sound.playPop(3);
                return;
            }
            cells = new ArrayList<int[]>();
            cells.add(new int[]{r, c});
        }
        if (cells.isEmpty()) {
            armed = B_NONE;
            floatText("Nothing to pop", W / 2f, boardY + boardH / 2f, dp(16), Palette.TEXT_DIM, false);
            return;
        }
        boolean isHammer = armed == B_HAMMER;
        store.addBooster(armed, -1);
        armed = B_NONE;
        startClear(cells, false, isHammer);
    }

    /** Shared clear+score+animate path for taps and boosters. Goal counts every popped bubble. */
    private void startClear(List<int[]> cells, boolean costsMove, boolean singleHammer) {
        cells = expandCascade(cells); // detonate any power tiles caught in the clear
        int n = cells.size();
        if (n == 0) {
            return;
        }
        // Commit the pre-move snapshot for one-step Undo.
        if (pendingUndo != null) {
            undoSnap = pendingUndo;
            undoMoves = pendMoves;
            undoScore = pendScore;
            undoCollected = pendCollected;
            pendingUndo = null;
        }
        clearHintPreview();
        if (!singleHammer) {
            combo = Math.min(10, combo + 1);
            comboTimer = COMBO_WINDOW;
        }
        int mult = Math.max(1, combo);
        // Chain multiplier: clearing through power tiles escalates the score.
        int powerHits = 0;
        for (int[] pos : cells) {
            if (board.typeAt(pos[0], pos[1]) != Board.T_NORMAL) {
                powerHits++;
            }
        }
        float chainMult = 1f + powerHits * 0.5f;
        int gained = singleHammer ? 15 : (int) (Level.popScore(Math.max(2, n)) * mult * chainMult);
        score += gained;
        if (costsMove) {
            movesLeft--;
        }
        // Goal-aware progress (REACH_SCORE uses score directly in checkEndConditions).
        if (level.goalType == Level.COLLECT_COLOR) {
            int got = 0;
            for (int[] pos : cells) {
                if (board.colorAt(pos[0], pos[1]) == level.accentColor) {
                    got++;
                }
            }
            collected += got;
        } else if (level.goalType == Level.CLEAR_ANY) {
            collected += n;
        }

        popping.clear();
        poppingColors.clear();
        float cx = 0, cy = 0;
        for (int[] pos : cells) {
            int col = board.colorAt(pos[0], pos[1]);
            popping.add(new int[]{pos[0], pos[1]});
            poppingColors.add(col);
            cx += colToX(pos[1]);
            cy += rowToY(pos[0]);
            int count = 3 + Math.min(4, n / 3);
            for (int k = 0; k < count; k++) {
                spawnParticle(colToX(pos[1]), rowToY(pos[0]), col, dp(240), false);
            }
        }
        cx /= n;
        cy /= n;

        floatText("+" + gained, cx, cy - BR, dp(20), Palette.TEXT, true);
        if (combo >= 2) {
            // Keep a single, escalating combo callout (replace any prior so they don't stack into mush).
            for (int i = floats.size() - 1; i >= 0; i--) {
                if (floats.get(i).text.startsWith("COMBO")) {
                    floats.remove(i);
                }
            }
            int cc = combo >= 6 ? Palette.COMBO_HOT : (combo >= 4 ? Palette.STAR_ON : Palette.COMBO);
            floats.add(new Effects.FloatingText("COMBO x" + combo, W / 2f, boardY + boardH * 0.35f,
                    dp(26) + combo * dp(4), cc, 1.1f, true));
            // Central burst that grows with the combo.
            int burst = Math.min(44, 8 + n * 2 + combo * 3);
            for (int k = 0; k < burst; k++) {
                spawnParticle(cx, cy, poppingColors.get(k % poppingColors.size()),
                        dp(300) + combo * dp(30), true);
            }
            if (combo >= 3) {
                comboFlash = 1f;
            }
        }
        sound.playPop(combo);
        if (hapticsCache) {
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } catch (Throwable ignored) {
            }
        }
        firstPopDone = true;
        animPhase = 0;
        animT = 0;
        state = State.ANIM;
    }

    private void spawnParticle(float x, float y, int colorIdx, float speed, boolean sparkle) {
        int base = Palette.BUBBLE[Math.max(0, Math.min(Palette.BUBBLE.length - 1, colorIdx))];
        int color = sparkle && rng.nextInt(3) == 0 ? 0xFFFFFFFF : base;
        double a = rng.nextDouble() * Math.PI * 2;
        float sp = speed * (0.3f + rng.nextFloat());
        particles.add(new Effects.Particle(x, y,
                (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - speed * 0.3f,
                BR * (0.1f + rng.nextFloat() * 0.2f), color, 0.4f + rng.nextFloat() * 0.45f));
    }

    private void floatText(String s, float x, float y, float size, int color, boolean bold) {
        floats.add(new Effects.FloatingText(s, x, y, size, color, 0.9f, bold));
    }

    private void clearFx() {
        particles.clear();
        floats.clear();
    }

    private float colToX(int c) {
        return boardX + c * cell + cell / 2f;
    }

    private float rowToY(float r) {
        return boardY + r * cell + cell / 2f;
    }

    // ===================================================================
    //  Drawing
    // ===================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        if (W == 0 || bgPaint == null) {
            return;
        }
        try {
            canvas.drawRect(0, 0, W, H, bgPaint);
            drawBgBubbles(canvas);
            switch (state) {
                case MENU: drawMenu(canvas); break;
                case MAP: drawMap(canvas); break;
                case PREGAME: drawPregame(canvas); break;
                case PLAYING:
                case ANIM: drawGame(canvas); break;
                case COMPLETE: drawGame(canvas); drawComplete(canvas); break;
                case FAILED: drawGame(canvas); drawFailed(canvas); break;
                case DAILY: drawDaily(canvas); break;
                case STATS: drawStats(canvas); break;
                case PAUSED: drawGame(canvas); drawPause(canvas); break;
                case SETTINGS: drawSettings(canvas); break;
            }
            drawParticles(canvas);
            drawFloats(canvas);
            if (transFade < 1f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(Palette.withAlpha(Palette.BG_TOP, (int) (255 * (1f - transFade))));
                canvas.drawRect(0, 0, W, H, p);
            }
        } catch (Throwable t) {
            safeRecover();
        }
    }

    private void drawBgBubbles(Canvas canvas) {
        p.setStyle(Paint.Style.FILL);
        for (float[] b : bgBubbles) {
            p.setColor(Palette.withAlpha(Palette.BUBBLE[(int) b[4]], (int) (b[5] * 255)));
            canvas.drawCircle(b[0], b[1], b[2], p);
        }
    }

    // ---- MENU ----------------------------------------------------------

    private void drawMenu(Canvas canvas) {
        drawTopCoins(canvas);
        float cy = H * 0.30f;
        drawLogo(canvas, W / 2f, cy);
        text(canvas, "Pop Colors. Clear Stress.", W / 2f, cy + dp(72), dp(15),
                Palette.TEXT_DIM, false, Paint.Align.CENTER);

        RectF play = menuPlay();
        drawButton(canvas, play, "PLAY", Palette.GREEN, Palette.GREEN_DK, dp(26));
        text(canvas, "LEVEL " + store.unlockedLevel() + "   •   ★ " + store.totalStars(),
                W / 2f, play.top - dp(22), dp(15), Palette.TEXT, true, Paint.Align.CENTER);

        RectF daily = menuDaily(), stats = menuStats(), snd = menuSound();
        drawRoundIcon(canvas, daily, Palette.GOLD, Palette.GOLD_DK);
        drawGiftIcon(canvas, daily.centerX(), daily.centerY(), dp(16));
        drawRoundIcon(canvas, stats, Palette.BLUE, Palette.BLUE_DK);
        drawTrophyIcon(canvas, stats.centerX(), stats.centerY(), dp(16));
        drawRoundIcon(canvas, snd, Palette.PURPLE, Palette.PURPLE_DK);
        drawSoundIcon(canvas, snd.centerX(), snd.centerY(), dp(15), store.soundOn());
        text(canvas, "DAILY", daily.centerX(), daily.bottom + dp(18), dp(11), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, "STATS", stats.centerX(), stats.bottom + dp(18), dp(11), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, "OPTIONS", snd.centerX(), snd.bottom + dp(18), dp(11), Palette.TEXT_DIM, true, Paint.Align.CENTER);
    }

    private void drawLogo(Canvas canvas, float cx, float cy) {
        String[] words = {"COLOR", "POP", "RUSH"};
        float size = Math.min(dp(54), W / 6.2f);
        tp.setFakeBoldText(true);
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        int letterIdx = 0;
        float lineH = size * 1.02f;
        float startY = cy - lineH;
        for (int wi = 0; wi < words.length; wi++) {
            String word = words[wi];
            float total = 0;
            float[] widths = new float[word.length()];
            for (int i = 0; i < word.length(); i++) {
                widths[i] = tp.measureText(word.substring(i, i + 1)) * 1.04f;
                total += widths[i];
            }
            float x = cx - total / 2f;
            float y = startY + wi * lineH;
            for (int i = 0; i < word.length(); i++) {
                float lx = x + widths[i] / 2f;
                tp.setColor(0x55000000);
                canvas.drawText(word.substring(i, i + 1), lx + dp(2), y + dp(3), tp);
                tp.setColor(Palette.LOGO[letterIdx % Palette.LOGO.length]);
                canvas.drawText(word.substring(i, i + 1), lx, y, tp);
                x += widths[i];
                letterIdx++;
            }
        }
    }

    // ---- MAP -----------------------------------------------------------

    private float nodeGap() {
        return dp(96);
    }

    private float nodeY(int index) {
        return dp(130) + index * nodeGap() - mapScroll;
    }

    private float nodeX(int index) {
        return W / 2f + (float) Math.sin(index * 0.9f) * (W * 0.22f);
    }

    private int mapNodeCount() {
        return store.unlockedLevel() + 8; // show a generous run of upcoming (locked) levels
    }

    private float computeMapScrollMax() {
        return Math.max(0, dp(130) + (mapNodeCount() - 1) * nodeGap() + dp(120) - H);
    }

    /** Open the map scrolled so the player's current (unlocked) level is centred. */
    private void scrollMapToCurrent() {
        mapScrollMax = computeMapScrollMax();
        int idx = store.unlockedLevel() - 1;
        mapScroll = clamp(dp(130) + idx * nodeGap() - H * 0.5f, 0, mapScrollMax);
    }

    private void drawMapPath(Canvas canvas, int count, int from, int to, int color, float width) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        Path path = new Path();
        boolean started = false;
        for (int i = Math.max(0, from); i <= Math.min(count - 1, to); i++) {
            float x = nodeX(i), y = nodeY(i);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        if (started) {
            canvas.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawMap(Canvas canvas) {
        int count = mapNodeCount();
        mapScrollMax = computeMapScrollMax();
        mapScroll = clamp(mapScroll, 0, mapScrollMax);

        int unlocked = store.unlockedLevel();
        // path: brighter (gold) up to the current level, dim beyond
        drawMapPath(canvas, count, 0, Math.min(count - 1, unlocked - 1), 0x66FFD23F, dp(7));
        drawMapPath(canvas, count, Math.max(0, unlocked - 1), count - 1, 0x2EFFFFFF, dp(6));

        for (int i = 0; i < count; i++) {
            int lvl = i + 1;
            float x = nodeX(i), y = nodeY(i);
            if (y < -dp(60) || y > H + dp(60)) {
                continue;
            }
            boolean locked = lvl > unlocked;
            float r = dp(30);
            if (lvl % 20 == 1 && lvl > 1) { // world divider
                float wy = y - nodeGap() * 0.5f;
                p.setColor(0x22FFFFFF);
                canvas.drawRect(dp(24), wy - dp(1), W - dp(24), wy + dp(1), p);
                text(canvas, "WORLD " + ((lvl - 1) / 20 + 1), W / 2f, wy + dp(5), dp(13),
                        Palette.COMBO, true, Paint.Align.CENTER);
            }
            p.setColor(0x44000000);
            canvas.drawCircle(x, y + dp(4), r, p);
            if (locked) {
                int col = Palette.BUBBLE[lvl % Palette.BUBBLE.length];
                p.setColor(Palette.scale(col, 0.45f)); // darkened "locked" chip, still shows its colour
                canvas.drawCircle(x, y, r, p);
                p.setColor(Palette.withAlpha(0xFFFFFFFF, 28));
                canvas.drawCircle(x - r * 0.25f, y - r * 0.3f, r * 0.42f, p);
                text(canvas, Integer.toString(lvl), x, y + dp(7), dp(18), 0x99FFFFFF, true, Paint.Align.CENTER);
                drawLockIcon(canvas, x + r * 0.52f, y - r * 0.52f, dp(7));
            } else {
                int col = Palette.BUBBLE[lvl % Palette.BUBBLE.length];
                p.setColor(col);
                canvas.drawCircle(x, y, r, p);
                p.setColor(Palette.lighten(col, 0.5f));
                canvas.drawCircle(x - r * 0.28f, y - r * 0.30f, r * 0.32f, p);
                text(canvas, Integer.toString(lvl), x, y + dp(7), dp(20), Palette.TEXT, true, Paint.Align.CENTER);
                int st = store.stars(lvl);
                for (int s = 0; s < 3; s++) {
                    drawStar(canvas, x - dp(16) + s * dp(16), y + r + dp(12), dp(7),
                            s < st ? Palette.STAR_ON : Palette.STAR_OFF);
                }
                if (lvl == unlocked) { // highlight the player's current level
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(dp(4));
                    p.setColor(Palette.STAR_ON);
                    canvas.drawCircle(x, y, r + dp(5), p);
                    p.setStyle(Paint.Style.FILL);
                    text(canvas, "PLAY", x, y - r - dp(10), dp(12), Palette.STAR_ON, true, Paint.Align.CENTER);
                }
            }
        }

        // Header drawn on top so scrolling nodes never collide with the title.
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.withAlpha(Palette.BG_TOP, 225));
        canvas.drawRect(0, 0, W, dp(66), p);
        text(canvas, "SELECT LEVEL", W / 2f, dp(44), dp(22), Palette.TEXT, true, Paint.Align.CENTER);

        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
        drawTopCoins(canvas);
    }

    // ---- PREGAME (with booster shop) ----------------------------------

    private RectF pregameCard() {
        return new RectF(W * 0.1f, H * 0.16f, W * 0.9f, H * 0.84f);
    }

    private RectF[] pregameBoosterSlots() {
        RectF card = pregameCard();
        float by = card.top + card.height() * 0.62f;
        float spacing = (card.width() - dp(24)) / BOOSTER_COUNT;
        RectF[] out = new RectF[BOOSTER_COUNT];
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            float bx = card.left + dp(12) + spacing * i + spacing / 2f;
            out[i] = new RectF(bx - dp(24), by - dp(24), bx + dp(24), by + dp(24));
        }
        return out;
    }

    private void drawPregame(Canvas canvas) {
        drawTopCoins(canvas);
        RectF card = pregameCard();
        drawPanel(canvas, card, dp(28));

        text(canvas, "LEVEL " + selectedLevel, W / 2f, card.top + dp(56), dp(34), Palette.TEXT, true, Paint.Align.CENTER);
        text(canvas, "GOAL", W / 2f, card.top + dp(104), dp(14), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        float gy = card.top + dp(150);
        if (level.goalType == Level.BREAK) {
            drawLockIcon(canvas, W / 2f - dp(52), gy, dp(18));
            text(canvas, "Free " + level.lockCount + " locks", W / 2f + dp(28), gy + dp(10), dp(24), Palette.TEXT, true, Paint.Align.CENTER);
        } else if (level.goalType == Level.REACH_SCORE) {
            drawStar(canvas, W / 2f - dp(52), gy, dp(22), Palette.STAR_ON);
            text(canvas, "Score " + level.target, W / 2f + dp(36), gy + dp(10), dp(26), Palette.TEXT, true, Paint.Align.CENTER);
        } else {
            drawBubble(canvas, W / 2f - dp(52), gy, dp(24), level.accentColor, 255);
            text(canvas, (level.goalType == Level.COLLECT_COLOR ? "Collect " : "Clear ") + level.target,
                    W / 2f + dp(40), gy + dp(10), dp(26), Palette.TEXT, true, Paint.Align.CENTER);
        }
        text(canvas, "Moves: " + level.moves + "      Colors: " + level.numColors,
                W / 2f, gy + dp(46), dp(15), Palette.TEXT_DIM, false, Paint.Align.CENTER);

        text(canvas, "BOOSTERS  (tap to buy)", W / 2f, card.top + card.height() * 0.50f, dp(13),
                Palette.TEXT_DIM, true, Paint.Align.CENTER);
        RectF[] slots = pregameBoosterSlots();
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            drawBoosterButton(canvas, slots[i], i, store.booster(i), false);
            text(canvas, B_NAMES[i], slots[i].centerX(), slots[i].bottom + dp(14), dp(9), Palette.TEXT_DIM, true, Paint.Align.CENTER);
            drawCoin(canvas, slots[i].centerX() - dp(14), slots[i].bottom + dp(30), dp(8));
            text(canvas, Integer.toString(B_PRICE[i]), slots[i].centerX() + dp(4), slots[i].bottom + dp(34), dp(12),
                    Palette.GOLD, true, Paint.Align.CENTER);
        }

        drawButton(canvas, pregamePlay(), "PLAY", Palette.GREEN, Palette.GREEN_DK, dp(24));
        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
    }

    // ---- GAME ----------------------------------------------------------

    private void drawGame(Canvas canvas) {
        RectF pause = backBtn();
        drawRoundIcon(canvas, pause, Palette.CARD, Palette.CARD_EDGE);
        drawPauseIcon(canvas, pause.centerX(), pause.centerY(), dp(11));
        drawTopCoins(canvas);
        if (undoSnap != null) {
            RectF ub = undoBtn();
            drawRoundIcon(canvas, ub, Palette.GOLD, Palette.GOLD_DK);
            drawUndoIcon(canvas, ub.centerX(), ub.centerY(), dp(12));
        }
        // Current level shown top-centre (matches the design sheet's "LEVEL 48").
        text(canvas, "LEVEL " + selectedLevel, W / 2f, dp(35), dp(17), Palette.TEXT, true, Paint.Align.CENTER);

        // Three evenly-spaced stat pills (consistent grid; readable over the bg).
        float pillTop = H * 0.085f, pillH = dp(58), gap = dp(8), side = dp(16);
        float pw = (W - side * 2 - gap * 2) / 3f;
        RectF mPill = new RectF(side, pillTop, side + pw, pillTop + pillH);
        RectF gPill = new RectF(mPill.right + gap, pillTop, mPill.right + gap + pw, pillTop + pillH);
        RectF sPill = new RectF(gPill.right + gap, pillTop, gPill.right + gap + pw, pillTop + pillH);
        drawStatPill(canvas, mPill);
        drawStatPill(canvas, gPill);
        drawStatPill(canvas, sPill);
        float lY = pillTop + dp(20), vY = pillTop + pillH - dp(13);
        // MOVES
        text(canvas, "MOVES", mPill.centerX(), lY, dp(12), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, Integer.toString(Math.max(0, movesLeft)), mPill.centerX(), vY, dp(26),
                movesLeft <= 5 ? Palette.RED : Palette.TEXT, true, Paint.Align.CENTER);
        // GOAL — icon + value drawn as one centred unit (no horizontal wobble)
        text(canvas, "GOAL", gPill.centerX(), lY, dp(12), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        int gp = goalProgress();
        boolean gdone = gp >= target;
        String gv = Math.min(gp, target) + "/" + target;
        tp.setFakeBoldText(true);
        tp.setTextSize(dp(22));
        float gvw = tp.measureText(gv);
        float unitW = dp(22) + dp(6) + gvw, ux = gPill.centerX() - unitW / 2f;
        if (level.goalType == Level.BREAK) {
            drawLockIcon(canvas, ux + dp(11), vY - dp(6), dp(9));
        } else if (level.goalType == Level.REACH_SCORE) {
            drawStar(canvas, ux + dp(11), vY - dp(7), dp(11), Palette.STAR_ON);
        } else {
            drawBubble(canvas, ux + dp(11), vY - dp(7), dp(11), level.accentColor, 255);
        }
        text(canvas, gv, ux + dp(28), vY, dp(22), gdone ? Palette.GREEN : Palette.TEXT, true, Paint.Align.LEFT);
        // SCORE — auto-shrinks to fit its pill
        text(canvas, "SCORE", sPill.centerX(), lY, dp(12), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        textFit(canvas, comma((int) displayScore), sPill.centerX(), vY, dp(22), pw - dp(16), Palette.GOLD, Paint.Align.CENTER);

        drawBoard(canvas);

        // Combo flash over the board.
        if (comboFlash > 0f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (90 * comboFlash)));
            canvas.drawRoundRect(new RectF(boardX, boardY, boardX + boardW, boardY + boardH), dp(16), dp(16), p);
        }

        // Combo meter, drawn above the board panel (so it isn't clipped) with room to breathe.
        if (combo >= 2) {
            float frac = Math.max(0f, comboTimer / COMBO_WINDOW);
            float barW = W * 0.5f, bx = (W - barW) / 2f, byy = boardY - dp(20);
            int cc = combo >= 6 ? Palette.COMBO_HOT : (combo >= 4 ? Palette.STAR_ON : Palette.COMBO);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x44000000);
            canvas.drawRoundRect(new RectF(bx, byy, bx + barW, byy + dp(8)), dp(4), dp(4), p);
            p.setColor(cc);
            canvas.drawRoundRect(new RectF(bx, byy, bx + barW * frac, byy + dp(8)), dp(4), dp(4), p);
        }

        drawBoosterBar(canvas);
        if (armed == B_SWAP) {
            text(canvas, swapFirst == null ? "Tap two bubbles to swap" : "Tap an adjacent bubble",
                    W / 2f, H - dp(94), dp(14), Palette.COMBO, true, Paint.Align.CENTER);
        } else if (armed != B_NONE) {
            text(canvas, "Tap a bubble to use booster", W / 2f, H - dp(94), dp(14),
                    Palette.COMBO, true, Paint.Align.CENTER);
        } else if (selectedLevel == 1 && !firstPopDone) {
            text(canvas, "Tap 2+ touching same-colour bubbles!", W / 2f, boardY - dp(34), dp(15),
                    Palette.COMBO, true, Paint.Align.CENTER);
        }
    }

    private void drawBoard(Canvas canvas) {
        RectF bg = new RectF(boardX - dp(8), boardY - dp(8), boardX + boardW + dp(8), boardY + boardH + dp(8));
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.BOARD_BG);
        canvas.drawRoundRect(bg, dp(16), dp(16), p);
        // frame for definition against the bright background
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0x33FFFFFF);
        canvas.drawRoundRect(bg, dp(16), dp(16), p);
        p.setStyle(Paint.Style.FILL);

        if (board == null) {
            return;
        }
        // recessed sockets behind every cell (tray look)
        p.setColor(0x1A000000);
        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                canvas.drawCircle(colToX(c), rowToY(r), BR * 0.98f, p);
            }
        }
        boolean fall = (state == State.ANIM && animPhase == 1);
        boolean popPhase = (state == State.ANIM && animPhase == 0);
        float fallP = easeOutBack(Math.min(1f, animT / FALL_DUR)); // slight landing bounce

        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                int col = board.colorAt(r, c);
                if (col == Board.EMPTY) {
                    continue;
                }
                if (popPhase && isPopping(r, c)) {
                    continue; // drawn only by the shrinking overlay below
                }
                float x = colToX(c);
                float y = fall ? lerp(rowToY(board.srcRowOf(r, c)), rowToY(r), fallP) : rowToY(r);
                drawBoardBubble(canvas, x, y, col, 1f, 255);
                int tt = board.typeAt(r, c);
                if (tt != Board.T_NORMAL) {
                    drawPowerEmblem(canvas, x, y, tt, 255);
                }
                int lk = board.lockAt(r, c);
                if (lk > 0) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0x88000000);
                    canvas.drawCircle(x, y, BR, p);
                    drawLockIcon(canvas, x, y, BR * 0.42f);
                    if (lk > 1) {
                        text(canvas, Integer.toString(lk), x + BR * 0.55f, y + BR * 0.7f, dp(12),
                                Palette.TEXT, true, Paint.Align.CENTER);
                    }
                }
            }
        }
        // touch-down preview + idle hint highlights
        if (state == State.PLAYING) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(hintPulse * 5f);
            if (previewCells != null) {
                drawCellHighlight(canvas, previewCells, Palette.withAlpha(0xFFFFFFFF, 170), 1f + 0.05f * pulse);
                int pn = previewCells.size();
                String lbl = pn >= 9 ? "→ RAINBOW" : pn >= 7 ? "→ BOMB" : pn >= 5 ? "→ ROCKET" : (pn + " pop");
                float px = 0, py = 0;
                for (int[] cellp : previewCells) {
                    px += colToX(cellp[1]);
                    py += rowToY(cellp[0]);
                }
                px /= pn;
                py /= pn;
                text(canvas, lbl, px, py - BR - dp(6), dp(15), Palette.COMBO, true, Paint.Align.CENTER);
            } else if (hintCells != null && !hintCells.isEmpty()) {
                drawCellHighlight(canvas, hintCells, Palette.withAlpha(0xFFFFFFFF, (int) (60 + 90 * pulse)), 1f + 0.06f * pulse);
            }
            if (armed == B_SWAP && swapFirst != null) {
                List<int[]> one = new ArrayList<int[]>();
                one.add(swapFirst);
                drawCellHighlight(canvas, one, Palette.withAlpha(Palette.COMBO, (int) (160 + 80 * pulse)), 1.06f);
            }
        }
        // power-tile forming ring
        if (pendingSpawn != null && spawnT < 0.5f) {
            float k = spawnT / 0.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(BR * 0.18f * (1f - k));
            p.setColor(Palette.withAlpha(Palette.COMBO, (int) (200 * (1f - k))));
            canvas.drawCircle(colToX(pendingSpawn[1]), rowToY(pendingSpawn[0]), BR * (1f + k * 0.8f), p);
            p.setStyle(Paint.Style.FILL);
        }
        if (popPhase) {
            float t = Math.min(1f, animT / POP_DUR);
            float s = (1f - t * t) * (1f + 0.3f * (float) Math.sin(Math.PI * t)); // squash then pop
            int a = (int) (255 * Math.max(0f, 1f - t));
            for (int i = 0; i < popping.size(); i++) {
                int[] pos = popping.get(i);
                drawBoardBubble(canvas, colToX(pos[1]), rowToY(pos[0]), poppingColors.get(i),
                        Math.max(0.01f, s), a);
            }
        }
    }

    private boolean isPopping(int r, int c) {
        for (int i = 0; i < popping.size(); i++) {
            int[] pos = popping.get(i);
            if (pos[0] == r && pos[1] == c) {
                return true;
            }
        }
        return false;
    }

    private void drawCellHighlight(Canvas canvas, List<int[]> cells, int color, float scale) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(BR * 0.14f);
        p.setColor(color);
        for (int[] pos : cells) {
            canvas.drawCircle(colToX(pos[1]), rowToY(pos[0]), BR * scale, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBoosterBar(Canvas canvas) {
        RectF[] slots = boosterBar();
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            drawBoosterButton(canvas, slots[i], i, store.booster(i), armed == i);
            text(canvas, B_NAMES[i], slots[i].centerX(), slots[i].bottom + dp(13), dp(9),
                    armed == i ? Palette.COMBO : Palette.TEXT_DIM, true, Paint.Align.CENTER);
        }
    }

    // ---- COMPLETE / FAILED --------------------------------------------

    private RectF completeCard() {
        return new RectF(W * 0.12f, H * 0.16f, W * 0.88f, H * 0.84f);
    }

    private void drawComplete(Canvas canvas) {
        dimScreen(canvas);
        RectF card = completeCard();
        drawPanel(canvas, card, dp(28));
        text(canvas, "LEVEL " + selectedLevel, W / 2f, card.top + dp(44), dp(18), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, "COMPLETE!", W / 2f, card.top + dp(82), dp(34), Palette.GREEN, true, Paint.Align.CENTER);

        float sy = card.top + dp(132);
        for (int i = 0; i < 3; i++) {
            boolean on = i < resultStars;
            float x = W / 2f + (i - 1) * dp(64);
            if (on) {
                float appear = resultT - 0.25f - i * 0.22f;
                if (appear <= 0) {
                    continue; // truly pops in from nothing
                }
                float sc = Math.min(1f, easeOutBack(Math.min(1f, appear / 0.35f)));
                drawStar(canvas, x, sy - (1 - sc) * dp(16), dp(30) * sc, Palette.STAR_ON);
            } else {
                drawStar(canvas, x, sy, dp(22), Palette.STAR_OFF);
            }
        }

        text(canvas, "SCORE", W / 2f, sy + dp(64), dp(14), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        float sp = Math.min(1f, Math.max(0f, (resultT - 0.6f) / 0.7f));
        text(canvas, comma((int) (score * sp)), W / 2f, sy + dp(96), dp(34), Palette.TEXT, true, Paint.Align.CENTER);

        float cp = Math.min(1f, Math.max(0f, (resultT - 1.3f) / 0.6f));
        int shownCoins = (int) (resultCoins * cp);
        drawCoin(canvas, W / 2f - dp(40), sy + dp(128), dp(15));
        text(canvas, "+" + shownCoins, W / 2f + dp(4), sy + dp(136), dp(24), Palette.GOLD, true, Paint.Align.CENTER);
        if (resultBooster >= 0) {
            text(canvas, "Bonus " + B_NAMES[resultBooster] + " booster!", W / 2f, sy + dp(160), dp(13),
                    Palette.COMBO, true, Paint.Align.CENTER);
        }

        drawButton(canvas, completeNext(), "NEXT", Palette.GREEN, Palette.GREEN_DK, dp(24));
        drawButton(canvas, completeHome(), "MAP", Palette.BLUE, Palette.BLUE_DK, dp(20));
    }

    private RectF failedCard() {
        return new RectF(W * 0.12f, H * 0.2f, W * 0.88f, H * 0.82f);
    }

    private void drawFailed(Canvas canvas) {
        dimScreen(canvas);
        RectF card = failedCard();
        drawPanel(canvas, card, dp(28));
        text(canvas, "OUT OF MOVES", W / 2f, card.top + dp(60), dp(28), Palette.RED, true, Paint.Align.CENTER);
        int gp = goalProgress();
        text(canvas, "You reached", W / 2f, card.top + dp(104), dp(16), Palette.TEXT_DIM, false, Paint.Align.CENTER);
        if (level.goalType == Level.BREAK) {
            drawLockIcon(canvas, W / 2f - dp(34), card.top + dp(148), dp(16));
        } else if (level.goalType == Level.REACH_SCORE) {
            drawStar(canvas, W / 2f - dp(34), card.top + dp(148), dp(18), Palette.STAR_ON);
        } else {
            drawBubble(canvas, W / 2f - dp(34), card.top + dp(148), dp(20), level.accentColor, 255);
        }
        text(canvas, gp + " / " + target, W / 2f + dp(26), card.top + dp(156), dp(28), Palette.TEXT, true, Paint.Align.CENTER);

        boolean canRescue = store.booster(B_MOVES) > 0 || store.coins() >= B_PRICE[B_MOVES];
        if (canRescue) {
            String lbl = store.booster(B_MOVES) > 0 ? "CONTINUE  +5 MOVES" : "CONTINUE  (" + B_PRICE[B_MOVES] + "¢)";
            drawButton(canvas, failedContinue(), lbl, Palette.GOLD, Palette.GOLD_DK, dp(18));
        }
        drawButton(canvas, failedRetry(), "RETRY", Palette.GREEN, Palette.GREEN_DK, dp(22));
        drawButton(canvas, failedHome(), "MAP", Palette.BLUE, Palette.BLUE_DK, dp(18));
    }

    // ---- DAILY ---------------------------------------------------------

    private long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    private boolean dailyClaimable() {
        return today() != store.lastClaimDay();
    }

    /**
     * @return {claimedCount, currentIndex} for the 7-day calendar. currentIndex is
     * the day that would be claimed now (and its reward index), or -1 if already
     * claimed today. Correctly handles broken streaks (a missed day restarts at day 1).
     */
    private int[] dailyState() {
        long t = today();
        int s = store.dailyStreak();
        if (t == store.lastClaimDay()) { // already claimed today
            int claimed = (s == 0) ? 0 : ((s - 1) % 7) + 1;
            return new int[]{claimed, -1};
        }
        boolean continuing = (t == store.lastClaimDay() + 1);
        int earnedBefore = continuing ? (s % 7) : 0;
        return new int[]{earnedBefore, earnedBefore};
    }

    private void drawDaily(Canvas canvas) {
        drawTopCoins(canvas);
        RectF card = new RectF(W * 0.08f, H * 0.16f, W * 0.92f, H * 0.84f);
        drawPanel(canvas, card, dp(28));
        text(canvas, "DAILY REWARD", W / 2f, card.top + dp(54), dp(28), Palette.TEXT, true, Paint.Align.CENTER);
        text(canvas, "Streak: " + store.dailyStreak() + " days", W / 2f, card.top + dp(86), dp(15), Palette.TEXT_DIM, false, Paint.Align.CENTER);

        int[] ds = dailyState();
        int claimedCount = ds[0];
        int currentIndex = ds[1];
        boolean claimable = dailyClaimable();
        float gridTop = card.top + dp(104);
        float cellW = (card.width() - dp(60)) / 3f;
        float cellH = dp(76);
        for (int i = 0; i < 7; i++) {
            int row = i / 3, coli = i % 3;
            boolean isBig = (i == 6);
            float x = card.left + dp(30) + cellW * coli;
            float y = gridTop + row * (cellH + dp(10));
            RectF cellRect = isBig
                    ? new RectF(card.left + dp(30), y, card.right - dp(30), y + cellH)
                    : new RectF(x, y, x + cellW - dp(10), y + cellH);
            boolean claimed = i < claimedCount;
            boolean current = (i == currentIndex);
            p.setStyle(Paint.Style.FILL);
            p.setColor(current ? Palette.GOLD : (claimed ? 0x3322CC55 : Palette.SLOT));
            canvas.drawRoundRect(cellRect, dp(14), dp(14), p);
            text(canvas, "DAY " + (i + 1), cellRect.centerX(), cellRect.top + dp(20), dp(12),
                    current ? Palette.CARD_EDGE : Palette.TEXT, true, Paint.Align.CENTER);
            if (claimed) {
                drawCheck(canvas, cellRect.centerX(), cellRect.centerY() + dp(8), dp(14));
            } else if (isBig) {
                drawChest(canvas, cellRect.centerX() - dp(64), cellRect.centerY() + dp(8), dp(15));
                text(canvas, "+" + DAILY_REWARDS[i] + "  +BONUS", cellRect.centerX() + dp(22), cellRect.centerY() + dp(13),
                        dp(14), current ? Palette.CARD_EDGE : Palette.TEXT, true, Paint.Align.CENTER);
            } else {
                drawCoin(canvas, cellRect.centerX() - dp(18), cellRect.centerY() + dp(14), dp(12));
                text(canvas, Integer.toString(DAILY_REWARDS[i]), cellRect.centerX() + dp(6), cellRect.centerY() + dp(20),
                        dp(18), current ? Palette.CARD_EDGE : Palette.TEXT, true, Paint.Align.CENTER);
            }
        }

        RectF claim = dailyClaim();
        if (claimable) {
            drawButton(canvas, claim, "CLAIM", Palette.GREEN, Palette.GREEN_DK, dp(22));
        } else {
            drawButton(canvas, claim, "COME BACK TOMORROW", Palette.STAR_OFF, Palette.scale(Palette.STAR_OFF, 0.8f), dp(15));
        }
        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
    }

    // ---- STATS ---------------------------------------------------------

    private void drawStats(Canvas canvas) {
        drawTopCoins(canvas);
        RectF card = new RectF(W * 0.1f, H * 0.2f, W * 0.9f, H * 0.8f);
        drawPanel(canvas, card, dp(28));
        text(canvas, "YOUR STATS", W / 2f, card.top + dp(54), dp(28), Palette.TEXT, true, Paint.Align.CENTER);

        String[][] rows = {
                {"Levels cleared", Integer.toString(store.levelsCleared())},
                {"Total stars", Integer.toString(store.totalStars())},
                {"Best score", comma(store.bestScore())},
                {"Coins", comma(store.coins())},
        };
        float ry = card.top + dp(110);
        for (String[] r : rows) {
            text(canvas, r[0], card.left + dp(34), ry, dp(17), Palette.TEXT_DIM, false, Paint.Align.LEFT);
            text(canvas, r[1], card.right - dp(34), ry, dp(20), Palette.TEXT, true, Paint.Align.RIGHT);
            ry += dp(50);
        }

        drawButton(canvas, statsReset(), resetArmed ? "TAP AGAIN TO CONFIRM" : "RESET PROGRESS",
                resetArmed ? Palette.RED_DK : Palette.RED, Palette.scale(Palette.RED_DK, 0.85f), dp(16));
        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
    }

    // ---- PAUSE / SETTINGS ---------------------------------------------

    private RectF pauseCard() {
        return new RectF(W * 0.18f, H * 0.22f, W * 0.82f, H * 0.78f);
    }

    private RectF pauseBtn(int i) {
        RectF c = pauseCard();
        float w = c.width() - dp(56), h = dp(50), gap = dp(14);
        float top = c.top + dp(74) + i * (h + gap);
        return new RectF(c.centerX() - w / 2f, top, c.centerX() + w / 2f, top + h);
    }

    private void drawPause(Canvas canvas) {
        dimScreen(canvas);
        RectF card = pauseCard();
        drawPanel(canvas, card, dp(28));
        text(canvas, "PAUSED", W / 2f, card.top + dp(48), dp(30), Palette.TEXT, true, Paint.Align.CENTER);
        drawButton(canvas, pauseBtn(0), "RESUME", Palette.GREEN, Palette.GREEN_DK, dp(20));
        drawButton(canvas, pauseBtn(1), "RESTART", Palette.GOLD, Palette.GOLD_DK, dp(20));
        drawButton(canvas, pauseBtn(2), "SETTINGS", Palette.BLUE, Palette.BLUE_DK, dp(20));
        drawButton(canvas, pauseBtn(3), "QUIT TO MAP", Palette.RED, Palette.RED_DK, dp(18));
    }

    private RectF settingsCard() {
        return new RectF(W * 0.12f, H * 0.2f, W * 0.88f, H * 0.8f);
    }

    private RectF settingsToggle(int i) {
        RectF c = settingsCard();
        float w = dp(82), h = dp(40);
        float y = c.top + dp(108) + i * dp(66);
        return new RectF(c.right - dp(28) - w, y, c.right - dp(28), y + h);
    }

    private void drawSettings(Canvas canvas) {
        RectF card = settingsCard();
        drawPanel(canvas, card, dp(28));
        text(canvas, "SETTINGS", W / 2f, card.top + dp(54), dp(28), Palette.TEXT, true, Paint.Align.CENTER);
        String[] labels = {"Sound effects", "Haptics", "Colour symbols"};
        boolean[] vals = {store.soundOn(), store.hapticsOn(), store.symbolsOn()};
        for (int i = 0; i < 3; i++) {
            RectF t = settingsToggle(i);
            text(canvas, labels[i], card.left + dp(30), t.centerY() + dp(6), dp(17), Palette.TEXT, false, Paint.Align.LEFT);
            drawToggle(canvas, t, vals[i]);
        }
        text(canvas, "Color Pop Rush  •  offline  •  no ads", W / 2f, card.bottom - dp(34), dp(12),
                Palette.TEXT_DIM, false, Paint.Align.CENTER);
        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
    }

    private void drawToggle(Canvas canvas, RectF r, boolean on) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(on ? Palette.GREEN : Palette.STAR_OFF);
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, p);
        float kr = r.height() / 2f - dp(3);
        float kx = on ? r.right - kr - dp(3) : r.left + kr + dp(3);
        p.setColor(Palette.TEXT);
        canvas.drawCircle(kx, r.centerY(), kr, p);
        text(canvas, on ? "ON" : "OFF", on ? r.left + dp(16) : r.right - dp(16), r.centerY() + dp(5),
                dp(12), Palette.withAlpha(0xFFFFFFFF, 200), true, on ? Paint.Align.LEFT : Paint.Align.RIGHT);
    }

    // ===================================================================
    //  Reusable drawing primitives
    // ===================================================================

    private void drawParticles(Canvas canvas) {
        p.setStyle(Paint.Style.FILL);
        for (Effects.Particle pt : particles) {
            float a = pt.alpha();
            p.setColor(Palette.withAlpha(pt.color, (int) (a * 255)));
            canvas.drawCircle(pt.x, pt.y, Math.max(0.5f, pt.radius * (0.35f + 0.65f * a)), p);
        }
    }

    private void drawFloats(Canvas canvas) {
        for (Effects.FloatingText f : floats) {
            tp.setFakeBoldText(f.bold);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(f.size * f.scale());
            tp.setColor(Palette.withAlpha(0xFF000000, (int) (f.alpha() * 90)));
            canvas.drawText(f.text, f.x + dp(1.5f), f.y + dp(1.5f), tp);
            tp.setColor(Palette.withAlpha(f.color, (int) (f.alpha() * 255)));
            canvas.drawText(f.text, f.x, f.y, tp);
        }
    }

    private void drawBoardBubble(Canvas canvas, float cx, float cy, int idx, float scale, int alpha) {
        if (bubbleShader == null || BR <= 0) {
            return;
        }
        idx = Math.max(0, Math.min(Palette.BUBBLE.length - 1, idx));
        int base = Palette.BUBBLE[idx];
        canvas.save();
        canvas.translate(cx, cy);
        if (scale != 1f) {
            canvas.scale(scale, scale);
        }
        p.setStyle(Paint.Style.FILL);
        // soft drop shadow for depth against the board
        p.setColor(Palette.withAlpha(0xFF1A0F3A, (int) (alpha * 0.22f)));
        canvas.drawCircle(0, BR * 0.14f, BR, p);
        p.setShader(bubbleShader[idx]);
        p.setAlpha(alpha);
        canvas.drawCircle(0, 0, BR, p);
        p.setShader(null);
        // darker rim for definition against neighbours
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(BR * 0.09f);
        p.setColor(Palette.withAlpha(Palette.scale(base, 0.72f), alpha));
        canvas.drawCircle(0, 0, BR * 0.95f, p);
        p.setStyle(Paint.Style.FILL);
        // soft glossy sheen + specular dot
        p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (alpha * 0.32f)));
        canvas.drawCircle(-BR * 0.26f, -BR * 0.3f, BR * 0.34f, p);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (alpha * 0.85f)));
        canvas.drawCircle(-BR * 0.32f, -BR * 0.35f, BR * 0.13f, p);
        if (symbolsCache) {
            drawSymbol(canvas, 0, 0, BR, idx, alpha);
        }
        p.setAlpha(255);
        canvas.restore();
    }

    private void drawBubble(Canvas canvas, float cx, float cy, float r, int idx, int alpha) {
        if (r <= 0) {
            return;
        }
        idx = Math.max(0, Math.min(Palette.BUBBLE.length - 1, idx));
        int base = Palette.BUBBLE[idx];
        p.setStyle(Paint.Style.FILL);
        if (bubbleShader != null && BR > 0) {
            // Reuse the cached board shader (built once) instead of allocating per frame.
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(r / BR, r / BR);
            p.setShader(bubbleShader[idx]);
            p.setAlpha(alpha);
            canvas.drawCircle(0, 0, BR, p);
            p.setShader(null);
            p.setAlpha(255);
            canvas.restore();
        } else {
            p.setColor(Palette.withAlpha(base, alpha));
            canvas.drawCircle(cx, cy, r, p);
        }
        p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (alpha * 0.6f)));
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.34f, r * 0.2f, p);
        p.setAlpha(255);
    }

    private void drawPanel(Canvas canvas, RectF r, float radius) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(6), r.right, r.bottom + dp(8)), radius, radius, p);
        p.setColor(Palette.CARD);
        canvas.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0x22FFFFFF);
        canvas.drawRoundRect(r, radius, radius, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawButton(Canvas canvas, RectF r, String label, int fill, int edge, float textSize) {
        float radius = r.height() / 2.4f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(edge);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(5), r.right, r.bottom + dp(5)), radius, radius, p);
        // Solid candy face + cheap top sheen (no per-frame shader allocation).
        p.setColor(fill);
        canvas.drawRoundRect(r, radius, radius, p);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, 60));
        canvas.drawRoundRect(new RectF(r.left + dp(6), r.top + dp(5), r.right - dp(6), r.top + r.height() * 0.44f),
                radius * 0.6f, radius * 0.6f, p);
        // Auto-shrink the label to fit, with a dark shadow for legibility on light fills.
        tp.setFakeBoldText(true);
        tp.setTextSize(textSize);
        float maxW = r.width() - radius * 1.5f;
        float lw = tp.measureText(label);
        float ts = (lw > maxW && lw > 0) ? textSize * maxW / lw : textSize;
        float ly = r.centerY() + ts * 0.36f;
        text(canvas, label, r.centerX() + dp(1.2f), ly + dp(1.6f), ts, 0x66000000, true, Paint.Align.CENTER);
        text(canvas, label, r.centerX(), ly, ts, Palette.TEXT, true, Paint.Align.CENTER);
    }

    private void drawRoundIcon(Canvas canvas, RectF r, int fill, int edge) {
        float cx = r.centerX(), cy = r.centerY(), rad = Math.min(r.width(), r.height()) / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(edge);
        canvas.drawCircle(cx, cy + dp(4), rad, p);
        p.setColor(fill);
        canvas.drawCircle(cx, cy, rad, p);
        // glossy top sheen for the candy chip look
        p.setColor(Palette.withAlpha(0xFFFFFFFF, 45));
        canvas.drawCircle(cx - rad * 0.22f, cy - rad * 0.3f, rad * 0.5f, p);
    }

    private void drawBoosterButton(Canvas canvas, RectF r, int id, int count, boolean armedNow) {
        float radius = dp(16);
        p.setStyle(Paint.Style.FILL);
        if (armedNow) {
            p.setColor(Palette.COMBO);
            canvas.drawRoundRect(new RectF(r.left - dp(3), r.top - dp(3), r.right + dp(3), r.bottom + dp(3)),
                    radius + dp(3), radius + dp(3), p);
        }
        p.setColor(0x55000000);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(4), r.right, r.bottom + dp(4)), radius, radius, p);
        p.setColor(count > 0 ? Palette.CARD : Palette.scale(Palette.CARD, 0.7f));
        canvas.drawRoundRect(r, radius, radius, p);

        float cx = r.centerX(), cy = r.centerY(), s = r.height() * 0.3f;
        switch (id) {
            case B_BOMB: drawBombIcon(canvas, cx, cy, s); break;
            case B_RAINBOW: drawRainbowIcon(canvas, cx, cy, s); break;
            case B_HAMMER: drawHammerIcon(canvas, cx, cy, s); break;
            case B_SHUFFLE: drawShuffleIcon(canvas, cx, cy, s); break;
            case B_SWAP: drawSwapIcon(canvas, cx, cy, s); break;
            default: drawPlusMovesIcon(canvas, cx, cy, s); break;
        }
        float bx = r.right - dp(4), by = r.top + dp(4);
        p.setColor(count > 0 ? Palette.RED : Palette.STAR_OFF);
        canvas.drawCircle(bx, by, dp(11), p);
        text(canvas, Integer.toString(count), bx, by + dp(5), dp(13), Palette.TEXT, true, Paint.Align.CENTER);
    }

    private void drawCoin(Canvas canvas, float cx, float cy, float r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.GOLD_DK);
        canvas.drawCircle(cx, cy, r, p);
        p.setColor(Palette.GOLD);
        canvas.drawCircle(cx, cy, r * 0.84f, p);
        p.setColor(Palette.lighten(Palette.GOLD, 0.4f));
        canvas.drawCircle(cx - r * 0.25f, cy - r * 0.28f, r * 0.3f, p);
        text(canvas, "$", cx, cy + r * 0.42f, r * 1.1f, Palette.GOLD_DK, true, Paint.Align.CENTER);
    }

    private void drawStar(Canvas canvas, float cx, float cy, float r, int color) {
        if (r <= 0) {
            return;
        }
        Path path = new Path();
        float inner = r * 0.45f;
        for (int i = 0; i < 10; i++) {
            float ang = (float) (-Math.PI / 2 + i * Math.PI / 5);
            float rad = (i % 2 == 0) ? r : inner;
            float x = cx + (float) Math.cos(ang) * rad;
            float y = cy + (float) Math.sin(ang) * rad;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        canvas.drawPath(path, p);
        if (color == Palette.STAR_ON) {
            p.setColor(Palette.lighten(color, 0.4f));
            canvas.drawCircle(cx - r * 0.2f, cy - r * 0.25f, r * 0.2f, p);
        }
    }

    private void drawTopCoins(Canvas canvas) {
        float cy = dp(30);
        float right = W - dp(18);
        String coins = comma(store.coins());
        tp.setTextSize(dp(18));
        tp.setFakeBoldText(true);
        float tw = tp.measureText(coins);
        RectF pill = new RectF(right - tw - dp(48), cy - dp(18), right, cy + dp(18));
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x44000000);
        canvas.drawRoundRect(pill, dp(18), dp(18), p);
        drawCoin(canvas, pill.left + dp(18), cy, dp(13));
        text(canvas, coins, pill.right - dp(12), cy + dp(6), dp(18), Palette.TEXT, true, Paint.Align.RIGHT);
    }

    private void dimScreen(Canvas canvas) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.OVERLAY);
        canvas.drawRect(0, 0, W, H, p);
    }

    // ---- tiny vector icons --------------------------------------------

    private void drawBombIcon(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF2A2A3A);
        canvas.drawCircle(cx, cy + s * 0.2f, s, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        p.setColor(0xFF888899);
        canvas.drawLine(cx + s * 0.5f, cy - s * 0.5f, cx + s * 0.9f, cy - s * 1.1f, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.GOLD);
        canvas.drawCircle(cx + s * 0.95f, cy - s * 1.2f, s * 0.3f, p);
    }

    private void drawRainbowIcon(Canvas canvas, float cx, float cy, float s) {
        int[] cols = {Palette.BUBBLE[0], Palette.BUBBLE[2], Palette.BUBBLE[3], Palette.BUBBLE[4]};
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.42f);
        for (int i = 0; i < cols.length; i++) {
            p.setColor(cols[i]);
            float rr = s * (1.25f - i * 0.32f);
            canvas.drawArc(new RectF(cx - rr, cy - rr + s * 0.4f, cx + rr, cy + rr + s * 0.4f), 180, 180, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawHammerIcon(Canvas canvas, float cx, float cy, float s) {
        canvas.save();
        canvas.rotate(-35, cx, cy);
        p.setColor(0xFFB97A3A);
        canvas.drawRoundRect(new RectF(cx - s * 0.18f, cy - s * 0.1f, cx + s * 0.18f, cy + s * 1.3f), dp(2), dp(2), p);
        p.setColor(0xFFB0B6C2);
        canvas.drawRoundRect(new RectF(cx - s * 1.1f, cy - s * 1.1f, cx + s * 1.1f, cy - s * 0.1f), dp(3), dp(3), p);
        canvas.restore();
    }

    private void drawPlusMovesIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(Palette.GREEN);
        canvas.drawCircle(cx, cy, s * 1.1f, p);
        text(canvas, "+5", cx, cy + s * 0.45f, s * 1.2f, Palette.TEXT, true, Paint.Align.CENTER);
    }

    private void drawShuffleIcon(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.4f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Palette.TEXT);
        canvas.drawArc(new RectF(cx - s, cy - s, cx + s, cy + s), -40, 200, false, p);
        p.setStyle(Paint.Style.FILL);
        // two arrow heads suggesting a swap
        canvas.drawCircle(cx + s * 0.9f, cy - s * 0.5f, dp(2.4f), p);
        canvas.drawCircle(cx - s * 0.9f, cy + s * 0.5f, dp(2.4f), p);
    }

    private void drawSwapIcon(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Palette.TEXT);
        canvas.drawLine(cx - s, cy - s * 0.5f, cx + s, cy - s * 0.5f, p);
        canvas.drawLine(cx + s, cy - s * 0.5f, cx + s * 0.4f, cy - s, p);
        canvas.drawLine(cx + s, cy + s * 0.5f, cx - s, cy + s * 0.5f, p);
        canvas.drawLine(cx - s, cy + s * 0.5f, cx - s * 0.4f, cy + s, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawUndoIcon(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Palette.CARD_EDGE);
        canvas.drawArc(new RectF(cx - s, cy - s, cx + s, cy + s), 110, 230, false, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx - s * 0.85f, cy - s * 0.35f, dp(3f), p);
        p.setStrokeWidth(dp(3f));
        p.setStyle(Paint.Style.STROKE);
        canvas.drawLine(cx - s * 0.85f, cy - s * 0.35f, cx - s * 0.2f, cy - s * 0.7f, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Small white emblem drawn on a power tile to show its kind. */
    private void drawPowerEmblem(Canvas canvas, float x, float y, int type, int alpha) {
        p.setColor(Palette.withAlpha(0xFFFFFFFF, alpha));
        float s = BR * 0.5f;
        if (type == Board.T_ROCKET_H || type == Board.T_ROCKET_V) {
            canvas.save();
            canvas.translate(x, y);
            if (type == Board.T_ROCKET_V) {
                canvas.rotate(90);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(BR * 0.14f);
            p.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(-s, 0, s, 0, p);
            canvas.drawLine(s, 0, s * 0.4f, -s * 0.5f, p);
            canvas.drawLine(s, 0, s * 0.4f, s * 0.5f, p);
            canvas.drawLine(-s, 0, -s * 0.4f, -s * 0.5f, p);
            canvas.drawLine(-s, 0, -s * 0.4f, s * 0.5f, p);
            p.setStyle(Paint.Style.FILL);
            canvas.restore();
        } else if (type == Board.T_BOMB) {
            canvas.drawCircle(x, y, s * 0.8f, p);
            p.setColor(Palette.withAlpha(Palette.GOLD, alpha));
            canvas.drawCircle(x + s * 0.6f, y - s * 0.7f, s * 0.25f, p);
        } else if (type == Board.T_RAINBOW) {
            drawStar(canvas, x, y, s, Palette.withAlpha(0xFFFFFFFF, alpha));
        }
    }

    /** Colourblind aid: a distinct dark symbol per colour index. */
    private void drawSymbol(Canvas canvas, float cx, float cy, float r, int idx, int alpha) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.withAlpha(0xFF20143F, (int) (alpha * 0.78f)));
        float s = r * 0.42f;
        switch (idx % 6) {
            case 0:
                canvas.drawCircle(cx, cy, s * 0.62f, p);
                break;
            case 1: {
                Path t = new Path();
                t.moveTo(cx, cy - s);
                t.lineTo(cx + s, cy + s * 0.8f);
                t.lineTo(cx - s, cy + s * 0.8f);
                t.close();
                canvas.drawPath(t, p);
                break;
            }
            case 2:
                canvas.drawRect(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f, p);
                break;
            case 3: {
                Path d = new Path();
                d.moveTo(cx, cy - s);
                d.lineTo(cx + s, cy);
                d.lineTo(cx, cy + s);
                d.lineTo(cx - s, cy);
                d.close();
                canvas.drawPath(d, p);
                break;
            }
            case 4:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.2f);
                p.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(cx - s, cy, cx + s, cy, p);
                canvas.drawLine(cx, cy - s, cx, cy + s, p);
                p.setStyle(Paint.Style.FILL);
                break;
            default:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(r * 0.16f);
                canvas.drawCircle(cx, cy, s * 0.72f, p);
                p.setStyle(Paint.Style.FILL);
                break;
        }
    }

    private void drawGiftIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(Palette.TEXT);
        canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.2f, cx + s, cy + s), dp(3), dp(3), p);
        canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.6f, cx + s, cy - s * 0.2f), dp(2), dp(2), p);
        p.setColor(Palette.RED);
        canvas.drawRect(cx - s * 0.16f, cy - s * 0.6f, cx + s * 0.16f, cy + s, p);
    }

    private void drawChest(Canvas canvas, float cx, float cy, float s) {
        p.setColor(0xFF8A5A2B);
        canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.2f, cx + s, cy + s), dp(3), dp(3), p);
        p.setColor(0xFFB97A3A);
        canvas.drawRoundRect(new RectF(cx - s, cy - s * 0.7f, cx + s, cy - s * 0.1f), dp(4), dp(4), p);
        p.setColor(Palette.GOLD);
        canvas.drawRect(cx - s * 0.16f, cy - s * 0.7f, cx + s * 0.16f, cy + s, p);
        canvas.drawCircle(cx, cy - s * 0.15f, s * 0.18f, p);
    }

    private void drawTrophyIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(Palette.TEXT);
        canvas.drawRoundRect(new RectF(cx - s * 0.7f, cy - s, cx + s * 0.7f, cy + s * 0.2f), dp(3), dp(3), p);
        canvas.drawRect(cx - s * 0.2f, cy + s * 0.2f, cx + s * 0.2f, cy + s * 0.7f, p);
        canvas.drawRect(cx - s * 0.6f, cy + s * 0.7f, cx + s * 0.6f, cy + s, p);
    }

    private void drawSoundIcon(Canvas canvas, float cx, float cy, float s, boolean on) {
        p.setColor(Palette.TEXT);
        Path sp = new Path();
        sp.moveTo(cx - s, cy - s * 0.4f);
        sp.lineTo(cx - s * 0.3f, cy - s * 0.4f);
        sp.lineTo(cx + s * 0.3f, cy - s);
        sp.lineTo(cx + s * 0.3f, cy + s);
        sp.lineTo(cx - s * 0.3f, cy + s * 0.4f);
        sp.lineTo(cx - s, cy + s * 0.4f);
        sp.close();
        canvas.drawPath(sp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2));
        if (on) {
            canvas.drawArc(new RectF(cx, cy - s * 0.7f, cx + s * 1.3f, cy + s * 0.7f), -50, 100, false, p);
        } else {
            canvas.drawLine(cx + s * 0.6f, cy - s * 0.5f, cx + s * 1.3f, cy + s * 0.5f, p);
            canvas.drawLine(cx + s * 1.3f, cy - s * 0.5f, cx + s * 0.6f, cy + s * 0.5f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawLockIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(0xFFCBC4E8);
        canvas.drawRoundRect(new RectF(cx - s * 0.7f, cy - s * 0.1f, cx + s * 0.7f, cy + s * 0.8f), dp(3), dp(3), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        canvas.drawArc(new RectF(cx - s * 0.45f, cy - s * 0.7f, cx + s * 0.45f, cy + s * 0.2f), 180, 180, false, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBackArrow(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3.5f));
        p.setColor(Palette.TEXT);
        p.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx + s * 0.6f, cy - s, cx - s * 0.5f, cy, p);
        canvas.drawLine(cx - s * 0.5f, cy, cx + s * 0.6f, cy + s, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawPauseIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(Palette.TEXT);
        canvas.drawRoundRect(new RectF(cx - s * 0.6f, cy - s, cx - s * 0.1f, cy + s), dp(2), dp(2), p);
        canvas.drawRoundRect(new RectF(cx + s * 0.1f, cy - s, cx + s * 0.6f, cy + s), dp(2), dp(2), p);
    }

    private void drawCheck(Canvas canvas, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3.5f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF2ECC71);
        canvas.drawLine(cx - s * 0.6f, cy, cx - s * 0.1f, cy + s * 0.5f, p);
        canvas.drawLine(cx - s * 0.1f, cy + s * 0.5f, cx + s * 0.7f, cy - s * 0.6f, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ===================================================================
    //  Geometry (shared by draw + input)
    // ===================================================================

    private RectF menuPlay() {
        float w = W * 0.62f;
        return new RectF((W - w) / 2f, H * 0.56f, (W + w) / 2f, H * 0.56f + dp(66));
    }

    private RectF menuDaily() {
        float y = H * 0.73f, r = dp(32);
        return new RectF(W * 0.5f - dp(110) - r, y - r, W * 0.5f - dp(110) + r, y + r);
    }

    private RectF menuStats() {
        float y = H * 0.73f, r = dp(32);
        return new RectF(W * 0.5f - r, y - r, W * 0.5f + r, y + r);
    }

    private RectF menuSound() {
        float y = H * 0.73f, r = dp(32);
        return new RectF(W * 0.5f + dp(110) - r, y - r, W * 0.5f + dp(110) + r, y + r);
    }

    private RectF backBtn() {
        return new RectF(dp(16), dp(14), dp(16) + dp(48), dp(14) + dp(48));
    }

    private RectF undoBtn() {
        float s = dp(44), x = dp(16) + dp(48) + dp(10);
        return new RectF(x, dp(16), x + s, dp(16) + s);
    }

    private RectF pregamePlay() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.84f - dp(76), (W + w) / 2f, H * 0.84f - dp(76) + dp(58));
    }

    private RectF[] boosterBar() {
        RectF[] out = new RectF[BOOSTER_COUNT];
        float size = dp(50);
        float gap = (W - dp(32) - size * BOOSTER_COUNT) / (BOOSTER_COUNT - 1);
        float y = H - dp(84);
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            float x = dp(16) + i * (size + gap);
            out[i] = new RectF(x, y, x + size, y + size);
        }
        return out;
    }

    private RectF completeNext() {
        RectF c = completeCard();
        float w = W * 0.5f, b = c.bottom - dp(78);
        return new RectF((W - w) / 2f, b - dp(58), (W + w) / 2f, b);
    }

    private RectF completeHome() {
        RectF c = completeCard();
        float w = W * 0.34f, b = c.bottom - dp(22);
        return new RectF((W - w) / 2f, b - dp(44), (W + w) / 2f, b);
    }

    private RectF failedContinue() {
        RectF c = failedCard();
        float w = W * 0.6f, b = c.bottom - dp(136);
        return new RectF((W - w) / 2f, b - dp(50), (W + w) / 2f, b);
    }

    private RectF failedRetry() {
        RectF c = failedCard();
        float w = W * 0.5f, b = c.bottom - dp(74);
        return new RectF((W - w) / 2f, b - dp(54), (W + w) / 2f, b);
    }

    private RectF failedHome() {
        RectF c = failedCard();
        float w = W * 0.34f, b = c.bottom - dp(20);
        return new RectF((W - w) / 2f, b - dp(44), (W + w) / 2f, b);
    }

    private RectF dailyClaim() {
        float w = W * 0.6f;
        return new RectF((W - w) / 2f, H * 0.84f - dp(70), (W + w) / 2f, H * 0.84f - dp(70) + dp(56));
    }

    private RectF statsReset() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.8f - dp(70), (W + w) / 2f, H * 0.8f - dp(70) + dp(52));
    }

    // ===================================================================
    //  Input
    // ===================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX();
                    downY = e.getY();
                    lastY = downY;
                    movedWhileDown = false;
                    mapVel = 0f;
                    if (state == State.PLAYING && armed == B_NONE) {
                        int[] cp = boardCell(e.getX(), e.getY());
                        if (cp != null && !onBoosterBar(e.getX(), e.getY())) {
                            setPreview(cp[0], cp[1]);
                        } else {
                            previewCells = null;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (state == State.MAP) {
                        float dy = e.getY() - lastY;
                        mapScroll = clamp(mapScroll - dy, 0, mapScrollMax);
                        mapVel = -dy;
                        lastY = e.getY();
                    }
                    if (Math.abs(e.getX() - downX) > dp(10) || Math.abs(e.getY() - downY) > dp(10)) {
                        movedWhileDown = true;
                        previewCells = null;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!movedWhileDown) {
                        handleTap(e.getX(), e.getY());
                    }
                    previewCells = null;
                    return true;
                default:
                    return true;
            }
        } catch (Throwable t) {
            safeRecover();
            return true;
        }
    }

    private void handleTap(float x, float y) {
        switch (state) {
            case MENU: tapMenu(x, y); break;
            case MAP: tapMap(x, y); break;
            case PREGAME: tapPregame(x, y); break;
            case PLAYING: tapGame(x, y); break;
            case ANIM: bufferTap(x, y); break;
            case COMPLETE: tapComplete(x, y); break;
            case FAILED: tapFailed(x, y); break;
            case DAILY: tapDaily(x, y); break;
            case STATS: tapStats(x, y); break;
            case PAUSED: tapPause(x, y); break;
            case SETTINGS: tapSettings(x, y); break;
            default: break;
        }
    }

    private void tapMenu(float x, float y) {
        if (menuPlay().contains(x, y)) {
            gotoMap();
        } else if (menuDaily().contains(x, y)) {
            clearFx();
            state = State.DAILY;
            transFade = 0f;
            sound.playClick();
        } else if (menuStats().contains(x, y)) {
            clearFx();
            state = State.STATS;
            transFade = 0f;
            sound.playClick();
        } else if (menuSound().contains(x, y)) {
            settingsReturn = State.MENU;
            clearFx();
            state = State.SETTINGS;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void tapPause(float x, float y) {
        if (pauseBtn(0).contains(x, y)) {
            state = State.PLAYING;
            sound.playClick();
        } else if (pauseBtn(1).contains(x, y)) {
            startGame();
        } else if (pauseBtn(2).contains(x, y)) {
            settingsReturn = State.PAUSED;
            state = State.SETTINGS;
            sound.playClick();
        } else if (pauseBtn(3).contains(x, y)) {
            gotoMap();
        }
    }

    private void tapSettings(float x, float y) {
        if (settingsToggle(0).contains(x, y)) {
            boolean v = !store.soundOn();
            store.setSoundOn(v);
            sound.setEnabled(v);
            sound.playClick();
        } else if (settingsToggle(1).contains(x, y)) {
            boolean v = !store.hapticsOn();
            store.setHapticsOn(v);
            hapticsCache = v;
            sound.playClick();
        } else if (settingsToggle(2).contains(x, y)) {
            boolean v = !store.symbolsOn();
            store.setSymbolsOn(v);
            symbolsCache = v;
            sound.playClick();
        } else if (backBtn().contains(x, y)) {
            state = settingsReturn;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void gotoMap() {
        clearFx();
        state = State.MAP;
        scrollMapToCurrent();
        transFade = 0f;
        sound.playClick();
    }

    private void tapMap(float x, float y) {
        if (backBtn().contains(x, y)) {
            clearFx();
            state = State.MENU;
            transFade = 0f;
            sound.playClick();
            return;
        }
        int count = mapNodeCount();
        int unlocked = store.unlockedLevel();
        for (int i = 0; i < count; i++) {
            float nx = nodeX(i), ny = nodeY(i);
            if ((x - nx) * (x - nx) + (y - ny) * (y - ny) <= dp(34) * dp(34)) {
                if (i + 1 <= unlocked) {
                    startPregame(i + 1);
                }
                return;
            }
        }
    }

    private void tapPregame(float x, float y) {
        if (pregamePlay().contains(x, y)) {
            startGame();
            return;
        }
        if (backBtn().contains(x, y)) {
            gotoMap();
            return;
        }
        RectF[] slots = pregameBoosterSlots();
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            if (slots[i].contains(x, y)) {
                if (store.spendCoins(B_PRICE[i])) {
                    store.addBooster(i, 1);
                    floatText("+1 " + B_NAMES[i], slots[i].centerX(), slots[i].top, dp(15), Palette.GREEN, true);
                    sound.playCoin();
                } else {
                    floatText("Need more coins", W / 2f, slots[i].top - dp(10), dp(15), Palette.RED, true);
                    sound.playClick();
                }
                return;
            }
        }
    }

    private void tapGame(float x, float y) {
        if (backBtn().contains(x, y)) {
            state = State.PAUSED;
            clearHintPreview();
            sound.playClick();
            return;
        }
        if (undoSnap != null && undoBtn().contains(x, y)) {
            doUndo();
            return;
        }
        RectF[] slots = boosterBar();
        for (int i = 0; i < BOOSTER_COUNT; i++) {
            if (slots[i].contains(x, y)) {
                tapBooster(i);
                return;
            }
        }
        int[] cell = boardCell(x, y);
        if (cell == null) {
            return;
        }
        if (armed == B_SWAP) {
            handleSwapTap(cell[0], cell[1]);
            return;
        }
        // Snapshot before a real board action (committed in startClear if a move happens).
        pendingUndo = board.snapshot();
        pendMoves = movesLeft;
        pendScore = score;
        pendCollected = collected;
        if (armed != B_NONE) {
            applyBooster(cell[0], cell[1]);
        } else if (board.typeAt(cell[0], cell[1]) != Board.T_NORMAL) {
            triggerPower(cell[0], cell[1]);
        } else {
            tryPop(cell[0], cell[1]);
        }
    }

    private void handleSwapTap(int r, int c) {
        if (!board.isPlayable(r, c)) {
            sound.playClick();
            return;
        }
        if (swapFirst == null) {
            swapFirst = new int[]{r, c};
            sound.playClick();
        } else if (swapFirst[0] == r && swapFirst[1] == c) {
            swapFirst = null;
        } else if (board.swap(swapFirst[0], swapFirst[1], r, c)) {
            store.addBooster(B_SWAP, -1);
            armed = B_NONE;
            swapFirst = null;
            clearHintPreview();
            floatText("Swapped!", colToX(c), rowToY(r) - BR, dp(16), Palette.COMBO, true);
            sound.playSwoosh();
        } else {
            swapFirst = new int[]{r, c};
            sound.playClick();
        }
    }

    private void doUndo() {
        if (undoSnap == null || board == null) {
            return;
        }
        board.restore(undoSnap);
        movesLeft = undoMoves;
        score = undoScore;
        displayScore = score;
        collected = undoCollected;
        combo = 0;
        comboTimer = 0f;
        undoSnap = null;
        clearHintPreview();
        floatText("Undo", W / 2f, boardY + boardH / 2f, dp(20), Palette.COMBO, true);
        sound.playSwoosh();
    }

    /** Buffer a board tap made during the pop/fall animation so combos keep flowing. */
    private void bufferTap(float x, float y) {
        int[] cell = boardCell(x, y);
        if (cell != null && armed == B_NONE) {
            bufR = cell[0];
            bufC = cell[1];
        }
    }

    private int[] boardCell(float x, float y) {
        if (board == null || x < boardX || x >= boardX + boardW || y < boardY || y >= boardY + boardH) {
            return null;
        }
        int c = (int) ((x - boardX) / cell);
        int r = (int) ((y - boardY) / cell);
        if (r < 0 || c < 0 || r >= board.rows || c >= board.cols || board.colorAt(r, c) == Board.EMPTY) {
            return null;
        }
        return new int[]{r, c};
    }

    private void tapBooster(int id) {
        if (id == B_SHUFFLE) {
            if (board != null && store.booster(B_SHUFFLE) > 0) {
                store.addBooster(B_SHUFFLE, -1);
                board.shuffle();
                clearHintPreview();
                floatText("Shuffled!", W / 2f, boardY + boardH / 2f, dp(20), Palette.COMBO, true);
                sound.playSwoosh();
            } else {
                floatText("Out of MIX", W / 2f, boardY + boardH / 2f, dp(14), Palette.RED, true);
                sound.playClick();
            }
            return;
        }
        if (id == B_MOVES) {
            if (store.booster(B_MOVES) > 0) {
                store.addBooster(B_MOVES, -1);
                movesLeft += 5;
                floatText("+5 MOVES", W * 0.22f, H * 0.115f + dp(50), dp(18), Palette.GREEN, true);
                sound.playCoin();
            } else {
                floatText("Out of +5", W * 0.22f, H * 0.115f + dp(50), dp(14), Palette.RED, true);
                sound.playClick();
            }
            return;
        }
        if (store.booster(id) > 0) {
            armed = (armed == id) ? B_NONE : id;
            swapFirst = null;
            sound.playClick();
        } else {
            floatText("Out of " + B_NAMES[id], W / 2f, boardY + boardH + dp(6), dp(14), Palette.RED, true);
            sound.playClick();
        }
    }

    private void tapComplete(float x, float y) {
        if (completeNext().contains(x, y)) {
            startPregame(selectedLevel + 1);
        } else if (completeHome().contains(x, y)) {
            gotoMap();
        }
    }

    private void tapFailed(float x, float y) {
        boolean canRescue = store.booster(B_MOVES) > 0 || store.coins() >= B_PRICE[B_MOVES];
        if (canRescue && failedContinue().contains(x, y)) {
            if (store.booster(B_MOVES) > 0) {
                store.addBooster(B_MOVES, -1);
            } else {
                store.spendCoins(B_PRICE[B_MOVES]);
            }
            movesLeft += 5;
            state = State.PLAYING; // resume the SAME board
            transFade = 0f;
            floatText("+5 MOVES", W / 2f, H * 0.4f, dp(22), Palette.GREEN, true);
            sound.playCoin();
        } else if (failedRetry().contains(x, y)) {
            startGame();
        } else if (failedHome().contains(x, y)) {
            gotoMap();
        }
    }

    private void tapDaily(float x, float y) {
        if (dailyClaim().contains(x, y) && dailyClaimable()) {
            int idx = dailyState()[1];
            int reward = DAILY_REWARDS[idx];
            store.addCoins(reward);
            long t = today();
            int newStreak = (t == store.lastClaimDay() + 1) ? store.dailyStreak() + 1 : 1;
            store.setDaily(t, newStreak);
            floatText("+" + reward, W / 2f, H * 0.5f, dp(28), Palette.GOLD, true);
            if (idx == 6) {
                store.addBooster(rng.nextInt(4), 2);
            }
            sound.playCoin();
        } else if (backBtn().contains(x, y)) {
            clearFx();
            state = State.MENU;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void tapStats(float x, float y) {
        if (statsReset().contains(x, y)) {
            if (resetArmed) {
                store.resetAll();
                selectedLevel = 1;
                resetArmed = false;
                floatText("Progress reset", W / 2f, H * 0.5f, dp(20), Palette.RED, true);
                sound.playClick();
            } else {
                resetArmed = true;       // require a second, confirming tap within ~3s
                resetArmedT = 3f;
                sound.playClick();
            }
        } else if (backBtn().contains(x, y)) {
            resetArmed = false;
            clearFx();
            state = State.MENU;
            transFade = 0f;
            sound.playClick();
        }
    }

    /** Handle the hardware/gesture back button. @return true if consumed. */
    public boolean onBack() {
        switch (state) {
            case MENU:
                return false;
            case PLAYING:
            case ANIM:
                state = State.PAUSED;
                clearHintPreview();
                return true;
            case PAUSED:
                state = State.PLAYING;
                return true;
            case SETTINGS:
                state = settingsReturn;
                transFade = 0f;
                return true;
            case PREGAME:
            case COMPLETE:
            case FAILED:
                clearFx();
                state = State.MAP;
                scrollMapToCurrent();
                transFade = 0f;
                return true;
            default:
                clearFx();
                state = State.MENU;
                transFade = 0f;
                return true;
        }
    }

    // ===================================================================
    //  Small helpers
    // ===================================================================

    private void text(Canvas canvas, String s, float x, float y, float size, int color, boolean bold, Paint.Align align) {
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setFakeBoldText(bold);
        tp.setTextAlign(align);
        canvas.drawText(s, x, y, tp);
    }

    /** Text that auto-shrinks to fit maxW (keeps large values on screen). */
    private void textFit(Canvas canvas, String s, float x, float y, float maxSize, float maxW, int color, Paint.Align align) {
        tp.setFakeBoldText(true);
        tp.setTextSize(maxSize);
        float w = tp.measureText(s);
        float size = (w > maxW && w > 0) ? maxSize * maxW / w : maxSize;
        text(canvas, s, x, y, size, color, true, align);
    }

    private void drawStatPill(Canvas canvas, RectF r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000);
        canvas.drawRoundRect(r, dp(14), dp(14), p);
        p.setColor(0x14FFFFFF);
        canvas.drawRoundRect(new RectF(r.left, r.top, r.right, r.top + r.height() * 0.5f), dp(14), dp(14), p);
    }

    private static String comma(int v) {
        String s = Integer.toString(Math.abs(v));
        StringBuilder b = new StringBuilder();
        int c = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            b.append(s.charAt(i));
            if (++c % 3 == 0 && i > 0) {
                b.append(',');
            }
        }
        if (v < 0) {
            b.append('-');
        }
        return b.reverse().toString();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
