package com.wubi.ime.theme;

import android.content.Context;
import android.content.SharedPreferences;

public class ThemeManager {

    public enum Theme {
        ORANGE_RED, CYAN_BLUE, VIOLET,
        RED, GREEN, PINK, BROWN, CYAN, DARK
    }

    private static final String PREFS = "wubi_prefs";
    private static final String KEY   = "theme";

    public static Theme load(Context ctx) {
        int idx = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 0);
        Theme[] vals = Theme.values();
        return (idx >= 0 && idx < vals.length) ? vals[idx] : Theme.ORANGE_RED;
    }

    public static void save(Context ctx, Theme t) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
           .edit().putInt(KEY, t.ordinal()).apply();
    }
}
