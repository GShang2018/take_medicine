package com.medremind.app.schedule;

import com.medremind.app.model.Dose;
import com.medremind.app.model.Med;
import com.medremind.app.util.Slots;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 纯 Java 的排班计算，不依赖 Android，便于单独验证。
 * 每天/每周/每月重复、间隔、按星期、每月几号、结束日期/次数均与网页版语义一致。
 */
public final class ScheduleEngine {
    private ScheduleEngine() {
    }

    public interface TimeSource {
        String time(String slotKey);
    }

    public static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static LocalDate startDate(Med med) {
        return parseDate(med.startDate, LocalDate.now());
    }

    public static LocalDate endDate(Med med) {
        return parseDate(med.endDate, null);
    }

    public static LocalTime timeOf(TimeSource source, String slotKey) {
        return Slots.parseTime(source.time(slotKey), "08:00");
    }

    /** 该日期是否处于重复规则内（不含结束条件）。 */
    private static boolean matchesBase(Med med, LocalDate date) {
        LocalDate start = startDate(med);
        if (date.isBefore(start)) return false;
        int interval = Math.max(1, med.interval);
        switch (med.freqType) {
            case "WEEKLY": {
                LocalDate startMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate dateMonday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                long weeks = ChronoUnit.WEEKS.between(startMonday, dateMonday);
                if (weeks < 0 || weeks % interval != 0) return false;
                if (med.weekDays == null || med.weekDays.isEmpty()) {
                    return date.getDayOfWeek() == start.getDayOfWeek();
                }
                return med.weekDays.contains(date.getDayOfWeek().getValue());
            }
            case "MONTHLY": {
                long months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(date));
                if (months < 0 || months % interval != 0) return false;
                int wanted = med.monthDay <= 0 ? 1 : med.monthDay;
                int effective = Math.min(wanted, date.lengthOfMonth());
                return date.getDayOfMonth() == effective;
            }
            case "DAILY":
            default: {
                long days = ChronoUnit.DAYS.between(start, date);
                return days >= 0 && days % interval == 0;
            }
        }
    }

    /** 从开始日到 date（含）累计出现过几次，最多统计 limit 次。 */
    public static int occurrenceIndex(Med med, LocalDate date) {
        LocalDate start = startDate(med);
        if (date.isBefore(start)) return 0;
        int count = 0;
        LocalDate cursor = start;
        int guard = 0;
        while (!cursor.isAfter(date) && guard++ < 5000) {
            if (matchesBase(med, cursor)) count++;
            cursor = cursor.plusDays(1);
        }
        return count;
    }

    public static boolean occursOn(Med med, LocalDate date) {
        if (!med.enabled) return false;
        if (!matchesBase(med, date)) return false;
        if ("UNTIL".equals(med.endType)) {
            LocalDate end = endDate(med);
            if (end != null && date.isAfter(end)) return false;
        } else if ("COUNT".equals(med.endType)) {
            int idx = occurrenceIndex(med, date);
            if (idx <= 0 || idx > Math.max(1, med.endCount)) return false;
        }
        return true;
    }

    public static List<Dose> dosesOn(Med med, LocalDate date, TimeSource times) {
        List<Dose> result = new ArrayList<>();
        if (!occursOn(med, date)) return result;
        for (String slot : med.slots) {
            LocalTime time = timeOf(times, slot);
            Dose d = new Dose();
            d.medId = med.id;
            d.name = med.name;
            d.dose = med.dose;
            d.note = med.note;
            d.slotKey = slot;
            d.slotLabel = Slots.labelOf(slot);
            d.date = date;
            d.time = time;
            result.add(d);
        }
        result.sort(Comparator.comparing(Dose::dateTime));
        return result;
    }

    /** 某天某个时段里所有需要服用的药品（同一时段可能多种药）。 */
    public static List<Dose> dosesForSlot(List<Med> meds, LocalDate date, String slotKey, TimeSource times) {
        List<Dose> result = new ArrayList<>();
        for (Med med : meds) {
            if (!occursOn(med, date)) continue;
            if (med.slots == null || !med.slots.contains(slotKey)) continue;
            Dose d = new Dose();
            d.medId = med.id;
            d.name = med.name;
            d.dose = med.dose;
            d.note = med.note;
            d.slotKey = slotKey;
            d.slotLabel = Slots.labelOf(slotKey);
            d.date = date;
            d.time = timeOf(times, slotKey);
            result.add(d);
        }
        return result;
    }

    /**
     * 某天所有「钟点相同」的药，不要求属于同一个时段。
     * 例如早饭后和午饭后都被设成 18:30，应该合成一个闹钟一起显示。
     */
    public static List<Dose> dosesAtTime(List<Med> meds, LocalDate date, LocalTime time, TimeSource times) {
        List<Dose> result = new ArrayList<>();
        for (Med med : meds) {
            if (!occursOn(med, date)) continue;
            if (med.slots == null) continue;
            for (Dose dose : dosesOn(med, date, times)) {
                if (dose.time.equals(time)) result.add(dose);
            }
        }
        result.sort(Comparator.comparing(Dose::dateTime));
        return result;
    }

    /** 从 from 起 days 天内的所有服药事件（含当天已过去的）。 */
    public static List<Dose> range(List<Med> meds, LocalDate from, int days, TimeSource times) {
        List<Dose> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate date = from.plusDays(i);
            for (Med med : meds) {
                result.addAll(dosesOn(med, date, times));
            }
        }
        result.sort(Comparator.comparing(Dose::dateTime));
        return result;
    }

    /** 严格晚于 after 的下一次服药时间。 */
    public static Dose nextDose(List<Med> meds, LocalDateTime after, TimeSource times) {
        Dose best = null;
        for (Med med : meds) {
            if (!med.enabled) continue;
            for (String slot : med.slots) {
                LocalDateTime at = nextOccurrence(med, slot, after, times);
                if (at == null) continue;
                if (best == null || at.isBefore(best.dateTime())) {
                    Dose d = new Dose();
                    d.medId = med.id;
                    d.name = med.name;
                    d.dose = med.dose;
                    d.note = med.note;
                    d.slotKey = slot;
                    d.slotLabel = Slots.labelOf(slot);
                    d.date = at.toLocalDate();
                    d.time = at.toLocalTime();
                    best = d;
                }
            }
        }
        return best;
    }

    /** 某个时段严格晚于 after 的下一次服药时间。 */
    public static LocalDateTime nextOccurrence(Med med, String slotKey, LocalDateTime after, TimeSource times) {
        if (!med.enabled) return null;
        LocalTime time = timeOf(times, slotKey);
        LocalDate date = after.toLocalDate();
        for (int i = 0; i < 800; i++) {
            LocalDate current = date.plusDays(i);
            if (!occursOn(med, current)) continue;
            LocalDateTime at = LocalDateTime.of(current, time);
            if (at.isAfter(after)) return at;
        }
        return null;
    }

    /** 用于月历预览时判断某天有没有服药。 */
    public static boolean hasAnyDose(Med med, LocalDate date) {
        return occursOn(med, date) && med.slots != null && !med.slots.isEmpty();
    }
}