package com.colorpop.rush;

/**
 * Procedurally describes a single level. The formulas give a smooth difficulty
 * ramp over an effectively unbounded number of levels ("thousands of levels"),
 * tuned so the values roughly match the design sheet (e.g. level 48 ~ 25 moves,
 * target ~40). Pure Java: no Android dependencies.
 */
public class Level {

    public final int number;     // 1-based level index
    public final int cols;
    public final int rows;
    public final int numColors;
    public final int moves;      // taps that result in a pop
    public final int target;     // bubbles of targetColor to collect
    public final int targetColor;

    public Level(int number) {
        this.number = Math.max(1, number);
        int n = this.number;

        this.cols = 8;
        this.rows = 10;

        // Colours grow from 4 (gentle intro) up to 6 (harder to form big groups).
        int colors = 4 + (n - 1) / 14;
        this.numColors = Math.min(6, Math.max(4, colors));

        // Fewer moves as levels progress, floored so it stays beatable.
        int mv = 30 - n / 8;
        this.moves = clamp(mv, 16, 30);

        // Collection target grows steadily (~42 around level 48).
        this.target = 18 + (int) Math.round(n * 0.5);

        // Rotate the collectable colour so it varies level to level.
        this.targetColor = n % this.numColors;
    }

    /** Points awarded for popping a group of {@code n} bubbles (before combo). */
    public static int popScore(int n) {
        if (n < 2) {
            return 0;
        }
        // Quadratic-ish growth rewards big groups: 2->10, 3->30, 5->100, 10->450.
        return n * (n - 1) * 5;
    }

    /** Coins earned for finishing a level, scaling with score and stars. */
    public static int coinReward(int score, int stars) {
        return score / 60 + stars * 30;
    }

    /**
     * Stars earned: one for completing, plus efficiency bonuses for finishing
     * with moves to spare.
     */
    public int starsFor(int movesLeft) {
        int stars = 1;
        if (movesLeft >= Math.ceil(moves * 0.15)) {
            stars++;
        }
        if (movesLeft >= Math.ceil(moves * 0.40)) {
            stars++;
        }
        return Math.min(3, stars);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
