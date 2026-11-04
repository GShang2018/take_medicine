package com.medremind.app.schedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.medremind.app.AppPrefs;
import com.medremind.app.model.Med;
import com.medremind.app.notif.ReminderReceiver;
import com.medremind.app.ui.MainActivity;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 使用 AlarmManager 精确闹钟调度每一次服药提醒。 */
public final class AlarmScheduler {
    public static final String ACTION_ALARM = "com.medremind.app.action.ALARM";
    public static final String EXTRA_MED_ID = "med_id";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_DOSE_AT = "dose_at";
    public static final String EXTRA_LEAD = "lead";
    public static final String EXTRA_SNOOZE = "snooze";
    public static final String EXTRA_TEST = "test";

    private static final int SNOOZE_OFFSET = 1000000;
    private static final int TEST_CODE = 2000001;
    private static final int SHOW_ALARM_CODE = 3000001;

    private AlarmScheduler() {
    }

    public static void rescheduleAll(Context context) {
        cancelAll(context);
        AppPrefs prefs = AppPrefs.get(context);
        List<Med> meds = prefs.getMeds();
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        for (Med med : meds) {
            if (!med.enabled || med.slots == null) continue;
            for (String slot : med.slots) {
                scheduleNextAfter(context, med, slot, now, false);
            }
        }
    }

    public static void scheduleNextAfter(Context context, Med med, String slot, LocalDateTime after, boolean snooze) {
        AppPrefs prefs = AppPrefs.get(context);
        LocalDateTime cursor = after;
        for (int guard = 0; guard < 30; guard++) {
            LocalDateTime dose = ScheduleEngine.nextOccurrence(med, slot, cursor, prefs::slotTime);
            if (dose == null) return;
            if (prefs.isTaken(med.id, dose.toLocalDate(), slot)) {
                cursor = dose.plusMinutes(1);
                continue;
            }
            schedule(context, med, slot, dose, prefs.leadMinutes(), snooze);
            return;
        }
    }

