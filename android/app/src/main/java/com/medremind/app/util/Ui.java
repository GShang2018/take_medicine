package com.medremind.app.util;

import android.content.Context;
import android.widget.Toast;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Ui {
    private static final String[] WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static void toast(Context context, String message) {
        if (context == null) return;
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    public static String hhmm(LocalTime time) {
        return time.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public static String shortDate(LocalDate date) {
        return String.format(Locale.CHINA, "%d月%d日", date.getMonthValue(), date.getDayOfMonth());
    }

    public static String weekday(LocalDate date) {
        return WEEK[date.getDayOfWeek().getValue() - 1];
    }

    public static String longDate(LocalDate date) {
        return String.format(Locale.CHINA, "%d年%d月%d日", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    public static String dayLabel(LocalDate date) {
        LocalDate today = LocalDate.now();
        long diff = java.time.temporal.ChronoUnit.DAYS.between(today, date);
        if (diff == 0) return "今天";
        if (diff == 1) return "明天";
        if (diff == 2) return "后天";
        return shortDate(date);
    }

    public static String greeting() {
        int hour = LocalTime.now().getHour();
        if (hour < 6) return "夜深了";
        if (hour < 11) return "早上好";
        if (hour < 14) return "中午好";
        if (hour < 18) return "下午好";
        return "晚上好";
    }
}