package com.colorpop.rush;

/**
 * Procedurally describes a single level. Difficulty ramps smoothly over an
 * effectively unbounded number of levels. Pure Java (JVM-testable).
 *
 * Goal types: CLEAR_ANY (clear N, N&lt;2*moves), COLLECT_COLOR (collect N of the
 * accent colour), REACH_SCORE (reach a score), BREAK (free all locked bubbles).
 * Locked obstacles are introduced from level 8 onward; some non-BREAK levels get
 * a little ambient lock texture.
 */
public class Level {

    public static final int CLEAR_ANY = 0;
    public static final int COLLECT_COLOR = 1;
    public static final int REACH_SCORE = 2;
    public static final int BREAK = 3;

    public final int number;
    public final int cols;
    public final int rows;
    public final int numColors;
    public final int moves;
    public final int goalType;
    public final int target;
    public final int accentColor;

    public final int lockCount; // locked bubbles to seed
    public final int lockMax;   // lock level per locked bubble

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
        int clearAmount = (int) Math.round(this.moves * ramp);

        int avg = clamp(4 - (this.numColors - 4), 3, 4);
        int pace = popScore(avg) / avg;
        this.star2Score = Math.max(120, clearAmount * pace);
        this.star3Score = (int) Math.round(star2Score * 1.6);

        this.accentColor = n % this.numColors;

        boolean obstacles = n >= 8;
        if (n < 4) {
            this.goalType = CLEAR_ANY;
            this.target = clearAmount;
            this.lockCount = 0;
            this.lockMax = 1;
        } else if (obstacles && n % 8 == 0) {
            // BREAK level: objective is to free all locked bubbles.
            this.goalType = BREAK;
            this.lockMax = (n % 16 == 0) ? 2 : 1;
            this.lockCount = clamp(3 + n / 25, 3, Math.min(8, this.moves - 6));
            this.target = this.lockCount * this.lockMax; // total lock levels to remove
        } else if (n % 7 == 0) {
            this.goalType = REACH_SCORE;
            this.target = Math.max(150, (int) Math.round(star2Score * 0.8));
            this.lockCount = obstacles && n % 5 == 0 ? 2 : 0;
            this.lockMax = 1;
        } else if (n % 3 == 0) {
            this.goalType = COLLECT_COLOR;
            this.target = clamp((int) Math.round(this.moves * 0.5), 5, this.moves - 2);
            this.lockCount = obstacles && n % 5 == 0 ? 2 : 0;
            this.lockMax = 1;
        } else {
            this.goalType = CLEAR_ANY;
            this.target = clearAmount;
            this.lockCount = (obstacles && n % 5 == 0) ? 2 : 0;
            this.lockMax = 1;
        }
    }

    /** Points awarded for popping a group of {@code n} bubbles (before combo). */
    public static int popScore(int n) {
        if (n < 2) {
            return 0;
        }
        return n * (n - 1) * 5;
    }

    public static int coinReward(int score, int stars) {
        return score / 60 + stars * 30;
    }

    /** Bonus score per leftover move in the end-of-level sweep. */
    public static int sweepBonus(int leftoverMoves) {
        return Math.max(0, leftoverMoves) * 50;
    }

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

    public boolean isBreakGoal() {
        return goalType == BREAK;
    }

    /** Deterministic per-level seed (splitmix avalanche) so retries are fair/reproducible. */
    public long seed() {
        long s = number * 0x9E3779B97F4A7C15L;
        s ^= (s >>> 29);
        s *= 0xBF58476D1CE4E5B9L;
        s ^= (s >>> 32);
        return s;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clampD(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
