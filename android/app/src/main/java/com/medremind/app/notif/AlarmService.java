package com.medremind.app.notif;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.model.Account;
import com.medremind.app.model.Dose;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.schedule.ScheduleEngine;
import com.medremind.app.ui.AlarmActivity;
import com.medremind.app.ui.MainActivity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 前台闹钟服务：循环播放闹铃、震动并展示全屏提醒。 */
public class AlarmService extends Service {
    public static final String EXTRA_MED_ID = "med_id";
    public static final String EXTRA_ACCOUNT = "account_id";
    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_DOSE_AT = "dose_at";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_DOSE = "dose_text";
    public static final String EXTRA_NOTE = "note";
    public static final String EXTRA_SLOT_LABEL = "slot_label";
    public static final String EXTRA_TIME_LABEL = "time_label";
    public static final String EXTRA_GROUP_DATE = "group_date";
    public static final String EXTRA_GROUP_TIME = "group_time";
    public static final String EXTRA_LEAD = "lead";
    public static final String EXTRA_SNOOZE_RING = "snooze_ring";
    public static final String EXTRA_TEST = "test";
    public static final String EXTRA_FROM_ALARM = "from_alarm";

    private static final int NOTIFICATION_ID = 7301;
    private static final long TIMEOUT_MS = 10 * 60 * 1000L;

    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::stopSelfCleanly;

