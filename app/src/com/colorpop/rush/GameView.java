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
 */
public class GameView extends View implements Choreographer.FrameCallback {

    private enum State { MENU, MAP, PREGAME, PLAYING, ANIM, COMPLETE, FAILED, DAILY }

    // Booster ids (match Storage booster ids).
    private static final int B_NONE = -1, B_BOMB = 0, B_RAINBOW = 1, B_HAMMER = 2, B_MOVES = 3;

    private static final float COMBO_WINDOW = 2.2f;
    private static final float POP_DUR = 0.14f;
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
    private float comboTimer;
    private int armed = B_NONE;

    // Animation
    private int animPhase;     // 0 = pop shrink, 1 = fall/refill
    private float animT;
    private final List<int[]> popping = new ArrayList<int[]>();
    private final List<Integer> poppingColors = new ArrayList<Integer>();

    // Effects
    private final List<Effects.Particle> particles = new ArrayList<Effects.Particle>();
    private final List<Effects.FloatingText> floats = new ArrayList<Effects.FloatingText>();

    // Result screen
    private int resultStars, resultCoins, resultBooster = -1;
    private float resultT;

    // Map scrolling
    private float mapScroll, mapScrollMax;

    // Touch tracking
    private float downX, downY, lastY;
    private boolean movedWhileDown;
    private float transFade = 1f; // simple screen fade-in

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
        update(dt);
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        W = w;
        H = h;
        bgPaint = new Paint();
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                Palette.BG_TOP, Palette.BG_BOTTOM, Shader.TileMode.CLAMP));
        layoutBoard();
        initBgBubbles();
    }

    private void layoutBoard() {
        if (level == null) {
            level = new Level(selectedLevel);
        }
        int cols = level.cols, rows = level.rows;
        float availTop = H * 0.205f;
        float availBottom = H * 0.135f;
        float availH = H - availTop - availBottom - dp(12);
        cell = Math.min((W * 0.95f) / cols, availH / rows);
        boardW = cell * cols;
        boardH = cell * rows;
        boardX = (W - boardW) / 2f;
        boardY = availTop + (availH - boardH) / 2f;
        BR = cell * 0.46f;
        bubbleShader = new RadialGradient[Palette.BUBBLE.length];
        for (int i = 0; i < bubbleShader.length; i++) {
            int base = Palette.BUBBLE[i];
            bubbleShader[i] = new RadialGradient(-BR * 0.34f, -BR * 0.36f, BR * 1.35f,
                    new int[]{Palette.lighten(base, 0.65f), base, Palette.scale(base, 0.8f)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
    }

    private void initBgBubbles() {
        bgBubbles.clear();
        int count = 16;
        for (int i = 0; i < count; i++) {
            float r = dp(8) + rng.nextFloat() * dp(26);
            bgBubbles.add(new float[]{
                    rng.nextFloat() * W,
                    rng.nextFloat() * H,
                    r,
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
        // Background bubbles drift up and wrap.
        for (float[] b : bgBubbles) {
            b[1] += b[3] * dt;
            if (b[1] + b[2] < 0) {
                b[1] = H + b[2];
                b[0] = rng.nextFloat() * W;
            }
        }
        // Particles & floating texts.
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
        // Score counter easing.
        if (Math.abs(displayScore - score) > 0.5f) {
            displayScore += (score - displayScore) * Math.min(1f, dt * 9f);
        } else {
            displayScore = score;
        }
        // Combo decay.
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
            // Commit the clear, collapse the board, switch to the fall phase.
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
        particles.clear();
        floats.clear();
        popping.clear();
        poppingColors.clear();
        state = State.PLAYING;
        transFade = 0f;
        sound.playSwoosh();
    }

    private void win() {
        resultStars = level.starsFor(movesLeft);
        resultCoins = Level.coinReward(score, resultStars);
        store.recordResult(selectedLevel, resultStars);
        store.addCoins(resultCoins);
        resultBooster = -1;
        if (selectedLevel % 5 == 0) {
            resultBooster = rng.nextInt(4);
            store.addBooster(resultBooster, 1);
        }
        resultT = 0;
        state = State.COMPLETE;
        sound.playWin();
        for (int i = 0; i < 40; i++) {
            burst(boardX + rng.nextFloat() * boardW, boardY + boardH * 0.4f,
                    rng.nextInt(Palette.BUBBLE.length), dp(420));
        }
    }

    private void lose() {
        resultT = 0;
        state = State.FAILED;
        sound.playFail();
    }

    /** Attempt to pop the connected group at a board cell. */
    private void tryPop(int r, int c) {
        List<int[]> g = board.group(r, c);
        if (g.size() >= 2) {
            startClear(g, true);
        } else {
            sound.playClick();
            // little nudge of particles for feedback
            burst(colToX(c), rowToY(r), board.colorAt(r, c), dp(120));
        }
    }

    private void applyBooster(int r, int c) {
        List<int[]> cells;
        if (armed == B_BOMB) {
            cells = board.cellsInBlast(r, c, 1);
        } else if (armed == B_RAINBOW) {
            cells = board.cellsOfColor(board.colorAt(r, c));
        } else { // hammer
            cells = new ArrayList<int[]>();
            cells.add(new int[]{r, c});
        }
        if (cells.isEmpty()) {
            return;
        }
        store.addBooster(armed, -1);
        armed = B_NONE;
        startClear(cells, false);
    }

    /** Shared clear+score+animate path for taps and boosters. */
    private void startClear(List<int[]> cells, boolean costsMove) {
        int n = cells.size();
        combo = Math.min(10, combo + 1);
        comboTimer = COMBO_WINDOW;
        int mult = Math.max(1, combo);
        int gained = Level.popScore(Math.max(2, n)) * mult;
        score += gained;
        if (costsMove) {
            movesLeft--;
        }
        // Record positions/colours for the pop animation and tally collection.
        popping.clear();
        poppingColors.clear();
        float cx = 0, cy = 0;
        int collectedNow = 0;
        for (int[] pos : cells) {
            int col = board.colorAt(pos[0], pos[1]);
            popping.add(new int[]{pos[0], pos[1]});
            poppingColors.add(col);
            cx += colToX(pos[1]);
            cy += rowToY(pos[0]);
            if (col == level.targetColor) {
                collectedNow++;
            }
            burst(colToX(pos[1]), rowToY(pos[0]), col, dp(260));
        }
        collected += collectedNow;
        cx /= n;
        cy /= n;
        floats.add(new Effects.FloatingText("+" + gained, cx, cy - BR, dp(20),
                Palette.TEXT, 0.9f, true));
        if (combo >= 2) {
            floats.add(new Effects.FloatingText("COMBO x" + combo, W / 2f, boardY - dp(6),
                    dp(26), Palette.COMBO, 1.0f, true));
        }
        sound.playPop(combo);
        animPhase = 0;
        animT = 0;
        state = State.ANIM;
    }

    private void burst(float x, float y, int colorIdx, float speed) {
        int base = Palette.BUBBLE[Math.max(0, Math.min(Palette.BUBBLE.length - 1, colorIdx))];
        int count = 5;
        for (int i = 0; i < count; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float sp = speed * (0.3f + rng.nextFloat());
            particles.add(new Effects.Particle(x, y,
                    (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - speed * 0.3f,
                    BR * (0.12f + rng.nextFloat() * 0.18f), base, 0.4f + rng.nextFloat() * 0.4f));
        }
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
        if (W == 0) {
            return;
        }
        if (bgPaint != null) {
            canvas.drawRect(0, 0, W, H, bgPaint);
        }
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
        }
        drawParticles(canvas);
        drawFloats(canvas);
        if (transFade < 1f) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.withAlpha(Palette.BG_TOP, (int) (255 * (1f - transFade))));
            canvas.drawRect(0, 0, W, H, p);
        }
    }

    private void drawBgBubbles(Canvas canvas) {
        for (float[] b : bgBubbles) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.withAlpha(Palette.BUBBLE[(int) b[4]], (int) (b[5] * 255)));
            canvas.drawCircle(b[0], b[1], b[2], p);
        }
    }

    // ---- MENU ----------------------------------------------------------

    private void drawMenu(Canvas canvas) {
        drawTopCoins(canvas);
        float cy = H * 0.30f;
        drawLogo(canvas, W / 2f, cy);
        text(canvas, "Pop Colors. Clear Stress.", W / 2f, cy + dp(70), dp(15),
                Palette.TEXT_DIM, false, Paint.Align.CENTER);

        RectF play = menuPlay();
        drawButton(canvas, play, "PLAY", Palette.GREEN, Palette.GREEN_DK, dp(26));

        // Three round buttons.
        RectF daily = menuDaily(), map = menuMap(), snd = menuSound();
        drawRoundIcon(canvas, daily, Palette.GOLD, Palette.GOLD_DK);
        drawGiftIcon(canvas, daily.centerX(), daily.centerY(), dp(16));
        drawRoundIcon(canvas, map, Palette.BLUE, Palette.BLUE_DK);
        drawTrophyIcon(canvas, map.centerX(), map.centerY(), dp(16));
        drawRoundIcon(canvas, snd, Palette.PURPLE, Palette.PURPLE_DK);
        drawSoundIcon(canvas, snd.centerX(), snd.centerY(), dp(15), store.soundOn());

        text(canvas, "LEVEL " + store.unlockedLevel() + "  •  ★ " + store.totalStars(),
                W / 2f, play.top - dp(22), dp(15), Palette.TEXT, true, Paint.Align.CENTER);
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
        // index 0 (level 1) at top; grows downward; minus scroll.
        return dp(120) + index * nodeGap() - mapScroll;
    }

    private float nodeX(int index) {
        return W / 2f + (float) Math.sin(index * 0.9f) * (W * 0.22f);
    }

    private int mapNodeCount() {
        return store.unlockedLevel() + 1;
    }

    private void drawMap(Canvas canvas) {
        int count = mapNodeCount();
        mapScrollMax = Math.max(0, dp(120) + (count - 1) * nodeGap() + dp(120) - H);

        text(canvas, "SELECT LEVEL", W / 2f, dp(46), dp(22), Palette.TEXT, true, Paint.Align.CENTER);

        // Path lines.
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
            // shadow
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
                text(canvas, Integer.toString(lvl), x, y + dp(7), dp(20), Palette.TEXT, true,
                        Paint.Align.CENTER);
                int st = store.stars(lvl);
                for (int s = 0; s < 3; s++) {
                    drawStar(canvas, x - dp(16) + s * dp(16), y + r + dp(12), dp(7),
                            s < st ? Palette.STAR_ON : Palette.STAR_OFF);
                }
            }
        }

        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
        drawTopCoins(canvas);
    }

    // ---- PREGAME -------------------------------------------------------

    private void drawPregame(Canvas canvas) {
        drawTopCoins(canvas);
        RectF card = new RectF(W * 0.1f, H * 0.18f, W * 0.9f, H * 0.82f);
        drawPanel(canvas, card, dp(28));

        text(canvas, "LEVEL " + selectedLevel, W / 2f, card.top + dp(58), dp(34),
                Palette.TEXT, true, Paint.Align.CENTER);

        // Goal.
        text(canvas, "GOAL", W / 2f, card.top + dp(108), dp(15), Palette.TEXT_DIM, true,
                Paint.Align.CENTER);
        float gy = card.top + dp(160);
        drawBubble(canvas, W / 2f - dp(46), gy, dp(26), level.targetColor, 255);
        text(canvas, "x " + level.target, W / 2f + dp(34), gy + dp(11), dp(34),
                Palette.TEXT, true, Paint.Align.CENTER);

        // Moves & colours stats.
        text(canvas, "Moves: " + level.moves + "      Colors: " + level.numColors,
                W / 2f, gy + dp(58), dp(16), Palette.TEXT_DIM, false, Paint.Align.CENTER);

        // Owned boosters row.
        text(canvas, "YOUR BOOSTERS", W / 2f, gy + dp(104), dp(14), Palette.TEXT_DIM, true,
                Paint.Align.CENTER);
        float by = gy + dp(150);
        float spacing = (card.width() - dp(60)) / 4f;
        for (int i = 0; i < 4; i++) {
            float bx = card.left + dp(30) + spacing * i + spacing / 2f;
            RectF slot = new RectF(bx - dp(28), by - dp(28), bx + dp(28), by + dp(28));
            drawBoosterButton(canvas, slot, i, store.booster(i), false);
        }

        RectF play = pregamePlay();
        drawButton(canvas, play, "PLAY", Palette.GREEN, Palette.GREEN_DK, dp(24));
        RectF back = backBtn();
        drawRoundIcon(canvas, back, Palette.CARD, Palette.CARD_EDGE);
        drawBackArrow(canvas, back.centerX(), back.centerY(), dp(13));
    }

    // ---- GAME ----------------------------------------------------------

    private void drawGame(Canvas canvas) {
        // Top HUD.
        float top = dp(14);
        RectF pause = backBtn();
        drawRoundIcon(canvas, pause, Palette.CARD, Palette.CARD_EDGE);
        drawPauseIcon(canvas, pause.centerX(), pause.centerY(), dp(11));
        drawTopCoins(canvas);

        // Stat pills: MOVES (left), TARGET (center), SCORE (right)
        float pillY = H * 0.085f;
        // Moves
        text(canvas, "MOVES", W * 0.16f, pillY, dp(13), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, Integer.toString(Math.max(0, movesLeft)), W * 0.16f, pillY + dp(30), dp(30),
                movesLeft <= 5 ? Palette.RED : Palette.TEXT, true, Paint.Align.CENTER);
        // Target
        text(canvas, "TARGET", W * 0.5f, pillY, dp(13), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        drawBubble(canvas, W * 0.5f - dp(34), pillY + dp(22), dp(15), level.targetColor, 255);
        text(canvas, Math.min(collected, target) + "/" + target, W * 0.5f + dp(20), pillY + dp(30),
                dp(26), collected >= target ? Palette.GREEN : Palette.TEXT, true, Paint.Align.CENTER);
        // Score
        text(canvas, "SCORE", W * 0.84f, pillY, dp(13), Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, comma((int) displayScore), W * 0.84f, pillY + dp(30), dp(24), Palette.GOLD,
                true, Paint.Align.CENTER);

        // Combo meter.
        if (combo >= 2) {
            float frac = Math.max(0f, comboTimer / COMBO_WINDOW);
            float barW = W * 0.5f, bx = (W - barW) / 2f, byy = boardY - dp(16);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33000000);
            canvas.drawRoundRect(new RectF(bx, byy, bx + barW, byy + dp(8)), dp(4), dp(4), p);
            p.setColor(Palette.COMBO);
            canvas.drawRoundRect(new RectF(bx, byy, bx + barW * frac, byy + dp(8)), dp(4), dp(4), p);
            text(canvas, "COMBO x" + combo, W / 2f, byy - dp(6), dp(16), Palette.COMBO, true,
                    Paint.Align.CENTER);
        }

        drawBoard(canvas);
        drawBoosterBar(canvas);

        if (armed != B_NONE) {
            text(canvas, "Tap a bubble to use booster", W / 2f, boardY + boardH + dp(4), dp(14),
                    Palette.COMBO, true, Paint.Align.CENTER);
        }
    }

    private void drawBoard(Canvas canvas) {
        // Board backing panel.
        RectF bg = new RectF(boardX - dp(8), boardY - dp(8),
                boardX + boardW + dp(8), boardY + boardH + dp(8));
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.BOARD_BG);
        canvas.drawRoundRect(bg, dp(16), dp(16), p);

        if (board == null) {
            return;
        }
        boolean fall = (state == State.ANIM && animPhase == 1);
        boolean popPhase = (state == State.ANIM && animPhase == 0);
        float fallP = easeOutCubic(Math.min(1f, animT / FALL_DUR));

        for (int r = 0; r < board.rows; r++) {
            for (int c = 0; c < board.cols; c++) {
                int col = board.colorAt(r, c);
                if (col == Board.EMPTY) {
                    continue;
                }
                float x = colToX(c);
                float y;
                if (fall) {
                    int src = board.srcRowOf(r, c);
                    y = lerp(rowToY(src), rowToY(r), fallP);
                } else {
                    y = rowToY(r);
                }
                drawBoardBubble(canvas, x, y, col, 1f, 255);
            }
        }
        // Popping cells shrinking on top during pop phase.
        if (popPhase) {
            float s = 1f - easeOutCubic(Math.min(1f, animT / POP_DUR));
            for (int i = 0; i < popping.size(); i++) {
                int[] pos = popping.get(i);
                int col = poppingColors.get(i);
                drawBoardBubble(canvas, colToX(pos[1]), rowToY(pos[0]), col, Math.max(0.01f, s),
                        (int) (255 * s));
            }
        }
    }

    private void drawBoosterBar(Canvas canvas) {
        RectF[] slots = boosterBar();
        for (int i = 0; i < 4; i++) {
            drawBoosterButton(canvas, slots[i], i, store.booster(i), armed == i);
        }
    }

    // ---- COMPLETE / FAILED --------------------------------------------

    private void drawComplete(Canvas canvas) {
        dimScreen(canvas);
        RectF card = new RectF(W * 0.12f, H * 0.2f, W * 0.88f, H * 0.8f);
        drawPanel(canvas, card, dp(28));
        text(canvas, "LEVEL " + selectedLevel, W / 2f, card.top + dp(46), dp(20),
                Palette.TEXT_DIM, true, Paint.Align.CENTER);
        text(canvas, "COMPLETE!", W / 2f, card.top + dp(84), dp(34), Palette.GREEN, true,
                Paint.Align.CENTER);

        // Stars reveal.
        float sy = card.top + dp(150);
        for (int i = 0; i < 3; i++) {
            float appear = resultT - 0.25f - i * 0.22f;
            float sc = appear <= 0 ? 0f : Math.min(1f, easeOutBack(Math.min(1f, appear / 0.35f)));
            boolean on = i < resultStars;
            float r = dp(30) * (on ? sc : 1f);
            float x = W / 2f + (i - 1) * dp(70);
            drawStar(canvas, x, sy - (on ? (1 - sc) * dp(20) : 0), Math.max(dp(14), r),
                    on ? Palette.STAR_ON : Palette.STAR_OFF);
        }

        text(canvas, "SCORE", W / 2f, sy + dp(80), dp(15), Palette.TEXT_DIM, true,
                Paint.Align.CENTER);
        text(canvas, comma(score), W / 2f, sy + dp(118), dp(36), Palette.TEXT, true,
                Paint.Align.CENTER);

        drawCoin(canvas, W / 2f - dp(40), sy + dp(158), dp(16));
        text(canvas, "+" + resultCoins, W / 2f + dp(6), sy + dp(166), dp(26), Palette.GOLD, true,
                Paint.Align.CENTER);
        if (resultBooster >= 0) {
            text(canvas, "Bonus booster unlocked!", W / 2f, sy + dp(196), dp(14),
                    Palette.COMBO, true, Paint.Align.CENTER);
        }

        drawButton(canvas, completeNext(), "NEXT", Palette.GREEN, Palette.GREEN_DK, dp(24));
        drawButton(canvas, completeHome(), "MAP", Palette.BLUE, Palette.BLUE_DK, dp(20));
    }

    private void drawFailed(Canvas canvas) {
        dimScreen(canvas);
        RectF card = new RectF(W * 0.12f, H * 0.24f, W * 0.88f, H * 0.76f);
        drawPanel(canvas, card, dp(28));
        text(canvas, "OUT OF MOVES", W / 2f, card.top + dp(70), dp(30), Palette.RED, true,
                Paint.Align.CENTER);
        text(canvas, "You collected", W / 2f, card.top + dp(120), dp(16), Palette.TEXT_DIM, false,
                Paint.Align.CENTER);
        drawBubble(canvas, W / 2f - dp(34), card.top + dp(168), dp(22), level.targetColor, 255);
        text(canvas, collected + " / " + target, W / 2f + dp(28), card.top + dp(176), dp(30),
                Palette.TEXT, true, Paint.Align.CENTER);
        drawButton(canvas, failedRetry(), "RETRY", Palette.GREEN, Palette.GREEN_DK, dp(24));
        drawButton(canvas, failedHome(), "MAP", Palette.BLUE, Palette.BLUE_DK, dp(20));
    }

    // ---- DAILY ---------------------------------------------------------

    private long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    private boolean dailyClaimable() {
        return today() != store.lastClaimDay();
    }

    private int dailyIndex() {
        return store.dailyStreak() % 7;
    }

    private void drawDaily(Canvas canvas) {
        drawTopCoins(canvas);
        RectF card = new RectF(W * 0.08f, H * 0.16f, W * 0.92f, H * 0.84f);
        drawPanel(canvas, card, dp(28));
        text(canvas, "DAILY REWARD", W / 2f, card.top + dp(54), dp(28), Palette.TEXT, true,
                Paint.Align.CENTER);
        text(canvas, "Streak: " + store.dailyStreak() + " days", W / 2f, card.top + dp(86), dp(15),
                Palette.TEXT_DIM, false, Paint.Align.CENTER);

        int cur = dailyIndex();
        boolean claimable = dailyClaimable();
        float gridTop = card.top + dp(120);
        float cellW = (card.width() - dp(60)) / 3f;
        float cellH = dp(86);
        for (int i = 0; i < 7; i++) {
            int row = i / 3, coli = i % 3;
            boolean isBig = (i == 6);
            float x = card.left + dp(30) + (isBig ? cellW : cellW * coli);
            float w = isBig ? cellW * 3 : cellW - dp(10);
            float y = gridTop + row * (cellH + dp(12));
            RectF cellRect = new RectF(x, y, x + (isBig ? cellW : w), y + cellH);
            if (isBig) {
                cellRect = new RectF(card.left + dp(30), y, card.right - dp(30), y + cellH);
            }
            boolean claimed = i < cur;
            boolean current = claimable && i == cur;
            p.setStyle(Paint.Style.FILL);
            p.setColor(current ? Palette.GOLD : (claimed ? 0x3322CC55 : Palette.SLOT));
            canvas.drawRoundRect(cellRect, dp(14), dp(14), p);
            text(canvas, "DAY " + (i + 1), cellRect.centerX(), cellRect.top + dp(20), dp(12),
                    current ? Palette.CARD_EDGE : Palette.TEXT_DIM, true, Paint.Align.CENTER);
            if (claimed) {
                drawCheck(canvas, cellRect.centerX(), cellRect.centerY() + dp(8), dp(14));
            } else {
                drawCoin(canvas, cellRect.centerX() - dp(18), cellRect.centerY() + dp(14), dp(12));
                text(canvas, Integer.toString(DAILY_REWARDS[i]), cellRect.centerX() + dp(6),
                        cellRect.centerY() + dp(20), dp(18),
                        current ? Palette.CARD_EDGE : Palette.TEXT, true, Paint.Align.CENTER);
            }
        }

        RectF claim = dailyClaim();
        if (claimable) {
            drawButton(canvas, claim, "CLAIM", Palette.GREEN, Palette.GREEN_DK, dp(22));
        } else {
            drawButton(canvas, claim, "COME BACK TOMORROW", Palette.STAR_OFF,
                    Palette.scale(Palette.STAR_OFF, 0.8f), dp(15));
        }
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
            p.setColor(Palette.withAlpha(pt.color, (int) (pt.alpha() * 255)));
            canvas.drawCircle(pt.x, pt.y, pt.radius, p);
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
        if (bubbleShader == null) {
            drawBubble(canvas, cx, cy, BR, idx, alpha);
            return;
        }
        canvas.save();
        canvas.translate(cx, cy);
        if (scale != 1f) {
            canvas.scale(scale, scale);
        }
        p.setStyle(Paint.Style.FILL);
        p.setShader(bubbleShader[idx]);
        p.setAlpha(alpha);
        canvas.drawCircle(0, 0, BR, p);
        p.setShader(null);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (alpha * 0.55f)));
        canvas.drawCircle(-BR * 0.3f, -BR * 0.34f, BR * 0.2f, p);
        p.setAlpha(255);
        canvas.restore();
    }

    /** General bubble (creates its own gradient) for UI use. */
    private void drawBubble(Canvas canvas, float cx, float cy, float r, int idx, int alpha) {
        idx = Math.max(0, Math.min(Palette.BUBBLE.length - 1, idx));
        int base = Palette.BUBBLE[idx];
        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx - r * 0.34f, cy - r * 0.36f, r * 1.35f,
                new int[]{Palette.lighten(base, 0.65f), base, Palette.scale(base, 0.8f)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        p.setAlpha(alpha);
        canvas.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, (int) (alpha * 0.55f)));
        canvas.drawCircle(cx - r * 0.3f, cy - r * 0.34f, r * 0.2f, p);
        p.setAlpha(255);
    }

    private void drawPanel(Canvas canvas, RectF r, float radius) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(6), r.right, r.bottom + dp(8)),
                radius, radius, p);
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
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(5), r.right, r.bottom + dp(5)),
                radius, radius, p);
        p.setColor(fill);
        canvas.drawRoundRect(r, radius, radius, p);
        p.setColor(Palette.withAlpha(0xFFFFFFFF, 40));
        canvas.drawRoundRect(new RectF(r.left + dp(6), r.top + dp(5), r.right - dp(6),
                r.top + r.height() * 0.42f), radius * 0.6f, radius * 0.6f, p);
        text(canvas, label, r.centerX(), r.centerY() + textSize * 0.36f, textSize, Palette.TEXT,
                true, Paint.Align.CENTER);
    }

    private void drawRoundIcon(Canvas canvas, RectF r, int fill, int edge) {
        float radius = r.height() / 2.6f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(edge);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(4), r.right, r.bottom + dp(4)),
                radius, radius, p);
        p.setColor(fill);
        canvas.drawRoundRect(r, radius, radius, p);
    }

    private void drawBoosterButton(Canvas canvas, RectF r, int id, int count, boolean armedNow) {
        float radius = dp(16);
        p.setStyle(Paint.Style.FILL);
        if (armedNow) {
            p.setColor(Palette.COMBO);
            canvas.drawRoundRect(new RectF(r.left - dp(3), r.top - dp(3), r.right + dp(3),
                    r.bottom + dp(3)), radius + dp(3), radius + dp(3), p);
        }
        p.setColor(0x55000000);
        canvas.drawRoundRect(new RectF(r.left, r.top + dp(4), r.right, r.bottom + dp(4)),
                radius, radius, p);
        p.setColor(count > 0 ? Palette.CARD : Palette.scale(Palette.CARD, 0.7f));
        canvas.drawRoundRect(r, radius, radius, p);

        float cx = r.centerX(), cy = r.centerY();
        float s = r.height() * 0.3f;
        switch (id) {
            case B_BOMB: drawBombIcon(canvas, cx, cy, s); break;
            case B_RAINBOW: drawRainbowIcon(canvas, cx, cy, s); break;
            case B_HAMMER: drawHammerIcon(canvas, cx, cy, s); break;
            default: drawPlusMovesIcon(canvas, cx, cy, s); break;
        }
        // Count badge.
        float bx = r.right - dp(4), by = r.top + dp(4);
        p.setColor(count > 0 ? Palette.RED : Palette.STAR_OFF);
        canvas.drawCircle(bx, by, dp(11), p);
        text(canvas, Integer.toString(count), bx, by + dp(5), dp(13), Palette.TEXT, true,
                Paint.Align.CENTER);
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
        text(canvas, coins, pill.right - dp(12), cy + dp(6), dp(18), Palette.TEXT, true,
                Paint.Align.RIGHT);
    }

    private void dimScreen(Canvas canvas) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Palette.OVERLAY);
        canvas.drawRect(0, 0, W, H, p);
    }

    // ---- tiny vector icons --------------------------------------------

    private void drawBombIcon(Canvas canvas, float cx, float cy, float s) {
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
            canvas.drawArc(new RectF(cx - rr, cy - rr + s * 0.4f, cx + rr, cy + rr + s * 0.4f),
                    180, 180, false, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawHammerIcon(Canvas canvas, float cx, float cy, float s) {
        canvas.save();
        canvas.rotate(-35, cx, cy);
        p.setColor(0xFFB97A3A);
        canvas.drawRoundRect(new RectF(cx - s * 0.18f, cy - s * 0.1f, cx + s * 0.18f, cy + s * 1.3f),
                dp(2), dp(2), p);
        p.setColor(0xFFB0B6C2);
        canvas.drawRoundRect(new RectF(cx - s * 1.1f, cy - s * 1.1f, cx + s * 1.1f, cy - s * 0.1f),
                dp(3), dp(3), p);
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

    private void drawTrophyIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(Palette.TEXT);
        canvas.drawRoundRect(new RectF(cx - s * 0.7f, cy - s, cx + s * 0.7f, cy + s * 0.2f),
                dp(3), dp(3), p);
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
            canvas.drawArc(new RectF(cx, cy - s * 0.7f, cx + s * 1.3f, cy + s * 0.7f), -50, 100,
                    false, p);
        } else {
            canvas.drawLine(cx + s * 0.6f, cy - s * 0.5f, cx + s * 1.3f, cy + s * 0.5f, p);
            canvas.drawLine(cx + s * 1.3f, cy - s * 0.5f, cx + s * 0.6f, cy + s * 0.5f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawLockIcon(Canvas canvas, float cx, float cy, float s) {
        p.setColor(0xFFCBC4E8);
        canvas.drawRoundRect(new RectF(cx - s * 0.7f, cy - s * 0.1f, cx + s * 0.7f, cy + s * 0.8f),
                dp(3), dp(3), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3));
        canvas.drawArc(new RectF(cx - s * 0.45f, cy - s * 0.7f, cx + s * 0.45f, cy + s * 0.2f),
                180, 180, false, p);
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
        return new RectF((W - w) / 2f, H * 0.58f, (W + w) / 2f, H * 0.58f + dp(66));
    }

    private RectF menuDaily() {
        float y = H * 0.74f, r = dp(34);
        return new RectF(W * 0.5f - dp(110) - r, y - r, W * 0.5f - dp(110) + r, y + r);
    }

    private RectF menuMap() {
        float y = H * 0.74f, r = dp(34);
        return new RectF(W * 0.5f - r, y - r, W * 0.5f + r, y + r);
    }

    private RectF menuSound() {
        float y = H * 0.74f, r = dp(34);
        return new RectF(W * 0.5f + dp(110) - r, y - r, W * 0.5f + dp(110) + r, y + r);
    }

    private RectF backBtn() {
        return new RectF(dp(16), dp(14), dp(16) + dp(44), dp(14) + dp(44));
    }

    private RectF pregamePlay() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.82f - dp(78), (W + w) / 2f, H * 0.82f - dp(78) + dp(60));
    }

    private RectF[] boosterBar() {
        RectF[] out = new RectF[4];
        float size = dp(54);
        float gap = (W - dp(40) - size * 4) / 3f;
        float y = H - dp(78);
        for (int i = 0; i < 4; i++) {
            float x = dp(20) + i * (size + gap);
            out[i] = new RectF(x, y, x + size, y + size);
        }
        return out;
    }

    private RectF completeNext() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.8f - dp(72), (W + w) / 2f, H * 0.8f - dp(72) + dp(58));
    }

    private RectF completeHome() {
        float w = W * 0.34f;
        return new RectF((W - w) / 2f, H * 0.8f - dp(8), (W + w) / 2f, H * 0.8f - dp(8) + dp(44));
    }

    private RectF failedRetry() {
        float w = W * 0.5f;
        return new RectF((W - w) / 2f, H * 0.76f - dp(72), (W + w) / 2f, H * 0.76f - dp(72) + dp(58));
    }

    private RectF failedHome() {
        float w = W * 0.34f;
        return new RectF((W - w) / 2f, H * 0.76f - dp(8), (W + w) / 2f, H * 0.76f - dp(8) + dp(44));
    }

    private RectF dailyClaim() {
        float w = W * 0.6f;
        return new RectF((W - w) / 2f, H * 0.84f - dp(70), (W + w) / 2f, H * 0.84f - dp(70) + dp(56));
    }

    // ===================================================================
    //  Input
    // ===================================================================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastY = downY;
                movedWhileDown = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (state == State.MAP) {
                    float dy = e.getY() - lastY;
                    mapScroll = clamp(mapScroll - dy, 0, mapScrollMax);
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
    }

    private void handleTap(float x, float y) {
        switch (state) {
            case MENU: tapMenu(x, y); break;
            case MAP: tapMap(x, y); break;
            case PREGAME: tapPregame(x, y); break;
            case PLAYING: tapGame(x, y); break;
            case COMPLETE: tapComplete(x, y); break;
            case FAILED: tapFailed(x, y); break;
            case DAILY: tapDaily(x, y); break;
            default: break;
        }
    }

    private void tapMenu(float x, float y) {
        if (menuPlay().contains(x, y)) {
            state = State.MAP;
            mapScroll = mapScrollMax;
            transFade = 0f;
            sound.playClick();
        } else if (menuDaily().contains(x, y)) {
            state = State.DAILY;
            transFade = 0f;
            sound.playClick();
        } else if (menuMap().contains(x, y)) {
            state = State.MAP;
            mapScroll = mapScrollMax;
            transFade = 0f;
            sound.playClick();
        } else if (menuSound().contains(x, y)) {
            boolean on = !store.soundOn();
            store.setSoundOn(on);
            sound.setEnabled(on);
            sound.playClick();
        }
    }

    private void tapMap(float x, float y) {
        if (backBtn().contains(x, y)) {
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
                int lvl = i + 1;
                if (lvl <= unlocked) {
                    startPregame(lvl);
                }
                return;
            }
        }
    }

    private void tapPregame(float x, float y) {
        if (pregamePlay().contains(x, y)) {
            startGame();
        } else if (backBtn().contains(x, y)) {
            state = State.MAP;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void tapGame(float x, float y) {
        if (backBtn().contains(x, y)) {
            state = State.MAP;
            mapScroll = mapScrollMax;
            transFade = 0f;
            sound.playClick();
            return;
        }
        RectF[] slots = boosterBar();
        for (int i = 0; i < 4; i++) {
            if (slots[i].contains(x, y)) {
                tapBooster(i);
                return;
            }
        }
        // Board tap.
        if (board != null && x >= boardX && x < boardX + boardW && y >= boardY && y < boardY + boardH) {
            int c = (int) ((x - boardX) / cell);
            int r = (int) ((y - boardY) / cell);
            if (r < 0 || c < 0 || r >= board.rows || c >= board.cols) {
                return;
            }
            if (board.colorAt(r, c) == Board.EMPTY) {
                return;
            }
            if (armed != B_NONE) {
                applyBooster(r, c);
            } else {
                tryPop(r, c);
            }
        }
    }

    private void tapBooster(int id) {
        if (id == B_MOVES) {
            if (store.booster(B_MOVES) > 0) {
                store.addBooster(B_MOVES, -1);
                movesLeft += 5;
                floats.add(new Effects.FloatingText("+5 MOVES", W * 0.16f, H * 0.085f + dp(60),
                        dp(18), Palette.GREEN, 1.0f, true));
                sound.playCoin();
            } else {
                sound.playClick();
            }
            return;
        }
        if (store.booster(id) > 0) {
            armed = (armed == id) ? B_NONE : id;
            sound.playClick();
        } else {
            sound.playClick();
        }
    }

    private void tapComplete(float x, float y) {
        if (completeNext().contains(x, y)) {
            startPregame(selectedLevel + 1);
        } else if (completeHome().contains(x, y)) {
            state = State.MAP;
            mapScroll = mapScrollMax;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void tapFailed(float x, float y) {
        if (failedRetry().contains(x, y)) {
            startGame();
        } else if (failedHome().contains(x, y)) {
            state = State.MAP;
            mapScroll = mapScrollMax;
            transFade = 0f;
            sound.playClick();
        }
    }

    private void tapDaily(float x, float y) {
        if (dailyClaim().contains(x, y) && dailyClaimable()) {
            int idx = dailyIndex();
            int reward = DAILY_REWARDS[idx];
            store.addCoins(reward);
            long t = today();
            int newStreak = (t == store.lastClaimDay() + 1) ? store.dailyStreak() + 1 : 1;
            store.setDaily(t, newStreak);
            floats.add(new Effects.FloatingText("+" + reward, W / 2f, H * 0.5f, dp(28),
                    Palette.GOLD, 1.2f, true));
            if (idx == 6) {
                store.addBooster(rng.nextInt(4), 2);
            }
            sound.playCoin();
        } else if (backBtn().contains(x, y)) {
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
                state = State.MAP;
                mapScroll = mapScrollMax;
                transFade = 0f;
                return true;
            default:
                state = State.MENU;
                transFade = 0f;
                return true;
        }
    }

    // ===================================================================
    //  Small helpers
    // ===================================================================

    private void text(Canvas canvas, String s, float x, float y, float size, int color,
                      boolean bold, Paint.Align align) {
        tp.setTextSize(size);
        tp.setColor(color);
        tp.setFakeBoldText(bold);
        tp.setTextAlign(align);
        canvas.drawText(s, x, y, tp);
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
