package com.mobilecut.editor;

import java.util.Locale;

public final class TrimMath {
    public static final long MIN_CLIP_MS = 500;
    private TrimMath() { }
    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
    public static String clock(long millis) {
        long sec = Math.max(0, millis) / 1000;
        long h = sec / 3600, m = (sec / 60) % 60, s = sec % 60;
        return h > 0 ? String.format(Locale.US,"%d:%02d:%02d",h,m,s)
                : String.format(Locale.US,"%02d:%02d",m,s);
    }
}
