package com.medremind.app.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.databinding.ActivityMainBinding;
import com.medremind.app.model.Med;
import com.medremind.app.notif.AlarmService;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.util.OemCompat;
import com.medremind.app.util.Perms;

public class MainActivity extends AppCompatActivity {
    private ActivityMainBinding b;
    private TodayFragment today;
    private MedsFragment meds;
    private SettingsFragment settings;
    private AppPrefs prefs;
    private int currentTab = R.id.nav_today;
    private boolean syncingNav;
    private boolean fullScreenPromptShown;
    private boolean welcomeShowing;
    private boolean openedFromAlarm;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    AlarmScheduler.rescheduleAll(this);
                }
                refreshCurrent();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        prefs = AppPrefs.get(this);

        FragmentManager fm = getSupportFragmentManager();
        today = (TodayFragment) fm.findFragmentByTag("today");
        meds = (MedsFragment) fm.findFragmentByTag("meds");
        settings = (SettingsFragment) fm.findFragmentByTag("settings");
        if (today == null || meds == null || settings == null) {
            today = new TodayFragment();
            meds = new MedsFragment();
            settings = new SettingsFragment();
            fm.beginTransaction()
                    .add(R.id.fragment_container, today, "today")
                    .add(R.id.fragment_container, meds, "meds")
                    .add(R.id.fragment_container, settings, "settings")
                    .hide(meds)
                    .hide(settings)
                    .commitNow();
        }

        b.bottomNav.setOnItemSelectedListener(item -> {
            if (syncingNav) return true;
            selectTab(item.getItemId());
            return true;
        });
        b.fab.setOnClickListener(v -> startActivity(new Intent(this, EditMedActivity.class)));

        int restored = b.bottomNav.getSelectedItemId();
        selectTab(restored == 0 ? R.id.nav_today : restored);

        AlarmScheduler.rescheduleAll(this);
        handleAlarmDetail(getIntent());
        if (prefs.firstRun()) {
            showWelcome();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String state = (Perms.notifications(this) ? "1" : "0")
                + (Perms.exactAlarm(this) ? "1" : "0")
                + (Perms.canOverlay(this) ? "1" : "0");
        if (!state.equals(prefs.permState())) {
            prefs.setPermState(state);
            AlarmScheduler.rescheduleAll(this);
        }
        refreshCurrent();
        maybePromptFullScreen();
        maybePromptOem();
    }

    /** 解锁使用时直接弹卡片需要悬浮窗；锁屏只显示通知，不再强制全屏权限。 */
    private void maybePromptFullScreen() {
        if (fullScreenPromptShown || welcomeShowing || openedFromAlarm || prefs.firstRun() || Perms.canOverlay(this)) return;
        fullScreenPromptShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("让闹钟在解锁使用时自动弹出来")
                .setMessage("锁屏或息屏时只显示通知，不会突然弹出整页卡片。\n\n"
                        + "如果你想在解锁使用手机、或正在用别的应用时也自动弹出底部提醒卡片，"
                        + "需要开启「显示在其他应用上层（悬浮窗）」。不开时，到点只显示通知，点一下也能进入详情页。")
                .setPositiveButton("开启悬浮窗", (d, w) -> Perms.openOverlaySettings(this))
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 小米/荣耀/华为对后台限制不同，首次打开时给一次对应机型的手动白名单指引。 */
    private void maybePromptOem() {
        if (!OemCompat.isTargetBrand() || prefs.oemGuideShown() || welcomeShowing
                || openedFromAlarm || prefs.firstRun() || !Perms.canOverlay(this)) return;
        prefs.setOemGuideShown();
        new MaterialAlertDialogBuilder(this)
                .setTitle("让 " + OemCompat.brandLabel() + " 别清理闹钟")
                .setMessage("系统还需要允许「自启动 / 后台活动 / 后台弹出界面」，"
                        + "否则清理后台后仍可能收不到提醒。\n\n" + OemCompat.setupHint() + "。")
                .setPositiveButton("查看设置步骤", (d, w) -> OemCompat.showGuide(this))
                .setNegativeButton("稍后", null)
                .show();
    }

    public void selectTab(int itemId) {
        if (today == null || meds == null || settings == null) return;
        if (itemId != R.id.nav_meds && itemId != R.id.nav_settings) itemId = R.id.nav_today;
        currentTab = itemId;
        if (b.bottomNav.getSelectedItemId() != itemId) {
            syncingNav = true;
            b.bottomNav.setSelectedItemId(itemId);
            syncingNav = false;
        }
        switchTab(itemId);
    }

    private void switchTab(int itemId) {
        Fragment target;
        if (itemId == R.id.nav_meds) target = meds;
        else if (itemId == R.id.nav_settings) target = settings;
        else target = today;
        getSupportFragmentManager().beginTransaction()
                .hide(today).hide(meds).hide(settings)
                .show(target)
                .commitAllowingStateLoss();
        b.fab.setVisibility(itemId == R.id.nav_meds ? View.VISIBLE : View.GONE);
        refreshCurrent();
    }

    private void refreshCurrent() {
        if (currentTab == R.id.nav_today && today != null) today.refresh();
        else if (currentTab == R.id.nav_meds && meds != null) meds.refresh();
        else if (currentTab == R.id.nav_settings && settings != null) settings.refresh();
    }

    private void showWelcome() {
        prefs.setFirstRunDone();
        welcomeShowing = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("欢迎使用吃药提醒")
                .setMessage("这是一个本地服药闹钟：\n\n"
                        + "⏰ 到点以闹钟方式响铃，支持全屏提醒\n"
                        + "👨‍👩‍👧 可为家人分别建账户，各自的药、订阅和日历互不混淆\n"
                        + "📅 可把服药计划写入系统日历，由系统日历提醒\n"
                        + "🔄 重启手机后自动恢复提醒\n\n"
                        + "药品数据只保存在本机，只有使用「从网址导入」时才会联网。"
                        + "为了准时响铃，请开启通知、精确闹钟和全屏提醒权限。你的用药方案可以在「设置」里一键导入。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去开启通知", (dialog, which) -> askNotificationPermission())
                .setOnDismissListener(dialog -> {
                    welcomeShowing = false;
                    maybePromptFullScreen();
                })
                .show();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (!Perms.notifications(this)) {
            Perms.openNotificationSettings(this);
        } else {
            selectTab(R.id.nav_settings);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAlarmDetail(intent);
    }

    /** 从闹钟卡片/通知点“查看详情”：切到对应账户的今日待服用，并高亮本次提醒的药。 */
    private void handleAlarmDetail(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(AlarmService.EXTRA_FROM_ALARM, false)) return;
        intent.removeExtra(AlarmService.EXTRA_FROM_ALARM);
        openedFromAlarm = true;
        String medId = intent.getStringExtra(AlarmService.EXTRA_MED_ID);
        Med med = medId == null ? null : prefs.findMed(medId);
        if (med != null) {
            prefs.setActiveAccount(AppPrefs.accountIdOf(med));
        }
        String date = intent.getStringExtra(AlarmService.EXTRA_GROUP_DATE);
        String time = intent.getStringExtra(AlarmService.EXTRA_GROUP_TIME);
        selectTab(R.id.nav_today);
        if (today != null) today.focusAlarm(date, time);
        AlarmService.stopAlarmUi(this);
    }
}