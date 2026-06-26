package com.colorpop.rush;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin wrapper over SharedPreferences holding all persistent player progress. */
public class Storage {

    private static final String FILE = "colorpoprush";

    private final SharedPreferences prefs;

    public Storage(Context ctx) {
        prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- Progress ---------------------------------------------------------

    public int unlockedLevel() {
        return Math.max(1, prefs.getInt("unlocked", 1));
    }

    public void unlock(int level) {
        if (level > unlockedLevel()) {
            prefs.edit().putInt("unlocked", level).apply();
        }
    }

    public int stars(int level) {
        return prefs.getInt("stars_" + level, 0);
    }

    public void setStars(int level, int stars) {
        if (stars > stars(level)) {
            prefs.edit().putInt("stars_" + level, stars).apply();
        }
    }

    public int totalStars() {
        return prefs.getInt("totalStars", 0);
    }

    private void recomputeTotalStars(int level, int oldStars, int newStars) {
        if (newStars > oldStars) {
            prefs.edit().putInt("totalStars", totalStars() + (newStars - oldStars)).apply();
        }
    }

    /** Records a completed level, returns coins awarded. */
    public void recordResult(int level, int stars) {
        int old = stars(level);
        setStars(level, stars);
        recomputeTotalStars(level, old, Math.max(old, stars));
        unlock(level + 1);
    }

    // --- Currency ---------------------------------------------------------

    public int coins() {
        return prefs.getInt("coins", 150);
    }

    public void addCoins(int amount) {
        prefs.edit().putInt("coins", Math.max(0, coins() + amount)).apply();
    }

    public boolean spendCoins(int amount) {
        if (coins() >= amount) {
            addCoins(-amount);
            return true;
        }
        return false;
    }

    // --- Boosters ---------------------------------------------------------
    // ids: 0 bomb, 1 rainbow, 2 hammer, 3 plus-moves

    public int booster(int id) {
        return Math.max(0, prefs.getInt("booster_" + id, id == 3 ? 2 : 1));
    }

    public void addBooster(int id, int delta) {
        prefs.edit().putInt("booster_" + id, Math.max(0, booster(id) + delta)).apply();
    }

    // --- Stats ------------------------------------------------------------

    public int bestScore() {
        return prefs.getInt("best", 0);
    }

    public void submitScore(int score) {
        if (score > bestScore()) {
            prefs.edit().putInt("best", score).apply();
        }
    }

    public int levelBest(int level) {
        return prefs.getInt("best_" + level, 0);
    }

    public void submitLevelScore(int level, int score) {
        if (score > levelBest(level)) {
            prefs.edit().putInt("best_" + level, score).apply();
        }
    }

    // --- Star-milestone chests --------------------------------------------

    private static final int[] CHEST_STARS = {10, 25, 50, 100, 175, 275, 400, 550, 750, 1000};
    private static final int[] CHEST_COINS = {200, 350, 600, 1000, 1600, 2400, 3500, 5000, 7000, 9000};

    public int chestStars(int i) {
        return i < CHEST_STARS.length ? CHEST_STARS[i]
                : CHEST_STARS[CHEST_STARS.length - 1] + (i - CHEST_STARS.length + 1) * 350;
    }

    public int chestCoins(int i) {
        return i < CHEST_COINS.length ? CHEST_COINS[i] : 9000;
    }

    public int claimedChests() {
        return Math.max(0, prefs.getInt("chests", 0));
    }

    public void claimChest() {
        prefs.edit().putInt("chests", claimedChests() + 1).apply();
    }

    /** Index of the next claimable chest if total stars qualify, else -1. */
    public int claimableChest() {
        int c = claimedChests();
        return totalStars() >= chestStars(c) ? c : -1;
    }

    // --- One-time tips ----------------------------------------------------

    public boolean tipSeen(String key) {
        return prefs.getBoolean("tip_" + key, false);
    }

    public void markTipSeen(String key) {
        prefs.edit().putBoolean("tip_" + key, true).apply();
    }

    public int levelsCleared() {
        return Math.max(0, unlockedLevel() - 1);
    }

    /** Wipes all progress (used by the stats screen reset button). */
    public void resetAll() {
        prefs.edit().clear().apply();
    }

    // --- Settings ---------------------------------------------------------

    public boolean soundOn() {
        return prefs.getBoolean("sound", true);
    }

    public void setSoundOn(boolean on) {
        prefs.edit().putBoolean("sound", on).apply();
    }

    public boolean hapticsOn() {
        return prefs.getBoolean("haptics", true);
    }

    public void setHapticsOn(boolean on) {
        prefs.edit().putBoolean("haptics", on).apply();
    }

    /** Colourblind-friendly: draw a distinct symbol on each bubble colour. */
    public boolean symbolsOn() {
        return prefs.getBoolean("symbols", false);
    }

    public void setSymbolsOn(boolean on) {
        prefs.edit().putBoolean("symbols", on).apply();
    }

    // --- Daily reward -----------------------------------------------------

    public long lastClaimDay() {
        return prefs.getLong("dailyDay", -1L);
    }

    public int dailyStreak() {
        return Math.max(0, prefs.getInt("dailyStreak", 0));
    }

    public void setDaily(long day, int streak) {
        prefs.edit().putLong("dailyDay", day).putInt("dailyStreak", streak).apply();
    }
}
