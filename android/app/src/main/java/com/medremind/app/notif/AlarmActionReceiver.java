package com.medremind.app.notif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.medremind.app.AppPrefs;
import com.medremind.app.model.Dose;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.schedule.ScheduleEngine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/** 处理通知与全屏闹钟页上的“已服用 / 稍后提醒 / 关闭”操作。 */
public class AlarmActionReceiver extends BroadcastReceiver {
    public static final String ACTION_TAKEN = "com.medremind.app.action.TAKEN";
    public static final String ACTION_SNOOZE = "com.medremind.app.action.SNOOZE";
    public static final String ACTION_DISMISS = "com.medremind.app.action.DISMISS";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String medId = intent.getStringExtra(AlarmService.EXTRA_MED_ID);
        String slot = intent.getStringExtra(AlarmService.EXTRA_SLOT);
        long doseAt = intent.getLongExtra(AlarmService.EXTRA_DOSE_AT, 0);

        if (ACTION_TAKEN.equals(action) && slot != null && doseAt > 0) {
            LocalDate date = LocalDate.ofInstant(Instant.ofEpochMilli(doseAt), ZoneId.systemDefault());
            markSlotTaken(context, medId, slot, date);
        } else if (ACTION_SNOOZE.equals(action) && medId != null && slot != null && doseAt > 0) {
            AlarmScheduler.scheduleSnooze(context, medId, slot, doseAt);
        }
        AlarmService.stopAlarmUi(context);
    }

    /** 同一钟点的药通常要一起吃，点一次“已服用”就把这一时间的所有药全部打勾。 */
    private void markSlotTaken(Context context, String medId, String slot, LocalDate date) {
        AppPrefs prefs = AppPrefs.get(context);
        LocalTime groupTime = ScheduleEngine.timeOf(prefs::slotTime, slot);
        List<Dose> doses = ScheduleEngine.dosesAtTime(prefs.getMeds(), date, groupTime, prefs::slotTime);
        if (doses.isEmpty()) {
            if (medId != null) prefs.setTaken(medId, date, slot, true);
        } else {
            for (Dose dose : doses) {
                prefs.setTaken(dose.medId, dose.date, dose.slotKey, true);
            }
        }
        AlarmScheduler.rescheduleAll(context);
    }
}