    private static void schedule(Context context, Med med, String slot, LocalDateTime dose,
                                 int leadMinutes, boolean snooze) {
        AppPrefs prefs = AppPrefs.get(context);
        ZoneId zone = ZoneId.systemDefault();
        long doseAt = dose.atZone(zone).toInstant().toEpochMilli();
        long triggerAt = doseAt - leadMinutes * 60_000L;
        long now = System.currentTimeMillis();
        if (triggerAt < now + 1500) {
            // 已经进入提前提醒窗口：允许很快响一次，但不要排到服药时间之后
            if (doseAt <= now) return;
            triggerAt = now + 2000;
        }

        Uri data = Uri.parse((snooze ? "medremind://snooze/" : "medremind://alarm/")
                + Uri.encode(med.id) + "/" + slot);
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_ALARM)
                .setData(data)
                .putExtra(EXTRA_MED_ID, med.id)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_DOSE_AT, doseAt)
                .putExtra(EXTRA_LEAD, leadMinutes)
                .putExtra(EXTRA_SNOOZE, snooze);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(med.id, slot, snooze), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        boolean exact = canScheduleExact(context);
        try {
            // 对服药闹钟使用系统“闹钟”通道：比普通精确闹钟更容易在深度休眠、
            // 后台冻结时按点唤醒，并会在状态栏显示闹钟图标。
            if (!setAlarmClock(context, am, triggerAt, pi)) {
                if (exact) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                    }
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
                }
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        Set<String> keys = prefs.scheduledKeys();
        keys.add(med.id + "|" + slot);
        prefs.setScheduledKeys(keys);
    }

    /** 稍后提醒：以当前时间为基准重新响铃。 */
    public static void scheduleSnooze(Context context, String medId, String slot, long doseAtMillis) {
        AppPrefs prefs = AppPrefs.get(context);
        Med med = prefs.findMed(medId);
        if (med == null) return;
        long triggerAt = System.currentTimeMillis() + prefs.snoozeMinutes() * 60_000L;
        Uri data = Uri.parse("medremind://snooze/" + Uri.encode(medId) + "/" + slot);
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_ALARM)
                .setData(data)
                .putExtra(EXTRA_MED_ID, medId)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_DOSE_AT, doseAtMillis)
                .putExtra(EXTRA_LEAD, 0)
                .putExtra(EXTRA_SNOOZE, true);
        PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(medId, slot, true), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        if (!setAlarmClock(context, am, triggerAt, pi)) {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        }
    }

    public static void scheduleTest(Context context, int seconds) {
        Intent intent = new Intent(context, ReminderReceiver.class)
                .setAction(ACTION_ALARM)
                .setData(Uri.parse("medremind://test"))
                .putExtra(EXTRA_TEST, true);
        PendingIntent pi = PendingIntent.getBroadcast(context, TEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = System.currentTimeMillis() + seconds * 1000L;
        if (!setAlarmClock(context, am, at, pi)) {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        }
    }

    /** 用系统闹钟通道排定；失败时返回 false，由调用方退回普通精确闹钟。 */
    private static boolean setAlarmClock(Context context, AlarmManager am, long triggerAt, PendingIntent pi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            Intent show = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent showPi = PendingIntent.getActivity(context, SHOW_ALARM_CODE, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(triggerAt, showPi), pi);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void cancelAll(Context context) {
        AppPrefs prefs = AppPrefs.get(context);
        for (String key : prefs.scheduledKeys()) {
            int split = key.lastIndexOf('|');
            if (split <= 0) continue;
            cancel(context, key.substring(0, split), key.substring(split + 1));
        }
        prefs.setScheduledKeys(new HashSet<>());
    }

    public static void cancelForMed(Context context, String medId) {
        AppPrefs prefs = AppPrefs.get(context);
        Set<String> keys = prefs.scheduledKeys();
        Set<String> remaining = new HashSet<>();
        for (String key : keys) {
            int split = key.lastIndexOf('|');
            if (split <= 0) continue;
            String id = key.substring(0, split);
            if (id.equals(medId)) {
                cancel(context, id, key.substring(split + 1));
            } else {
                remaining.add(key);
            }
        }
        prefs.setScheduledKeys(remaining);
    }

    /** 取消某个药某个时段、某一天的闹钟（把 pending 全部撤掉后由调度器重排）。 */
    public static void cancelSlot(Context context, String medId, String slot) {
        cancel(context, medId, slot);
        AppPrefs prefs = AppPrefs.get(context);
        Set<String> keys = prefs.scheduledKeys();
        keys.remove(medId + "|" + slot);
        prefs.setScheduledKeys(keys);
    }

    private static void cancel(Context context, String medId, String slot) {
        for (boolean snooze : new boolean[]{false, true}) {
            Uri data = Uri.parse((snooze ? "medremind://snooze/" : "medremind://alarm/")
                    + Uri.encode(medId) + "/" + slot);
            Intent intent = new Intent(context, ReminderReceiver.class).setAction(ACTION_ALARM).setData(data);
            PendingIntent pi = PendingIntent.getBroadcast(context, requestCode(medId, slot, snooze), intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) {
                AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.cancel(pi);
                pi.cancel();
            }
        }
    }

    public static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            return am != null && am.canScheduleExactAlarms();
        }
        return true;
    }

    private static int requestCode(String medId, String slot, boolean snooze) {
        int base = Math.abs((medId + "#" + slot).hashCode()) % 900000 + 1000;
        return snooze ? base + SNOOZE_OFFSET : base;
    }

    /** 用于界面展示即将响铃的时间（含提前量）。 */
    public static LocalDateTime alarmTimeFor(LocalDateTime dose, int leadMinutes) {
        LocalDateTime t = dose.minusMinutes(leadMinutes);
        return t.isBefore(LocalDateTime.now()) ? LocalDateTime.now() : t;
    }
}