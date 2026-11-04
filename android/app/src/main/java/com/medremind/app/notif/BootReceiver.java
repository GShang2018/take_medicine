package com.medremind.app.notif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.medremind.app.AppPrefs;
import com.medremind.app.MediApp;
import com.medremind.app.schedule.AlarmScheduler;

/** 开机、升级、改时间后重新排定所有闹钟。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        String action = intent == null ? "" : intent.getAction();
        new Thread(() -> {
            try {
                AlarmScheduler.rescheduleAll(app);
                com.medremind.app.sync.SubscriptionSync.rescheduleAll(app);
                MediApp.log("boot receiver " + action + ": meds=" + AppPrefs.get(app).getMeds().size()
                        + " alarms=" + AppPrefs.get(app).scheduledKeys().size());
            } catch (Throwable t) {
                MediApp.log("boot receiver " + action + " failed: " + t);
            } finally {
                pending.finish();
            }
        }, "medremind-reschedule").start();
    }
}