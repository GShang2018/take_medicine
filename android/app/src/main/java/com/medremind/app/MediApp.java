package com.medremind.app;

import android.app.Application;
import android.util.Log;

import com.medremind.app.notif.Notifier;
import com.medremind.app.sync.SubscriptionSync;

public class MediApp extends Application {
    private static final String TAG = "MediRemind";

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.createChannels(this);
        try {
            // 触发旧数据迁移，并按账户恢复订阅任务。
            AppPrefs.get(this).getAccounts();
            SubscriptionSync.rescheduleAll(this);
        } catch (Exception e) {
            log("init accounts failed: " + e);
        }
    }

    public static void log(String message) {
        Log.d(TAG, message);
    }
}