package com.medremind.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.medremind.app.model.Account;
import com.medremind.app.model.Med;
import com.medremind.app.util.Slots;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 全部本地数据与设置，均保存在 SharedPreferences，不联网。 */
public final class AppPrefs {
    public static final String DEFAULT_ACCOUNT_ID = "default";
    private static final String FILE = "medremind";
    private static volatile AppPrefs instance;

    private final SharedPreferences sp;
    private final Gson gson = new Gson();

    private AppPrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static AppPrefs get(Context context) {
        if (instance == null) {
            synchronized (AppPrefs.class) {
                if (instance == null) instance = new AppPrefs(context);
            }
        }
        return instance;
    }

    // ---------------- 账户 ----------------

    /** 所有账户；旧版本只有一份数据时自动迁移成 default 账户。 */
    public List<Account> getAccounts() {
        String json = sp.getString("accounts", null);
        if (json != null && !json.isEmpty()) {
            try {
                Type type = new TypeToken<List<Account>>() {
                }.getType();
                List<Account> list = gson.fromJson(json, type);
                if (list != null && !list.isEmpty()) return list;
            } catch (Exception ignored) {
            }
        }
        return migrateAccounts();
    }

    /** 把旧版的单账户数据搬进 default 账户，只执行一次。 */
    private List<Account> migrateAccounts() {
        Account account = new Account();
        account.id = DEFAULT_ACCOUNT_ID;
        account.name = "我的药";
        account.subUrl = sp.getString("sub_url", "");
        account.subInterval = sp.getInt("sub_interval", 0);
        account.subHash = sp.getString("sub_hash", "");
        account.subLastSync = sp.getLong("sub_last_sync", 0L);
        account.subLastError = sp.getString("sub_last_error", "");
        account.calendarId = sp.getLong("cal_id", -1L);
        account.calendarName = sp.getString("cal_name", "");
        account.calendarCount = sp.getInt("last_sync_count", 0);
        account.calendarLastSync = sp.getLong("last_sync_at", 0L);

        List<Account> list = new ArrayList<>();
        list.add(account);
        saveAccounts(list);
        setActiveAccount(account.id);

        List<Med> meds = readMedsRaw();
        boolean changed = false;
        for (Med med : meds) {
            if (med.accountId == null || med.accountId.isEmpty()) {
                med.accountId = DEFAULT_ACCOUNT_ID;
                changed = true;
            }
        }
        if (changed) writeMedsRaw(meds);
        return list;
    }

    public void saveAccounts(List<Account> accounts) {
        sp.edit().putString("accounts", gson.toJson(accounts)).apply();
    }

    public Account findAccount(String id) {
        if (id == null) return null;
        for (Account account : getAccounts()) {
            if (id.equals(account.id)) return account;
        }
        return null;
    }

    public String activeAccountId() {
        List<Account> accounts = getAccounts();
        String id = sp.getString("active_account", "");
        for (Account account : accounts) {
            if (account.id.equals(id)) return id;
        }
        String first = accounts.get(0).id;
        setActiveAccount(first);
        return first;
    }

    public Account activeAccount() {
        Account account = findAccount(activeAccountId());
        if (account != null) return account;
        return getAccounts().get(0);
    }

    public void setActiveAccount(String id) {
        sp.edit().putString("active_account", id == null ? "" : id).apply();
    }

    /** 新建并切换到新账户。 */
    public Account createAccount(String name) {
        Account account = new Account();
        account.id = UUID.randomUUID().toString();
        account.name = name == null || name.trim().isEmpty() ? "新账户" : name.trim();
        List<Account> accounts = getAccounts();
        accounts.add(account);
        saveAccounts(accounts);
        setActiveAccount(account.id);
        return account;
    }

