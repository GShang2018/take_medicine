package com.medremind.app.notif;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;

import com.medremind.app.R;

/** 通知渠道与权限检查。 */
public final class Notifier {
    public static final String CH_ALARM = "med_alarm";

    private Notifier() {
    }

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // 1.5.9 移除了旧版「后台闹钟守护」，顺便清掉遗留通知渠道。
        nm.deleteNotificationChannel("med_guard");

        NotificationChannel alarm = new NotificationChannel(CH_ALARM,
                context.getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH);
        alarm.setDescription(context.getString(R.string.alarm_channel_desc));
        alarm.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        // 声音由 AlarmService 循环播放，避免通知只响一声。
        alarm.setSound(null, null);
        alarm.enableVibration(false);
        alarm.setBypassDnd(false);
        alarm.setShowBadge(true);
        nm.createNotificationChannel(alarm);
    }

    public static boolean notificationsEnabled(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        return nm.areNotificationsEnabled();
    }

    static AudioAttributes alarmAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }
}