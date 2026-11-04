package com.medremind.app.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.medremind.app.AppPrefs;
import com.medremind.app.calendar.CalendarSync;
import com.medremind.app.calendar.IcsImporter;
import com.medremind.app.model.Account;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** 每个账户各自订阅一份 .ics，互不覆盖；用 WorkManager 按账户定时刷新。 */
public final class SubscriptionSync {
    /** 可选的自动更新间隔（分钟），0 = 手动。 */
    public static final int[] INTERVALS = {0, 15, 60, 240, 720};
    public static final String[] INTERVAL_LABELS = {
            "手动（只在点「立即同步」时更新）", "每 15 分钟", "每 1 小时", "每 4 小时", "每 12 小时"
    };
    public static final String EXTRA_ACCOUNT = "account_id";
    private static final String WORK_PREFIX = "ics_subscription_";
    private static final Set<String> RUNNING = ConcurrentHashMap.newKeySet();

    private SubscriptionSync() {
    }

    public static String intervalLabel(int minutes) {
        if (minutes <= 0) return "手动";
        if (minutes < 60) return minutes + " 分钟";
        return (minutes / 60) + " 小时";
    }

    public interface Callback {
        void onDone(boolean ok, String message);
    }

    /** 在后台线程同步指定账户，回调切回主线程。 */
    public static void syncAsync(Context context, String accountId, Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean ok = true;
            String message;
            try {
                message = sync(app, accountId);
            } catch (Exception e) {
                ok = false;
                message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            boolean finalOk = ok;
            String finalMessage = message;
            new Handler(Looper.getMainLooper()).post(() -> callback.onDone(finalOk, finalMessage));
        }, "medremind-sub-sync").start();
    }

    /** 立即拉取该账户的订阅内容；内容未变化时只更新同步时间。 */
    public static String sync(Context app, String accountId) throws IOException {
        AppPrefs prefs = AppPrefs.get(app);
        Account account = prefs.findAccount(accountId);
        if (account == null) throw new IOException("账户不存在");
        String url = account.subUrl == null ? "" : account.subUrl.trim();
        if (url.isEmpty()) throw new IOException("该账户尚未设置订阅链接");
        if (!RUNNING.add(accountId)) return "已有同步任务在进行";
        try {
            String body = IcsImporter.readUrl(url);
            String hash = sha256(body);
            boolean changed = !hash.equals(account.subHash);
            List<Med> meds = IcsImporter.parse(body);
            if (meds.isEmpty()) throw new IOException("链接里没有解析到药品");
            if (changed) {
                applyPlan(app, accountId, meds);
                updateAccount(app, accountId, a -> {
                    a.subHash = hash;
                    a.subLastError = "";
                    a.subLastSync = System.currentTimeMillis();
                });
            } else {
                updateAccount(app, accountId, a -> {
                    a.subLastError = "";
                    a.subLastSync = System.currentTimeMillis();
                });
            }
            return changed ? "已更新 " + meds.size() + " 种药品" : "内容未变化";
        } catch (IOException e) {
            String message = e.getMessage() == null ? "同步失败" : e.getMessage();
            updateAccount(app, accountId, a -> a.subLastError = message);
            throw e;
        } finally {
            RUNNING.remove(accountId);
        }
    }

    private interface AccountUpdate {
        void apply(Account account);
    }

    private static void updateAccount(Context app, String accountId, AccountUpdate update) {
        AppPrefs prefs = AppPrefs.get(app);
        Account current = prefs.findAccount(accountId);
        if (current == null) return;
        Account copy = current.copy();
        update.apply(copy);
        prefs.upsertAccount(copy);
    }

    /** 用订阅内容替换该账户的药品方案；名称+剂量相同的药保留原 id，已服记录不丢。 */
    private static void applyPlan(Context app, String accountId, List<Med> parsed) {
        AppPrefs prefs = AppPrefs.get(app);
        Map<String, Med> existing = new HashMap<>();
        for (Med med : prefs.getMeds(accountId)) existing.put(med.name + "|" + med.dose, med);
        List<Med> result = new ArrayList<>();
        for (Med med : parsed) {
            Med old = existing.get(med.name + "|" + med.dose);
            if (old != null) {
                med.id = old.id;
                med.createdAt = old.createdAt;
                med.enabled = old.enabled;
            }
            result.add(med);
        }
        prefs.saveMedsForAccount(accountId, result);
        AlarmScheduler.rescheduleAll(app);
        Account account = prefs.findAccount(accountId);
        if (account != null && account.calendarId > 0 && prefs.autoSync()) {
            CalendarSync.autoSyncIfEnabled(app, accountId);
        }
    }

    /** 按账户的链接和间隔注册/更新周期任务；手动模式或未设置链接时取消。 */
    public static void scheduleWork(Context context, String accountId) {
        Context app = context.getApplicationContext();
        AppPrefs prefs = AppPrefs.get(app);
        Account account = prefs.findAccount(accountId);
        WorkManager workManager = WorkManager.getInstance(app);
        String workName = WORK_PREFIX + accountId;
        workManager.cancelUniqueWork(workName);
        if (account == null) return;
        int minutes = account.subInterval;
        String url = account.subUrl == null ? "" : account.subUrl.trim();
        if (minutes < 15 || url.isEmpty()) return;
        Data input = new Data.Builder().putString(EXTRA_ACCOUNT, accountId).build();
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(SubscriptionWorker.class, minutes, TimeUnit.MINUTES)
                        .setInputData(input)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .build();
        workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancelWork(Context context, String accountId) {
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(WORK_PREFIX + accountId);
    }

    /** App 启动或重启后，按所有账户的设置重新注册周期任务。 */
    public static void rescheduleAll(Context context) {
        Context app = context.getApplicationContext();
        // 1.3.x 只有一个不带账户名的任务；升级后清掉，避免空转。
        WorkManager.getInstance(app).cancelUniqueWork("ics_subscription");
        for (Account account : AppPrefs.get(app).getAccounts()) {
            scheduleWork(app, account.id);
        }
    }

    private static String sha256(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("无法计算内容摘要", e);
        }
    }
}