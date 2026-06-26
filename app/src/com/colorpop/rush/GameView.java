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

    private enum State { MENU, MAP, PREGAME, PLAYING, ANIM, COMPLETE, FAILED, DAILY, STATS }

    // Booster ids (match Storage booster ids).
    private static final int B_NONE = -1, B_BOMB = 0, B_RAINBOW = 1, B_HAMMER = 2, B_MOVES = 3;
    private static final String[] B_NAMES = {"BOMB", "COLOR", "HAMMER", "+5"};
    private static final int[] B_PRICE = {120, 150, 100, 80};

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

    // Effects
    private final List<Effects.Particle> particles = new ArrayList<Effects.Particle>();
    private final List<Effects.FloatingText> floats = new ArrayList<Effects.FloatingText>();

    // Result screen
    private int resultStars, resultCoins, resultBooster = -1;
    private float resultT;

    // Map scrolling
    private float mapScroll, mapScrollMax;

    // Stats screen: reset needs a confirmation tap
    private boolean resetArmed;
    private float resetArmedT;

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
        bgPaint = new Paint();
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                Palette.BG_TOP, Palette.BG_BOTTOM, Shader.TileMode.CLAMP));
        layoutBoard();
        initBgBubbles();
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
                    && board.isPoppable(bufR, bufC)) {
                int r = bufR, c = bufC;
                bufR = bufC = -1;
                tryPop(r, c);
            } else {
                bufR = bufC = -1;
            }
        }
    }

    private void checkEndConditions() {
        if (collected >= target) {
            win();
        } else if (movesLeft <= 0) {
            lose();
        }
    }

    // ===================================================================
    //  Game actions
    // ===================================================================

    private void startPregame(int lvl) {
        selectedLevel = Math.max(1, lvl);
        level = new Level(selectedLevel);
        layoutBoard();
        clearFx();
        state = State.PREGAME;
        transFade = 0f;
        sound.playClick();
    }

    private void startGame() {
        level = new Level(selectedLevel);
        layoutBoard();
        board = new Board(level.cols, level.rows, level.numColors, rng.nextLong());
        score = 0;
        displayScore = 0;
        movesLeft = level.moves;
        target = level.target;
        collected = 0;
        combo = 0;
        armed = B_NONE;
        bufR = bufC = -1;
        clearFx();
        state = State.PLAYING;
        transFade = 0f;
        sound.playSwoosh();
    }

    private void win() {
        resultStars = level.starsFor(movesLeft);
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
        if (g.size() >= 2) {
            startClear(g, true, false);
        } else {
            sound.playClick();
        }
    }

    private void applyBooster(int r, int c) {
        List<int[]> cells;
        if (armed == B_BOMB) {
            cells = board.cellsInBlast(r, c, 2); // 5x5 — a meaningfully "large area"
        } else if (armed == B_RAINBOW) {
            cells = board.cellsOfColor(board.colorAt(r, c));
        } else {
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
        int n = cells.size();
        if (!singleHammer) {
            combo = Math.min(10, combo + 1);
            comboTimer = COMBO_WINDOW;
        }
        int mult = Math.max(1, combo);
        int gained = singleHammer ? 15 : Level.popScore(Math.max(2, n)) * mult;
        score += gained;
        if (costsMove) {
            movesLeft--;
        }
        collected += n; // every popped bubble counts toward the goal -> always winnable

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
        text(canvas, "SOUND", snd.centerX(), snd.bottom + dp(18), dp(11), Palette.TEXT_DIM, true, Paint.Align.CENTER);
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

    private void drawMap(Canvas canvas) {
        int count = mapNodeCount();
        mapScrollMax = computeMapScrollMax();
        mapScroll = clamp(mapScroll, 0, mapScrollMax);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(6));
        p.setColor(0x33FFFFFF);
        Path path = new Path();
        for (int i = 0; i < count; i++) {
            float x = nodeX(i), y = nodeY(i);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);

        int unlocked = store.unlockedLevel();
        for (int i = 0; i < count; i++) {
            int lvl = i + 1;
            float x = nodeX(i), y = nodeY(i);
            if (y < -dp(60) || y > H + dp(60)) {
                continue;
            }
            boolean locked = lvl > unlocked;
            float r = dp(30);
            p.setColor(0x44000000);
            canvas.drawCircle(x, y + dp(4), r, p);
            if (locked) {
                p.setColor(Palette.STAR_OFF);
                canvas.drawCircle(x, y, r, p);
                drawLockIcon(canvas, x, y, dp(16));
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
        float spacing = (card.width() - dp(40)) / 4f;
        RectF[] out = new RectF[4];
        for (int i = 0; i < 4; i++) {
            float bx = card.left + dp(20) + spacing * i + spacing / 2f;
            out[i] = new RectF(bx - dp(28), by - dp(28), bx + dp(28), by + dp(28));
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
        drawBubble(canvas, W / 2f - dp(52), gy, dp(24), level.accentColor, 255);
        text(canvas, "Clear " + level.target, W / 2f + dp(40), gy + dp(10), dp(28), Palette.TEXT, true, Paint.Align.CENTER);
        text(canvas, "Moves: " + level.moves + "      Colors: " + level.numColors,
                W / 2f, gy + dp(46), dp(15), Palette.TEXT_DIM, false, Paint.Align.CENTER);

        text(canvas, "BOOSTERS  (tap to buy)", W / 2f, card.top + card.height() * 0.50f, dp(13),
                Palette.TEXT_DIM, true, Paint.Align.CENTER);
        RectF[] slots = pregameBoosterSlots();
        for (int i = 0; i < 4; i++) {
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
        // Current level shown top-centre (matches the design sheet's "LEVEL 48").
        text(canvas, "LEVEL " + selectedLevel, W / 2f, dp(35), dp(17), Palette.TEXT, true, Paint.Align.CENTER);

        // Stat row sits BELOW the coin pill and pause button to avoid overlap.
        float labelY = H * 0.115f;
        float valueY = labelY + dp(32);
        // MOVES (left)
        float mx = W * 0.22f;
        text(canvas, "MOVES", mx, labelY, dp(13), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, Integer.toString(Math.max(0, movesLeft)), mx, valueY, dp(30),
                movesLeft <= 5 ? Palette.RED : Palette.TEXT, true, Paint.Align.CENTER);
        // TARGET (center)
        text(canvas, "GOAL", W * 0.5f, labelY, dp(13), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        drawBubble(canvas, W * 0.5f - dp(36), valueY - dp(8), dp(14), level.accentColor, 255);
        text(canvas, Math.min(collected, target) + "/" + target, W * 0.5f + dp(18), valueY, dp(25),
                collected >= target ? Palette.GREEN : Palette.TEXT, true, Paint.Align.CENTER);
        // SCORE (right, anchored & auto-shrunk so it never overflows)
        text(canvas, "SCORE", W - dp(18), labelY, dp(13), Palette.TEXT_DIM, true, Paint.Align.RIGHT);
        textRight(canvas, comma((int) displayScore), W - dp(18), valueY, dp(24), W * 0.30f, Palette.GOLD);

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
        if (armed != B_NONE) {
            text(canvas, "Tap a bubble to use booster", W / 2f, H - dp(94), dp(14),
                    Palette.COMBO, true, Paint.Align.CENTER);
        }
    }

    private void drawBoard(Canvas canvas) {
        RectF bg = new RectF(boardX - dp(8), boardY - dp(8), boardX + boardW + dp(8), boardY + boardH + dp(8));
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.BOARD_BG);
        canvas.drawRoundRect(bg, dp(16), dp(16), p);

        if (board == null) {
            return;
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
            }
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

    private void drawBoosterBar(Canvas canvas) {
        RectF[] slots = boosterBar();
        for (int i = 0; i < 4; i++) {
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

        drawCoin(canvas, W / 2f - dp(40), sy + dp(128), dp(15));
        text(canvas, "+" + resultCoins, W / 2f + dp(4), sy + dp(136), dp(24), Palette.GOLD, true, Paint.Align.CENTER);
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
        text(canvas, "You cleared", W / 2f, card.top + dp(104), dp(16), Palette.TEXT_DIM, false, Paint.Align.CENTER);
        drawBubble(canvas, W / 2f - dp(34), card.top + dp(148), dp(20), level.accentColor, 255);
        text(canvas, collected + " / " + target, W / 2f + dp(26), card.top + dp(156), dp(28), Palette.TEXT, true, Paint.Align.CENTER);

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
        float radius = r.height() / 2.6f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(edge);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(4), r.right, r.bottom + dp(4)), radius, radius, p);
        p.setColor(fill);
        canvas.drawRoundRect(r, radius, radius, p);
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
        return new RectF(dp(16), dp(14), dp(16) + dp(44), dp(14) + dp(44));
    }

    private RectF pregamePlay() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.84f - dp(76), (W + w) / 2f, H * 0.84f - dp(76) + dp(58));
    }

    private RectF[] boosterBar() {
        RectF[] out = new RectF[4];
        float size = dp(54);
        float gap = (W - dp(40) - size * 4) / 3f;
        float y = H - dp(86);
        for (int i = 0; i < 4; i++) {
            float x = dp(20) + i * (size + gap);
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
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (state == State.MAP) {
                        mapScroll = clamp(mapScroll - (e.getY() - lastY), 0, mapScrollMax);
                        lastY = e.getY();
                    }
                    if (Math.abs(e.getX() - downX) > dp(10) || Math.abs(e.getY() - downY) > dp(10)) {
                        movedWhileDown = true;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!movedWhileDown) {
                        handleTap(e.getX(), e.getY());
                    }
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
            boolean on = !store.soundOn();
            store.setSoundOn(on);
            sound.setEnabled(on);
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
        for (int i = 0; i < 4; i++) {
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
            gotoMap();
            return;
        }
        RectF[] slots = boosterBar();
        for (int i = 0; i < 4; i++) {
            if (slots[i].contains(x, y)) {
                tapBooster(i);
                return;
            }
        }
        int[] cell = boardCell(x, y);
        if (cell != null) {
            if (armed != B_NONE) {
                applyBooster(cell[0], cell[1]);
            } else {
                tryPop(cell[0], cell[1]);
            }
        }
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

    /** Right-aligned text that auto-shrinks to fit maxW (keeps large scores on screen). */
    private void textRight(Canvas canvas, String s, float xRight, float y, float maxSize, float maxW, int color) {
        tp.setFakeBoldText(true);
        tp.setTextSize(maxSize);
        float w = tp.measureText(s);
        float size = (w > maxW && w > 0) ? maxSize * maxW / w : maxSize;
        text(canvas, s, xRight, y, size, color, true, Paint.Align.RIGHT);
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
