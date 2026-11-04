package com.medremind.app.notif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.medremind.app.AppPrefs;
import com.medremind.app.MediApp;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.schedule.ScheduleEngine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 闹钟到点：校验药品仍然有效后启动闹钟服务，并安排下一次。 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)) {
            AlarmService.startTest(context);
            return;
        }
        String medId = intent.getStringExtra(AlarmScheduler.EXTRA_MED_ID);
        String slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT);
        long doseAt = intent.getLongExtra(AlarmScheduler.EXTRA_DOSE_AT, 0);
        boolean snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false);
        int lead = intent.getIntExtra(AlarmScheduler.EXTRA_LEAD, 0);
        if (medId == null || slot == null || doseAt <= 0) return;

        AppPrefs prefs = AppPrefs.get(context);
        Med med = prefs.findMed(medId);
        if (med == null || !med.enabled) return;

        LocalDateTime dose = LocalDateTime.ofInstant(Instant.ofEpochMilli(doseAt), ZoneId.systemDefault());
        LocalDate date = dose.toLocalDate();
        if (!snooze && prefs.isTaken(medId, date, slot)) {
            // 已经提前打过勾：本次不再响，但仍要把下一次的闹钟接上，避免漏掉明天。
            AlarmScheduler.scheduleNextAfter(context, med, slot, dose.plusMinutes(1), false);
            return;
        }

        AlarmService.start(context, med, slot, doseAt, snooze, lead);

        if (!snooze) {
            AlarmScheduler.scheduleNextAfter(context, med, slot, dose.plusMinutes(1), false);
        }
        MediApp.log("alarm fired: " + med.name + " " + slot + " snooze=" + snooze);
    }
}