    public static void start(Context context, Med med, String slot, long doseAt, boolean snooze, int lead) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.putExtra(EXTRA_MED_ID, med.id);
        intent.putExtra(EXTRA_ACCOUNT, AppPrefs.accountIdOf(med));
        intent.putExtra(EXTRA_SLOT, slot);
        intent.putExtra(EXTRA_DOSE_AT, doseAt);
        intent.putExtra(EXTRA_NAME, med.name);
        intent.putExtra(EXTRA_DOSE, med.dose);
        intent.putExtra(EXTRA_NOTE, med.note);
        intent.putExtra(EXTRA_SLOT_LABEL, com.medremind.app.util.Slots.labelOf(slot));
        intent.putExtra(EXTRA_LEAD, lead);
        intent.putExtra(EXTRA_SNOOZE_RING, snooze);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            // 少数 ROM 会拦截后台 FGS；精确闹钟广播本身有后台启动豁免，退回普通 startService
            // 仍可在 onStartCommand 里 startForeground，避免整次提醒被吞掉。
            com.medremind.app.MediApp.log("startForegroundService failed: " + e);
            try {
                context.startService(intent);
            } catch (Exception ignored) {
                com.medremind.app.MediApp.log("startService failed: " + ignored);
            }
        }
    }

    public static void startTest(Context context) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.putExtra(EXTRA_TEST, true);
        intent.putExtra(EXTRA_NAME, "测试药品");
        intent.putExtra(EXTRA_DOSE, "1 粒");
        intent.putExtra(EXTRA_NOTE, "这是一次测试提醒，用来确认闹钟、通知与全屏页面是否正常。");
        intent.putExtra(EXTRA_SLOT_LABEL, "测试提醒");
        intent.putExtra(EXTRA_TIME_LABEL, LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        intent.putExtra(EXTRA_DOSE_AT, System.currentTimeMillis());
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (Exception e) {
            // 少数 ROM 会拦截后台 FGS；精确闹钟广播本身有后台启动豁免，退回普通 startService
            // 仍可在 onStartCommand 里 startForeground，避免整次提醒被吞掉。
            com.medremind.app.MediApp.log("startForegroundService failed: " + e);
            try {
                context.startService(intent);
            } catch (Exception ignored) {
                com.medremind.app.MediApp.log("startService failed: " + ignored);
            }
        }
    }

    /** 由通知操作或闹钟页面调用：关闭通知、停止声音与服务。 */
    public static void stopAlarmUi(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        context.stopService(new Intent(context, AlarmService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelfCleanly();
            return START_NOT_STICKY;
        }
        boolean test = intent.getBooleanExtra(EXTRA_TEST, false);
        String accountId = intent.getStringExtra(EXTRA_ACCOUNT);
        String slot = intent.getStringExtra(EXTRA_SLOT);
        String name = value(intent.getStringExtra(EXTRA_NAME), test ? "测试提醒" : "服药提醒");
        String doseText = value(intent.getStringExtra(EXTRA_DOSE), "按医嘱");
        String note = value(intent.getStringExtra(EXTRA_NOTE), "");
        String slotLabel = value(intent.getStringExtra(EXTRA_SLOT_LABEL), "服药");
        String timeLabel = value(intent.getStringExtra(EXTRA_TIME_LABEL), "");
        long doseAt = intent.getLongExtra(EXTRA_DOSE_AT, System.currentTimeMillis());
        int lead = intent.getIntExtra(EXTRA_LEAD, 0);
        boolean snooze = intent.getBooleanExtra(EXTRA_SNOOZE_RING, false);

        List<Dose> doses = new ArrayList<>();
        AppPrefs prefs = AppPrefs.get(this);
        java.util.Map<String, String> medAccountNames = new java.util.HashMap<>();
        for (Med med : prefs.getMeds()) {
            Account account = prefs.findAccount(AppPrefs.accountIdOf(med));
            medAccountNames.put(med.id, account == null ? "" : account.name);
        }
        LocalTime groupTime = null;
        LocalDate groupDate = null;
        if (!test && slot != null && doseAt > 0) {
            LocalDate date = Instant.ofEpochMilli(doseAt).atZone(ZoneId.systemDefault()).toLocalDate();
            groupDate = date;
            groupTime = ScheduleEngine.timeOf(prefs::slotTime, slot);
            // 同一钟点的药一起响，不再局限于同一个“早/中/晚”时段：
            // 不同时段被设成同一个时间、或多个账户同时间吃药，都合并成一个闹钟。
            for (Dose d : ScheduleEngine.dosesAtTime(prefs.getMeds(), date, groupTime, prefs::slotTime)) {
                if (!prefs.isTaken(d.medId, d.date, d.slotKey)) doses.add(d);
            }
            if (doses.isEmpty()) {
                // 这一时间已经全部打过勾，不再响铃。
                stopSelfCleanly();
                return START_NOT_STICKY;
            }
            // 合并了不同时段时，时段标签也一起显示，避免只写“早饭后”。
            java.util.Set<String> labels = new java.util.LinkedHashSet<>();
            for (Dose d : doses) labels.add(d.slotLabel);
            if (!labels.isEmpty()) slotLabel = String.join(" / ", labels);
            intent.putExtra(EXTRA_SLOT_LABEL, slotLabel);
        }

        if (!test) {
            // 页面和通知一律使用“计划服药时间”。不要把触发时刻、提前提醒时刻，
            // 或 Intent 里可能残留的旧时间当成页面顶部时间。
            if (groupTime != null) {
                timeLabel = com.medremind.app.util.Slots.format(groupTime);
            } else if (slot != null) {
                timeLabel = com.medremind.app.util.Slots.format(
                        com.medremind.app.util.Slots.parseTime(prefs.slotTime(slot), "08:00"));
            } else if (doseAt > 0) {
                LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(doseAt), ZoneId.systemDefault());
                timeLabel = dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            }
        }
        // 让直接 startActivity 与通知里的 PendingIntent 拿到同样的内容。
        intent.putExtra(EXTRA_TIME_LABEL, timeLabel);
        if (groupDate != null) intent.putExtra(EXTRA_GROUP_DATE, groupDate.toString());
        if (groupTime != null) {
            intent.putExtra(EXTRA_GROUP_TIME, com.medremind.app.util.Slots.format(groupTime));
        }
        intent.putExtra(EXTRA_ACCOUNT, accountId);

        String title;
        String contentText;
        String bigText;
        if (test) {
            title = "测试提醒";
            contentText = name + " · " + doseText;
            bigText = name + "  " + doseText
                    + (note.isEmpty() ? "" : "\n" + note);
        } else {
            java.util.Set<String> accountNames = new java.util.LinkedHashSet<>();
            for (Dose d : doses) {
                String accountName = medAccountNames.get(d.medId);
                if (accountName != null && !accountName.isEmpty()) accountNames.add(accountName);
            }
            boolean multipleAccounts = accountNames.size() > 1;
            StringBuilder names = new StringBuilder();
            StringBuilder summary = new StringBuilder();
            if (!timeLabel.isEmpty()) summary.append(timeLabel);
            if (!slotLabel.isEmpty()) {
                summary.append(summary.length() > 0 ? " · " : "").append(slotLabel);
            }
            if (summary.length() > 0) summary.append("\n");
            for (int i = 0; i < doses.size(); i++) {
                Dose d = doses.get(i);
                if (i > 0) {
                    names.append("、");
                    summary.append("\n");
                }
                String prefix = multipleAccounts ? medAccountNames.get(d.medId) + " · " : "";
                names.append(prefix).append(d.name);
                summary.append(prefix).append(d.name).append("  ")
                        .append(d.dose == null || d.dose.isEmpty() ? "按医嘱" : d.dose);
            }
            contentText = (timeLabel.isEmpty() ? "" : timeLabel + " · ")
                    + (slotLabel.isEmpty() ? "" : slotLabel + " · ")
                    + names;
            bigText = summary.toString();
            title = "该吃药了";
        }

        Notification notification = buildNotification(intent, title, contentText, bigText, lead, snooze, test, timeLabel);
        startForeground(NOTIFICATION_ID, notification);
        // 锁屏/息屏时只响铃、点亮屏幕并留在通知栏，不拉起页面；解锁使用中才弹底部卡片。
        if (canShowSheetNow()) showOverCurrentAppIfAllowed(intent);
        wakeScreenForAlarm();

        stopSound();
        playSound();
        vibrate();
        acquireWakeLock();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    /**
     * 只有屏幕亮着且已经解锁时才直接弹出卡片。
     * 锁屏、息屏时保持通知 + 闹铃，不弹页面，避免锁屏上突然出现整页弹窗。
     */
    private boolean canShowSheetNow() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || !pm.isInteractive()) return false;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km == null || !km.isKeyguardLocked();
    }

    /**
     * 息屏时用屏幕唤醒锁把屏幕点亮到锁屏，让用户能看到通知；
     * 不启动任何 Activity，所以不会出现整页弹窗。屏幕亮着时不做任何事。
     */
    @SuppressWarnings("deprecation")
    private void wakeScreenForAlarm() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null || pm.isInteractive()) return;
        try {
            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "MediRemind:alarm-screen");
                screenWakeLock.setReferenceCounted(false);
            }
            if (!screenWakeLock.isHeld()) {
                screenWakeLock.acquire(30_000L);
                com.medremind.app.MediApp.log("alarm: screen woken for lockscreen notification");
            }
        } catch (Exception e) {
            com.medremind.app.MediApp.log("alarm: wake screen failed: " + e);
        }
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        screenWakeLock = null;
    }

    private void showOverCurrentAppIfAllowed(Intent source) {
        if (!canShowSheetNow()) return;
        try {
            Intent intent = new Intent(this, AlarmActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtras(source);
            startActivity(intent);
        } catch (Exception e) {
            com.medremind.app.MediApp.log("show alarm activity failed: " + e);
        }
    }

    private Notification buildNotification(Intent source, String title, String contentText, String bigText,
                                           int lead, boolean snooze, boolean test, String timeLabel) {
        Intent detailIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_FROM_ALARM, true)
                .putExtra(EXTRA_MED_ID, source.getStringExtra(EXTRA_MED_ID))
                .putExtra(EXTRA_ACCOUNT, source.getStringExtra(EXTRA_ACCOUNT))
                .putExtra(EXTRA_SLOT, source.getStringExtra(EXTRA_SLOT))
                .putExtra(EXTRA_DOSE_AT, source.getLongExtra(EXTRA_DOSE_AT, 0))
                .putExtra(EXTRA_GROUP_DATE, source.getStringExtra(EXTRA_GROUP_DATE))
                .putExtra(EXTRA_GROUP_TIME, source.getStringExtra(EXTRA_GROUP_TIME));

        PendingIntent detailPi = PendingIntent.getActivity(this, 10, detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Notifier.CH_ALARM)
                .setSmallIcon(R.drawable.ic_alarm)
                .setColor(ContextCompat.getColor(this, R.color.teal))
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bigText))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(detailPi)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false);

        builder.addAction(0, "查看详情", detailPi);
        if (!test) {
            builder.addAction(0, "稍后提醒 " + AppPrefs.get(this).snoozeMinutes() + " 分钟",
                    actionPendingIntent(AlarmActionReceiver.ACTION_SNOOZE, 2, source));
        }
        return builder.build();
    }

    private PendingIntent actionPendingIntent(String action, int code, Intent source) {
        Intent intent = new Intent(this, AlarmActionReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_MED_ID, source.getStringExtra(EXTRA_MED_ID))
                .putExtra(EXTRA_ACCOUNT, source.getStringExtra(EXTRA_ACCOUNT))
                .putExtra(EXTRA_SLOT, source.getStringExtra(EXTRA_SLOT))
                .putExtra(EXTRA_DOSE_AT, source.getLongExtra(EXTRA_DOSE_AT, 0));
        return PendingIntent.getBroadcast(this, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void playSound() {
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) return;
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, (int) (max * 0.8f)), 0);
            }
            player = new MediaPlayer();
            player.setAudioAttributes(Notifier.alarmAttributes());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            stopSound();
        }
    }

    private void stopSound() {
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }

    private void vibrate() {
        try {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 700, 400, 700};
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
            }
        } catch (Exception ignored) {
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediRemind:alarm");
                wakeLock.acquire(TIMEOUT_MS);
            }
        } catch (Exception ignored) {
        }
    }

    private void stopSelfCleanly() {
        handler.removeCallbacks(timeout);
        stopSound();
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
        releaseScreenWakeLock();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(timeout);
        stopSound();
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        releaseScreenWakeLock();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String value(String s, String fallback) {
        return s == null || s.isEmpty() ? fallback : s;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉应用时，保持闹钟继续响（由通知操作关闭）。
        super.onTaskRemoved(rootIntent);
    }

    @SuppressWarnings("unused")
    private static String format(long millis) {
        return String.format(Locale.CHINA, "%tR", millis);
    }
}