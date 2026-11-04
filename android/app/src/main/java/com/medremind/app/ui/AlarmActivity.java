package com.medremind.app.ui;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.medremind.app.AppPrefs;
import com.medremind.app.databinding.ActivityAlarmBinding;
import com.medremind.app.model.Dose;
import com.medremind.app.notif.AlarmActionReceiver;
import com.medremind.app.notif.AlarmService;
import com.medremind.app.schedule.ScheduleEngine;
import com.medremind.app.util.Slots;
import com.medremind.app.util.Ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 到点从底部滑出的提醒卡片。 */
public class AlarmActivity extends AppCompatActivity {
    private ActivityAlarmBinding b;
    private long shownAt;
    private float touchDownY;
    private boolean closing;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownAt = SystemClock.elapsedRealtime();
        b = ActivityAlarmBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) {
            keyguard.requestDismissKeyguard(this, null);
        }

        b.scrim.setOnClickListener(v -> dismissAlarm());
        b.btnDetail.setOnClickListener(v -> openDetails());
        b.btnSnooze.setOnClickListener(v -> snooze());

        // 点通知栏进来时，收通知栏的抬手可能补发一次返回；刚出现的一小段时间内忽略。
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (SystemClock.elapsedRealtime() - shownAt < 1200) return;
                dismissAlarm();
            }
        });

        try {
            populate(getIntent());
        } catch (Throwable e) {
            android.util.Log.e("AlarmActivity", "populate failed", e);
            showFallback();
        }
        animateIn();
    }

    private void animateIn() {
        b.scrim.setAlpha(0f);
        b.sheet.post(() -> {
            b.sheet.setTranslationY(b.sheet.getHeight() + Ui.dp(this, 24));
            b.sheet.animate()
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
            b.scrim.animate().alpha(1f).setDuration(220).start();
        });
    }

    private void showFallback() {
        b.alarmTitle.setText("该吃药了");
        b.alarmMeta.setText("");
        b.alarmSummary.setText("请打开 App 查看本次服药信息");
        b.btnSnooze.setVisibility(View.VISIBLE);
        b.btnSnooze.setText("稍后提醒 " + AppPrefs.get(this).snoozeMinutes() + " 分钟");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        shownAt = SystemClock.elapsedRealtime();
        closing = false;
        setIntent(intent);
        try {
            populate(intent);
            animateIn();
        } catch (Throwable e) {
            android.util.Log.e("AlarmActivity", "populate failed", e);
            showFallback();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev != null) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touchDownY = ev.getY();
            } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                    && !closing
                    && Math.abs(ev.getY() - touchDownY) > Ui.dp(this, 110)
                    && SystemClock.elapsedRealtime() - shownAt > 800) {
                dismissAlarm();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    private void populate(Intent intent) {
        if (intent == null) return;
        boolean test = intent.getBooleanExtra(AlarmService.EXTRA_TEST, false);
        boolean snoozeRing = intent.getBooleanExtra(AlarmService.EXTRA_SNOOZE_RING, false);
        String name = safe(intent.getStringExtra(AlarmService.EXTRA_NAME), "服药提醒");
        String doseText = safe(intent.getStringExtra(AlarmService.EXTRA_DOSE), "按医嘱");
        String note = intent.getStringExtra(AlarmService.EXTRA_NOTE);
        String slotLabel = safe(intent.getStringExtra(AlarmService.EXTRA_SLOT_LABEL), "");
        String slotKey = intent.getStringExtra(AlarmService.EXTRA_SLOT);
        long doseAt = intent.getLongExtra(AlarmService.EXTRA_DOSE_AT, 0);
        int lead = intent.getIntExtra(AlarmService.EXTRA_LEAD, 0);
        String time = safe(intent.getStringExtra(AlarmService.EXTRA_TIME_LABEL), "");

        String groupDateText = intent.getStringExtra(AlarmService.EXTRA_GROUP_DATE);
        String groupTimeText = intent.getStringExtra(AlarmService.EXTRA_GROUP_TIME);
        LocalDate groupDate = parseDate(groupDateText);
        LocalTime groupTime = parseTime(groupTimeText);
        if (groupDate == null && doseAt > 0) {
            groupDate = LocalDate.ofInstant(Instant.ofEpochMilli(doseAt), ZoneId.systemDefault());
        }
        if (groupTime == null && slotKey != null) {
            groupTime = ScheduleEngine.timeOf(AppPrefs.get(this)::slotTime, slotKey);
        }

        AppPrefs prefs = AppPrefs.get(this);
        List<Dose> due = new ArrayList<>();
        java.util.Map<String, String> accountNames = new java.util.HashMap<>();
        for (com.medremind.app.model.Med med : prefs.getMeds()) {
            com.medremind.app.model.Account acc = prefs.findAccount(AppPrefs.accountIdOf(med));
            accountNames.put(med.id, acc == null ? "" : acc.name);
        }
        if (!test && groupDate != null && groupTime != null) {
            for (Dose dose : ScheduleEngine.dosesAtTime(prefs.getMeds(), groupDate, groupTime, prefs::slotTime)) {
                if (!prefs.isTaken(dose.medId, dose.date, dose.slotKey)) due.add(dose);
            }
            java.util.Set<String> labels = new java.util.LinkedHashSet<>();
            for (Dose dose : due) labels.add(dose.slotLabel);
            if (!labels.isEmpty()) slotLabel = String.join(" / ", labels);
        }

        if (!test && groupTime != null) {
            time = Slots.format(groupTime);
        } else if (!test && slotKey != null) {
            time = Slots.format(Slots.parseTime(prefs.slotTime(slotKey), "08:00"));
        }

        b.alarmTitle.setText(test ? "测试提醒" : "该吃药了");
        String meta = time;
        if (!slotLabel.isEmpty()) meta += (meta.isEmpty() ? "" : " · ") + slotLabel;
        if (snoozeRing && !meta.isEmpty()) meta = "稍后提醒 · " + meta;
        else if (lead > 0 && !meta.isEmpty()) meta = "提前 " + lead + " 分钟 · " + meta;
        b.alarmMeta.setText(meta);

        boolean multipleAccounts = false;
        java.util.Set<String> dueAccounts = new java.util.LinkedHashSet<>();
        for (Dose dose : due) {
            String accountName = accountNames.get(dose.medId);
            if (accountName != null && !accountName.isEmpty()) dueAccounts.add(accountName);
        }
        multipleAccounts = dueAccounts.size() > 1;

        StringBuilder summary = new StringBuilder();
        if (due.isEmpty()) {
            summary.append(name).append("  ").append(doseText);
            if (note != null && !note.isEmpty()) summary.append("\n").append(note);
        } else {
            for (int i = 0; i < due.size(); i++) {
                Dose dose = due.get(i);
                if (i > 0) summary.append("\n");
                if (multipleAccounts) {
                    String accountName = accountNames.get(dose.medId);
                    if (accountName != null && !accountName.isEmpty()) summary.append(accountName).append(" · ");
                }
                summary.append(dose.name).append("  ")
                        .append(dose.dose == null || dose.dose.isEmpty() ? "按医嘱" : dose.dose);
            }
        }
        b.alarmSummary.setText(summary.toString());
        b.btnSnooze.setVisibility(test ? View.GONE : View.VISIBLE);
        b.btnSnooze.setText("稍后提醒 " + prefs.snoozeMinutes() + " 分钟");
    }

    private void openDetails() {
        if (closing) return;
        closing = true;
        Intent source = getIntent();
        Intent detail = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmService.EXTRA_FROM_ALARM, true)
                .putExtra(AlarmService.EXTRA_MED_ID, source.getStringExtra(AlarmService.EXTRA_MED_ID))
                .putExtra(AlarmService.EXTRA_ACCOUNT, source.getStringExtra(AlarmService.EXTRA_ACCOUNT))
                .putExtra(AlarmService.EXTRA_SLOT, source.getStringExtra(AlarmService.EXTRA_SLOT))
                .putExtra(AlarmService.EXTRA_DOSE_AT, source.getLongExtra(AlarmService.EXTRA_DOSE_AT, 0))
                .putExtra(AlarmService.EXTRA_GROUP_DATE, source.getStringExtra(AlarmService.EXTRA_GROUP_DATE))
                .putExtra(AlarmService.EXTRA_GROUP_TIME, source.getStringExtra(AlarmService.EXTRA_GROUP_TIME));
        startActivity(detail);
        AlarmService.stopAlarmUi(this);
        finish();
    }

    private void snooze() {
        if (closing) return;
        closing = true;
        Intent intent = new Intent(this, AlarmActionReceiver.class)
                .setAction(AlarmActionReceiver.ACTION_SNOOZE);
        Intent source = getIntent();
        intent.putExtra(AlarmService.EXTRA_MED_ID, source.getStringExtra(AlarmService.EXTRA_MED_ID));
        intent.putExtra(AlarmService.EXTRA_ACCOUNT, source.getStringExtra(AlarmService.EXTRA_ACCOUNT));
        intent.putExtra(AlarmService.EXTRA_SLOT, source.getStringExtra(AlarmService.EXTRA_SLOT));
        intent.putExtra(AlarmService.EXTRA_DOSE_AT, source.getLongExtra(AlarmService.EXTRA_DOSE_AT, 0));
        sendBroadcast(intent);
        finish();
    }

    private void dismissAlarm() {
        if (closing) return;
        closing = true;
        AlarmService.stopAlarmUi(this);
        finish();
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isEmpty() ? null : LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return value == null || value.isEmpty() ? null : LocalTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}