    public void upsertAccount(Account account) {
        List<Account> accounts = getAccounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id.equals(account.id)) {
                accounts.set(i, account);
                saveAccounts(accounts);
                return;
            }
        }
        accounts.add(account);
        saveAccounts(accounts);
    }

    /** 删除账户及其药品、服药记录；至少保留一个账户。 */
    public boolean deleteAccount(String id) {
        List<Account> accounts = getAccounts();
        if (accounts.size() <= 1) return false;
        List<String> removedIds = new ArrayList<>();
        for (Med med : readMedsRaw()) {
            if (id.equals(accountIdOf(med))) removedIds.add(med.id);
        }
        accounts.removeIf(a -> a.id.equals(id));
        if (accounts.isEmpty()) return false;
        saveAccounts(accounts);

        List<Med> meds = readMedsRaw();
        meds.removeIf(m -> id.equals(accountIdOf(m)));
        writeMedsRaw(meds);

        if (!removedIds.isEmpty()) {
            Set<String> taken = takenSet();
            taken.removeIf(key -> {
                int cut = key.indexOf('|');
                String medId = cut < 0 ? key : key.substring(0, cut);
                return removedIds.contains(medId);
            });
            sp.edit().putStringSet("taken", taken).apply();
        }
        if (id.equals(activeAccountId())) setActiveAccount(accounts.get(0).id);
        return true;
    }

    public static String accountIdOf(Med med) {
        return med == null || med.accountId == null || med.accountId.isEmpty()
                ? DEFAULT_ACCOUNT_ID : med.accountId;
    }

    // ---------------- 药品 ----------------

    private List<Med> readMedsRaw() {
        String json = sp.getString("meds", null);
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type type = new TypeToken<List<Med>>() {
            }.getType();
            List<Med> list = gson.fromJson(json, type);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void writeMedsRaw(List<Med> meds) {
        sp.edit().putString("meds", gson.toJson(meds)).apply();
    }

    public List<Med> getMeds() {
        return readMedsRaw();
    }

    public List<Med> getMeds(String accountId) {
        List<Med> result = new ArrayList<>();
        for (Med med : readMedsRaw()) {
            if (accountIdOf(med).equals(accountId)) result.add(med);
        }
        return result;
    }

    /** 用一份新方案替换该账户的全部药品，其它账户不受影响。 */
    public void saveMedsForAccount(String accountId, List<Med> meds) {
        List<Med> all = readMedsRaw();
        all.removeIf(m -> accountIdOf(m).equals(accountId));
        for (Med med : meds) {
            med.accountId = accountId;
            all.add(med);
        }
        writeMedsRaw(all);
    }

    public void saveMeds(List<Med> meds) {
        writeMedsRaw(meds);
    }

    public Med findMed(String id) {
        if (id == null) return null;
        for (Med m : readMedsRaw()) {
            if (id.equals(m.id)) return m;
        }
        return null;
    }

    public void upsert(Med med) {
        if (med.accountId == null || med.accountId.isEmpty()) med.accountId = activeAccountId();
        List<Med> all = readMedsRaw();
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(med.id)) {
                all.set(i, med);
                found = true;
                break;
            }
        }
        if (!found) all.add(med);
        writeMedsRaw(all);
    }

    public void deleteMed(String id) {
        List<Med> all = readMedsRaw();
        all.removeIf(m -> m.id.equals(id));
        writeMedsRaw(all);
    }

    // ---------------- 时段 ----------------

    public String slotTime(String key) {
        return sp.getString("time_" + key, Slots.defaultTime(key));
    }

    public void setSlotTime(String key, String hm) {
        sp.edit().putString("time_" + key, hm).apply();
    }

    // ---------------- 提醒设置 ----------------

    /** 闹钟提前量（分钟），0 表示准点响铃。 */
    public int leadMinutes() {
        return sp.getInt("lead", 0);
    }

    public void setLeadMinutes(int minutes) {
        sp.edit().putInt("lead", Math.max(0, minutes)).apply();
    }

    public int snoozeMinutes() {
        return sp.getInt("snooze", 10);
    }

    public void setSnoozeMinutes(int minutes) {
        sp.edit().putInt("snooze", Math.max(1, minutes)).apply();
    }

    /** 写入系统日历时提前提醒的分钟数。 */
    public int calendarLead() {
        return sp.getInt("cal_lead", 5);
    }

    public void setCalendarLead(int minutes) {
        sp.edit().putInt("cal_lead", Math.max(0, minutes)).apply();
    }

    // ---------------- 系统日历 ----------------

    public boolean autoSync() {
        return sp.getBoolean("auto_sync", true);
    }

    public void setAutoSync(boolean enabled) {
        sp.edit().putBoolean("auto_sync", enabled).apply();
    }

    // ---------------- 导入来源 ----------------

    public String lastIcsUrl() {
        return sp.getString("last_ics_url", "");
    }

    public void setLastIcsUrl(String url) {
        sp.edit().putString("last_ics_url", url).apply();
    }

    // ---------------- 已服药记录 ----------------

    private Set<String> takenSet() {
        return new HashSet<>(sp.getStringSet("taken", new HashSet<>()));
    }

    public boolean isTaken(String medId, LocalDate date, String slot) {
        return takenSet().contains(medId + "|" + date + "|" + slot);
    }

    public void setTaken(String medId, LocalDate date, String slot, boolean taken) {
        Set<String> set = takenSet();
        String key = medId + "|" + date + "|" + slot;
        if (taken) set.add(key);
        else set.remove(key);
        sp.edit().putStringSet("taken", set).apply();
    }

    public void clearTaken() {
        sp.edit().remove("taken").apply();
    }

    // ---------------- 闹钟调度记录 ----------------

    public Set<String> scheduledKeys() {
        return new HashSet<>(sp.getStringSet("scheduled", new HashSet<>()));
    }

    public void setScheduledKeys(Set<String> keys) {
        sp.edit().putStringSet("scheduled", keys).apply();
    }

    // ---------------- 其它 ----------------

    public boolean firstRun() {
        return sp.getBoolean("first_run", true);
    }

    public void setFirstRunDone() {
        sp.edit().putBoolean("first_run", false).apply();
    }

    public boolean hasAnyMed() {
        return !getMeds().isEmpty();
    }

    public String permState() {
        return sp.getString("perm_state", "");
    }

    public void setPermState(String state) {
        sp.edit().putString("perm_state", state).apply();
    }

    public boolean oemGuideShown() {
        return sp.getBoolean("oem_guide_shown", false);
    }

    public void setOemGuideShown() {
        sp.edit().putBoolean("oem_guide_shown", true).apply();
    }
}