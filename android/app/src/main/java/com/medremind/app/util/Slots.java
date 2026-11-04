package com.medremind.app.util;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** 四个固定的服药时段。 */
public final class Slots {
    public static final String MORNING = "morning";
    public static final String NOON = "noon";
    public static final String EVENING = "evening";
    public static final String NIGHT = "night";

    public static final String[] KEYS = {MORNING, NOON, EVENING, NIGHT};
    public static final String[] LABELS = {"早饭后", "午饭后", "晚饭后", "晚上/睡前"};
    public static final String[] DEFAULT_TIMES = {"08:00", "12:30", "18:30", "21:00"};

    private Slots() {
    }

    public static int indexOf(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    public static String labelOf(String key) {
        int i = indexOf(key);
        return i >= 0 ? LABELS[i] : "服药";
    }

    public static String defaultTime(String key) {
        int i = indexOf(key);
        return i >= 0 ? DEFAULT_TIMES[i] : "08:00";
    }

    /** 把 "8:05"、"08:05" 之类的字符串解析成 LocalTime，失败返回 08:00。 */
    public static LocalTime parseTime(String hm, String fallback) {
        try {
            String[] parts = hm.split(":");
            int h = Integer.parseInt(parts[0].trim());
            int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (h < 0 || h > 23 || m < 0 || m > 59) throw new NumberFormatException();
            return LocalTime.of(h, m);
        } catch (Exception e) {
            return parseTime(fallback, "08:00");
        }
    }

    public static String format(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** 根据小时推断时段，用于导入旧日历时缺省。 */
    public static String guessKey(int hour) {
        if (hour < 10) return MORNING;
        if (hour < 15) return NOON;
        if (hour < 20) return EVENING;
        return NIGHT;
    }
}