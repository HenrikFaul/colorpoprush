package com.colorpop.rush;

/**
 * Procedurally describes a single level. Difficulty ramps smoothly over an
 * effectively unbounded number of levels ("thousands of levels"). Pure Java.
 *
 * Winnability guarantee: the goal is to clear {@link #target} bubbles (any
 * colour counts). Every pop clears at least 2 bubbles, so the most a player can
 * clear is {@code 2 * moves}. We keep {@code target < 2 * moves} for every
 * level, so every level is always beatable — fixing the original design where a
 * single-colour target became impossible at higher levels.
 */
public class Level {

    public final int number;     // 1-based level index
    public final int cols;
    public final int rows;
    public final int numColors;
    public final int moves;      // taps that result in a pop
    public final int target;     // bubbles to clear
    public final int accentColor; // decorative colour used for the goal icon

    public Level(int number) {
        this.number = Math.max(1, number);
        int n = this.number;

        this.cols = 8;
        this.rows = 10;

        // Colours grow from 4 (gentle intro) up to 6 (harder to form big groups).
        int colors = 4 + (n - 1) / 14;
        this.numColors = Math.min(6, Math.max(4, colors));

        // Moves decrease as levels progress, floored so it stays generous.
        this.moves = clamp(30 - n / 12, 20, 30);

        // Target ramps from ~1.0x to 1.9x of the move budget (always < 2x => winnable).
        // Tuned so e.g. level 48 lands near 40 (matching the design sheet).
        double ramp = clampD(1.0 + n * 0.011, 1.0, 1.9);
        this.target = (int) Math.round(this.moves * ramp);

        // Rotate the accent colour so the goal icon varies level to level.
        this.accentColor = n % this.numColors;
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
     * with moves to spare (rewards big groups and combos, which clear faster).
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

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
