package com.medremind.app.util;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/**
 * 小米/Redmi、华为、荣耀等国产 ROM 的后台保活入口。
 * 各家设置页没有公开标准 API，这里只做“尽力跳转 + 明确指引”，
 * 跳不过去时回退到应用详情页，绝不假装已经授权。
 */
public final class OemCompat {
    private OemCompat() {
    }

    public static boolean isXiaomi() {
        String s = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return s.contains("xiaomi") || s.contains("redmi") || s.contains("poco");
    }

    public static boolean isHuawei() {
        String s = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return s.contains("huawei");
    }

    public static boolean isHonor() {
        String s = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        return s.contains("honor") || s.contains("hihonor");
    }

    public static boolean isTargetBrand() {
        return isXiaomi() || isHuawei() || isHonor();
    }

    public static String brandLabel() {
        if (isXiaomi()) return "小米 / Redmi";
        if (isHonor()) return "荣耀";
        if (isHuawei()) return "华为";
        return "当前手机";
    }

    public static String setupHint() {
        if (isXiaomi()) return "开启自启动，省电策略设为「无限制」";
        if (isHonor() || isHuawei()) return "应用启动管理改为手动，允许自启动/后台活动";
        return "允许自启动、后台运行和悬浮窗";
    }

    /** 打开厂商自启动/后台管理页；失败返回 false。 */
    public static boolean openAutoStart(Activity activity) {
        if (isXiaomi()) {
            if (launch(activity, "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")) return true;
            if (launchAction(activity, "miui.intent.action.OP_AUTO_START")) return true;
        }
        if (isHonor()) {
            if (launch(activity, "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")) return true;
            if (launch(activity, "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")) return true;
        }
        if (isHuawei() || isHonor()) {
            if (launch(activity, "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")) return true;
            if (launch(activity, "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")) return true;
        }
        return false;
    }

    private static boolean launch(Activity activity, String pkg, String cls) {
        try {
            Intent intent = new Intent().setComponent(new ComponentName(pkg, cls));
            if (activity.getPackageManager().resolveActivity(intent, 0) == null) return false;
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean launchAction(Activity activity, String action) {
        try {
            Intent intent = new Intent(action).addCategory(Intent.CATEGORY_DEFAULT);
            if (activity.getPackageManager().resolveActivity(intent, 0) == null) return false;
            activity.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 展示当前品牌对应的后台保活步骤；至少能跳到应用详情，不会卡死。 */
    public static void showGuide(Activity activity) {
        String message;
        if (isXiaomi()) {
            message = "小米 / Redmi 需要三处设置，缺一不可：\n\n"
                    + "1. 应用设置 → 应用管理 → 吃药提醒 → 自启动：允许\n"
                    + "2. 省电策略：设为「无限制」\n"
                    + "3. 权限 → 显示在其他应用上层（悬浮窗）：允许\n\n"
                    + "如果在最近任务里下拉卡片，也可以给 App 加锁，减少被清理。";
        } else if (isHonor()) {
            message = "荣耀（MagicOS）需要：\n\n"
                    + "1. 设置 → 应用和服务 → 应用启动管理 → 吃药提醒\n"
                    + "2. 关闭「自动管理」，手动打开「自启动」「关联启动」「后台活动」\n"
                    + "3. 电池 → 应用耗电管理 → 吃药提醒 → 允许后台活动\n"
                    + "4. 权限 → 悬浮窗 / 后台弹出界面：允许";
        } else if (isHuawei()) {
            message = "华为（EMUI / HarmonyOS）需要：\n\n"
                    + "1. 设置 → 应用和服务 → 应用启动管理 → 吃药提醒\n"
                    + "2. 关闭「自动管理」，手动打开「自启动」「关联启动」「后台活动」\n"
                    + "3. 电池 → 更多电池设置 → 应用耗电管理 → 吃药提醒 → 允许后台活动\n"
                    + "4. 权限 → 悬浮窗 / 后台弹出界面：允许";
        } else {
            message = "不同系统名称略有不同，请允许：\n\n"
                    + "1. 自启动 / 后台启动\n"
                    + "2. 后台运行 / 忽略电池优化\n"
                    + "3. 显示在其他应用上层（悬浮窗）\n\n"
                    + "设置 → 应用 → 吃药提醒 → 权限，可以逐项检查。";
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("后台保活设置 · " + brandLabel())
                .setMessage(message)
                .setPositiveButton("去自启动设置", (d, w) -> {
                    if (!openAutoStart(activity)) {
                        Perms.openAppDetails(activity);
                    }
                })
                .setNeutralButton("电池设置", (d, w) -> Perms.openBatterySettings(activity))
                .setNegativeButton("关闭", null)
                .show();
    }
}