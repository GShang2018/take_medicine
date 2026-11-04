package com.medremind.app.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.medremind.app.notif.Notifier;
import com.medremind.app.schedule.AlarmScheduler;

import java.util.ArrayList;
import java.util.List;

/** 权限检查与跳转系统设置。 */
public final class Perms {
    private Perms() {
    }

    public static boolean notifications(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return Notifier.notificationsEnabled(context);
    }

    public static boolean exactAlarm(Context context) {
        return AlarmScheduler.canScheduleExact(context);
    }

    public static boolean fullScreen(Context context) {
        // 兼容旧调用：1.5.6 起锁屏只显示通知，不再依赖全屏提醒权限。
        return canOverlay(context);
    }

    public static boolean battery(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean calendar(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canOverlay(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public static void openOverlaySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    public static List<String> missing(Context context) {
        List<String> result = new ArrayList<>();
        if (!notifications(context)) result.add("通知");
        if (!exactAlarm(context)) result.add("精确闹钟");
        // 解锁使用手机时，后台服务直接拉起提醒卡片必须依赖悬浮窗；
        // 全屏提醒只在锁屏时更可靠，所以这里把悬浮窗作为必须项提示。
        if (!canOverlay(context)) result.add("悬浮窗");
        if (!battery(context)) result.add("后台运行");
        return result;
    }

    public static void openNotificationSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    public static void openExactAlarmSettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + activity.getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        openAppDetails(activity);
    }

    public static void openFullScreenSettings(Activity activity) {
        // 旧版入口保留，统一改为悬浮窗设置。
        openOverlaySettings(activity);
    }

    public static void openBatterySettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return;
        } catch (Exception ignored) {
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            openAppDetails(activity);
        }
    }

    public static void openAppDetails(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
        }
    }
}