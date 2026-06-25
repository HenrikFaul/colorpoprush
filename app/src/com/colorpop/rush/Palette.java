package com.colorpop.rush;

import android.graphics.Color;

/** Central colour palette and small colour helpers for the whole game. */
public final class Palette {

    private Palette() {}

    /** Vibrant bubble colours, indexed by colour id. */
    public static final int[] BUBBLE = {
            0xFFFF4D6D, // 0 red / coral
            0xFFFF9F1C, // 1 orange
            0xFFFFD23F, // 2 yellow
            0xFF3DDC84, // 3 green
            0xFF4895EF, // 4 blue
            0xFF9B5DE5, // 5 purple
    };

    // Backgrounds — vibrant candy purple-blue (matches the icon), dark enough for white text
    public static final int BG_TOP = 0xFF3D2C9A;
    public static final int BG_BOTTOM = 0xFF6248D8;
    public static final int BOARD_BG = 0x26FFFFFF;

    // Panels / cards
    public static final int CARD = 0xFF2E2160;
    public static final int CARD_EDGE = 0xFF20163F;
    public static final int OVERLAY = 0xCC0E0930;
    public static final int SLOT = 0x33FFFFFF;

    // Buttons
    public static final int GREEN = 0xFF5BC236;
    public static final int GREEN_DK = 0xFF3F9D22;
    public static final int BLUE = 0xFF3E8EF7;
    public static final int BLUE_DK = 0xFF2A6FD0;
    public static final int RED = 0xFFF25C5C;
    public static final int RED_DK = 0xFFC93F3F;
    public static final int PURPLE = 0xFF9B5DE5;
    public static final int PURPLE_DK = 0xFF7A3FC0;

    // Accents
    public static final int GOLD = 0xFFFFC83D;
    public static final int GOLD_DK = 0xFFE0A21E;
    public static final int STAR_ON = 0xFFFFD23F;
    public static final int STAR_OFF = 0xFF4A3F7A;
    public static final int COMBO = 0xFFFFE14D;

    // Text
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFB9AEE8;

    /** Per-letter colours for the COLOR POP RUSH wordmark. */
    public static final int[] LOGO = {
            0xFFFF4D6D, 0xFFFF9F1C, 0xFFFFD23F, 0xFF3DDC84, 0xFF4895EF,
            0xFF9B5DE5, 0xFFFF4D6D, 0xFF3DDC84, 0xFF4895EF, 0xFFFFD23F,
            0xFF9B5DE5, 0xFFFF4D6D, 0xFF3DDC84,
    };

    /** Multiply a colour's brightness (keeps alpha). factor<1 darkens, >1 lightens. */
    public static int scale(int color, float factor) {
        int a = Color.alpha(color);
        int r = clamp((int) (Color.red(color) * factor));
        int g = clamp((int) (Color.green(color) * factor));
        int b = clamp((int) (Color.blue(color) * factor));
        return Color.argb(a, r, g, b);
    }

    /** Blend towards white by t (0..1). */
    public static int lighten(int color, float t) {
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * t);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * t);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * t);
        return Color.argb(Color.alpha(color), clamp(r), clamp(g), clamp(b));
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
