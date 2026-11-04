package com.medremind.app.ui;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medremind.app.AppPrefs;
import com.medremind.app.calendar.CalendarSync;
import com.medremind.app.model.Account;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.sync.SubscriptionSync;
import com.medremind.app.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** 账户切换、新建、重命名、删除的共用对话框。 */
public final class AccountUi {
    public interface Listener {
        void onAccountsChanged();
    }

    private AccountUi() {
    }

    public static void show(Fragment fragment, Listener listener) {
        if (!fragment.isAdded()) return;
        Context context = fragment.requireContext();
        AppPrefs prefs = AppPrefs.get(context);
        List<Account> accounts = prefs.getAccounts();
        String active = prefs.activeAccountId();
        List<String> items = new ArrayList<>();
        for (Account account : accounts) {
            int count = prefs.getMeds(account.id).size();
            items.add((account.id.equals(active) ? "✓ " : "") + account.name
                    + " · " + count + " 种药");
        }
        items.add("＋ 新建账户");
        new MaterialAlertDialogBuilder(context)
                .setTitle("切换账户")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == accounts.size()) {
                        promptCreate(fragment, listener);
                    } else {
                        Account account = accounts.get(which);
                        prefs.setActiveAccount(account.id);
                        listener.onAccountsChanged();
                    }
                })
                .setNeutralButton("管理当前账户", (dialog, which) -> showManage(fragment, listener))
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showManage(Fragment fragment, Listener listener) {
        Context context = fragment.requireContext();
        AppPrefs prefs = AppPrefs.get(context);
        Account account = prefs.activeAccount();
        String[] items = {"重命名「" + account.name + "」", "删除「" + account.name + "」"};
        new MaterialAlertDialogBuilder(context)
                .setTitle("管理账户")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) promptRename(fragment, listener);
                    else confirmDelete(fragment, listener);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public static void promptCreate(Fragment fragment, Listener listener) {
        EditText input = nameInput(fragment.requireContext(), "");
        input.setHint("例如：爸爸、妈妈、我");
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("新建账户")
                .setMessage("每个账户有独立的药品、订阅链接和系统日历，互不覆盖。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (dialog, which) -> {
                    String name = text(input);
                    if (name.isEmpty()) {
                        Ui.toast(fragment.requireContext(), "请输入账户名称");
                        return;
                    }
                    AppPrefs prefs = AppPrefs.get(fragment.requireContext());
                    for (Account account : prefs.getAccounts()) {
                        if (account.name.equals(name)) {
                            Ui.toast(fragment.requireContext(), "已有同名账户");
                            return;
                        }
                    }
                    prefs.createAccount(name);
                    listener.onAccountsChanged();
                    Ui.toast(fragment.requireContext(), "已创建「" + name + "」，可导入或同步这个账户的药");
                })
                .show();
    }

    public static void promptRename(Fragment fragment, Listener listener) {
        AppPrefs prefs = AppPrefs.get(fragment.requireContext());
        Account account = prefs.activeAccount();
        EditText input = nameInput(fragment.requireContext(), account.name);
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle("重命名账户")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = text(input);
                    if (name.isEmpty()) {
                        Ui.toast(fragment.requireContext(), "请输入账户名称");
                        return;
                    }
                    for (Account other : prefs.getAccounts()) {
                        if (!other.id.equals(account.id) && other.name.equals(name)) {
                            Ui.toast(fragment.requireContext(), "已有同名账户");
                            return;
                        }
                    }
                    Account copy = account.copy();
                    copy.name = name;
                    prefs.upsertAccount(copy);
                    listener.onAccountsChanged();
                })
                .show();
    }

    public static void confirmDelete(Fragment fragment, Listener listener) {
        Context context = fragment.requireContext();
        AppPrefs prefs = AppPrefs.get(context);
        Account account = prefs.activeAccount();
        if (prefs.getAccounts().size() <= 1) {
            Ui.toast(context, "至少要保留一个账户");
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("删除账户")
                .setMessage("将删除「" + account.name + "」的药品、服药记录、订阅和日历事件，"
                        + "其他账户不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    SubscriptionSync.cancelWork(context, account.id);
                    long calendarId = account.calendarId;
                    String accountId = account.id;
                    if (!prefs.deleteAccount(account.id)) {
                        Ui.toast(context, "至少要保留一个账户");
                        return;
                    }
                    Context app = context.getApplicationContext();
                    new Thread(() -> {
                        if (calendarId > 0) {
                            if (AppPrefs.DEFAULT_ACCOUNT_ID.equals(accountId)) {
                                CalendarSync.deleteLegacyEvents(app, calendarId);
                            }
                            CalendarSync.deleteAccountEvents(app, calendarId, accountId);
                        }
                        AlarmScheduler.rescheduleAll(app);
                    }, "medremind-delete-account").start();
                    listener.onAccountsChanged();
                    Ui.toast(context, "已删除账户");
                })
                .show();
    }

    private static EditText nameInput(Context context, String value) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        int pad = Math.round(20 * context.getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        return input;
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }
}