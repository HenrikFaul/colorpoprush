package com.colorpop.rush;

/**
 * Cosmetic per-world theme (background gradient, accent, name). Worlds are bands
 * of 20 levels. Pure Java (no Android) — colours are plain ARGB ints. Purely
 * visual: never affects gameplay or winnability.
 */
public final class World {

    public final int index;     // 0-based
    public final String name;
    public final int bgTop;
    public final int bgBottom;
    public final int accent;

    private static final String[] NAMES = {
            "CANDY COVE", "BERRY BOG", "SUNSET DUNES", "FROST PEAKS", "NEON CITY",
            "JUNGLE DEEP", "CORAL REEF", "VOID NEBULA",
    };
    private static final int[][] THEMES = {
            {0xFF3D2C9A, 0xFF6248D8, 0xFFFFC83D}, // candy purple
            {0xFF5A1E55, 0xFFB23A8E, 0xFFFF6BD6}, // berry magenta
            {0xFF7A2E1E, 0xFFE06A2C, 0xFFFFD23F}, // sunset orange
            {0xFF173A5E, 0xFF2E7FB8, 0xFF8FE3FF}, // frost blue
            {0xFF1B1140, 0xFF3A1E8C, 0xFF3DF0C0}, // neon
            {0xFF143A1E, 0xFF2E8B57, 0xFFB8FF6B}, // jungle green
            {0xFF0E3A4A, 0xFF2AA6B8, 0xFFFFB36B}, // coral teal
            {0xFF120B33, 0xFF3A2A7A, 0xFFB58CFF}, // void
    };

    private World(int index, String name, int bgTop, int bgBottom, int accent) {
        this.index = index;
        this.name = name;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.accent = accent;
    }

    public static int indexFor(int levelNumber) {
        return Math.max(0, (Math.max(1, levelNumber) - 1) / 20);
    }

    public static World forLevel(int levelNumber) {
        int idx = indexFor(levelNumber);
        int t = idx % THEMES.length;
        String name = NAMES[idx % NAMES.length];
        if (idx >= NAMES.length) {
            name = name + " " + (idx / NAMES.length + 1);
        }
        return new World(idx, name, THEMES[t][0], THEMES[t][1], THEMES[t][2]);
    }
}
