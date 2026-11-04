package com.medremind.app.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.medremind.app.AppPrefs;
import com.medremind.app.model.Account;

/** WorkManager 周期任务：拉取某个账户订阅的 .ics 并更新该账户的方案。 */
public class SubscriptionWorker extends Worker {
    public SubscriptionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        String accountId = getInputData().getString(SubscriptionSync.EXTRA_ACCOUNT);
        if (accountId == null || AppPrefs.get(app).findAccount(accountId) == null) {
            return Result.success();
        }
        try {
            SubscriptionSync.sync(app, accountId);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            AppPrefs prefs = AppPrefs.get(app);
            Account account = prefs.findAccount(accountId);
            if (account != null) {
                Account copy = account.copy();
                copy.subLastError = message;
                prefs.upsertAccount(copy);
            }
            Log.w("MediRemind", "订阅同步失败: " + message);
        }
        return Result.success();
    }
}