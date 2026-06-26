package com.colorpop.rush;

/**
 * Procedurally describes a single level. Difficulty ramps smoothly over an
 * effectively unbounded number of levels ("thousands of levels"). Pure Java.
 *
 * Goal types add variety while staying winnable:
 *  - CLEAR_ANY: clear N bubbles (any colour). N &lt; 2*moves, so always beatable.
 *  - COLLECT_COLOR: collect N bubbles of the accent colour (N capped to the move budget).
 *  - REACH_SCORE: reach a modest score within the move budget.
 */
public class Level {

    public static final int CLEAR_ANY = 0;
    public static final int COLLECT_COLOR = 1;
    public static final int REACH_SCORE = 2;

    public final int number;     // 1-based level index
    public final int cols;
    public final int rows;
    public final int numColors;
    public final int moves;      // taps that result in a pop
    public final int goalType;
    public final int target;     // amount for the active goal (bubbles or score)
    public final int accentColor; // colour used for the goal icon / COLLECT_COLOR target

    public final int star2Score;
    public final int star3Score;

    public Level(int number) {
        this.number = Math.max(1, number);
        int n = this.number;

        this.cols = 8;
        this.rows = 10;

        int colors = 4 + (n - 1) / 14;
        this.numColors = Math.min(6, Math.max(4, colors));

        this.moves = clamp(30 - n / 12, 20, 30);

        double ramp = clampD(1.0 + n * 0.011, 1.0, 1.9);
        int clearAmount = (int) Math.round(this.moves * ramp); // < 2*moves => winnable

        // Score thresholds for star ratings, derived from a typical clearing pace.
        int avg = clamp(4 - (this.numColors - 4), 3, 4);
        int pace = popScore(avg) / avg; // ~10..15 points per bubble
        this.star2Score = Math.max(120, clearAmount * pace);
        this.star3Score = (int) Math.round(star2Score * 1.6);

        this.accentColor = n % this.numColors;

        // Rotate the objective for variety; first levels stay simple.
        if (n < 4) {
            this.goalType = CLEAR_ANY;
            this.target = clearAmount;
        } else if (n % 7 == 0) {
            this.goalType = REACH_SCORE;
            this.target = Math.max(150, (int) Math.round(star2Score * 0.8));
        } else if (n % 3 == 0) {
            this.goalType = COLLECT_COLOR;
            this.target = clamp((int) Math.round(this.moves * 0.5), 5, this.moves - 2);
        } else {
            this.goalType = CLEAR_ANY;
            this.target = clearAmount;
        }
    }

    /** Points awarded for popping a group of {@code n} bubbles (before combo). */
    public static int popScore(int n) {
        if (n < 2) {
            return 0;
        }
        return n * (n - 1) * 5;
    }

    /** Coins earned for finishing a level, scaling with score and stars. */
    public static int coinReward(int score, int stars) {
        return score / 60 + stars * 30;
    }

    /** Stars from the final score: completing earns 1, big scores earn 2 or 3. */
    public int starsForScore(int score, boolean completed) {
        if (!completed) {
            return 0;
        }
        int stars = 1;
        if (score >= star2Score) {
            stars++;
        }
        if (score >= star3Score) {
            stars++;
        }
        return Math.min(3, stars);
    }

    public boolean isScoreGoal() {
        return goalType == REACH_SCORE;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
