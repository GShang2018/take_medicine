package com.medremind.app.util;

import com.medremind.app.AppPrefs;
import com.medremind.app.model.Med;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 把药品方案格式化成界面上的中文摘要。 */
public final class MedFormat {
    private static final String[] WEEK = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private MedFormat() {
    }

    public static String slots(Med med) {
        if (med.slots == null || med.slots.isEmpty()) return "未设置时间";
        List<String> labels = new ArrayList<>();
        for (String slot : med.slots) labels.add(Slots.labelOf(slot));
        return String.join("、", labels);
    }

    public static String frequency(Med med) {
        int interval = Math.max(1, med.interval);
        if ("WEEKLY".equals(med.freqType)) {
            StringBuilder sb = new StringBuilder(interval == 1 ? "每周" : "每" + interval + "周");
            if (med.weekDays != null && !med.weekDays.isEmpty()) {
                sb.append(" ");
                for (int i = 0; i < med.weekDays.size(); i++) {
                    int day = med.weekDays.get(i);
                    if (day >= 1 && day <= 7) {
                        if (i > 0) sb.append("、");
                        sb.append(WEEK[day - 1]);
                    }
                }
            }
            return sb.toString();
        }
        if ("MONTHLY".equals(med.freqType)) {
            return (interval == 1 ? "每月" : "每" + interval + "月") + " " + Math.max(1, med.monthDay) + " 号";
        }
        return interval == 1 ? "每天" : "每 " + interval + " 天";
    }

    public static String end(Med med) {
        if ("UNTIL".equals(med.endType)) {
            LocalDate date = com.medremind.app.schedule.ScheduleEngine.parseDate(med.endDate, null);
            if (date != null) return "至 " + Ui.shortDate(date);
            return "长期";
        }
        if ("COUNT".equals(med.endType)) {
            return "共 " + Math.max(1, med.endCount) + " 次";
        }
        return "长期";
    }

    public static String full(Med med) {
        return slots(med) + " · " + frequency(med) + " · " + end(med);
    }

    public static String times(Med med, AppPrefs prefs) {
        List<String> parts = new ArrayList<>();
        if (med.slots != null) {
            for (String slot : med.slots) {
                parts.add(prefs.slotTime(slot));
            }
        }
        return String.join(" / ", parts);
    }
}