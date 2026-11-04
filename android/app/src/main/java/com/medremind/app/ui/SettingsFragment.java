package com.medremind.app.ui;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TimePicker;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.calendar.CalendarSync;
import com.medremind.app.calendar.IcsImporter;
import com.medremind.app.databinding.FragmentSettingsBinding;
import com.medremind.app.model.Account;
import com.medremind.app.model.Med;

import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.sync.SubscriptionSync;
import com.medremind.app.util.OemCompat;
import com.medremind.app.util.Perms;
import com.medremind.app.util.Slots;
import com.medremind.app.util.Ui;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

public class SettingsFragment extends Fragment {
    private FragmentSettingsBinding b;
    private AppPrefs prefs;
    private RowBuilder.Row[] slotRows = new RowBuilder.Row[4];
    private RowBuilder.Row accountRow;
    private RowBuilder.Row addAccountRow;
    private RowBuilder.Row leadRow;
    private RowBuilder.Row snoozeRow;
    private RowBuilder.Row overlayRow;
    private RowBuilder.Row syncRow;
    private RowBuilder.Row removeRow;
    private RowBuilder.Row autoSyncRow;
    private RowBuilder.Row calLeadRow;
    private RowBuilder.Row notifyRow;
    private RowBuilder.Row exactRow;
    private RowBuilder.Row batteryRow;
    private RowBuilder.Row calendarRow;
    private RowBuilder.Row subUrlRow;
    private RowBuilder.Row subIntervalRow;
    private RowBuilder.Row subSyncRow;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean pendingCalendarSync;

    private final ActivityResultLauncher<String[]> calendarPermission =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                if (Perms.calendar(requireContext()) && pendingCalendarSync) {
                    pendingCalendarSync = false;
                    syncCalendar();
                }
                refresh();
            });

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) AlarmScheduler.rescheduleAll(requireContext());
                refresh();
            });

    private final ActivityResultLauncher<String[]> filePicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) importFromUri(uri);
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentSettingsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = AppPrefs.get(requireContext());
        buildRows();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void buildRows() {
        accountRow = RowBuilder.add(b.accountRows, "当前账户", "", "切换",
                v -> AccountUi.show(this, this::onAccountChanged));
        addAccountRow = RowBuilder.add(b.accountRows, "添加账户", "每个人一套独立的药、订阅和系统日历", "添加",
                v -> AccountUi.promptCreate(this, this::onAccountChanged));
        RowBuilder.add(b.accountRows, "重命名账户", "", "修改",
                v -> AccountUi.promptRename(this, this::onAccountChanged));
        RowBuilder.add(b.accountRows, "删除账户", "只删除当前账户，其他账户不受影响", "删除",
                v -> AccountUi.confirmDelete(this, this::onAccountChanged));

        for (int i = 0; i < 4; i++) {
            final int index = i;
            slotRows[i] = RowBuilder.add(b.slotRows, Slots.LABELS[i], "", "修改", v -> pickSlotTime(index));
        }

        leadRow = RowBuilder.add(b.behaviorRows, "提前提醒", "", "调整", v -> chooseLead());
        snoozeRow = RowBuilder.add(b.behaviorRows, "稍后提醒时长", "", "调整", v -> chooseSnooze());
        RowBuilder.add(b.behaviorRows, "测试闹钟", "5 秒后完整响一次，可按 Home 键锁屏验证", "测试", v -> {
            AlarmScheduler.scheduleTest(requireContext(), 5);
            Ui.toast(requireContext(), "5 秒后响铃，可以按 Home 或锁屏验证");
        });
        overlayRow = RowBuilder.add(b.behaviorRows, "解锁时弹出提醒", "", "去开启", v -> {
            if (Perms.canOverlay(requireContext())) {
                Ui.toast(requireContext(), "已开启：解锁使用时会弹出底部提醒卡片");
            } else {
                Perms.openOverlaySettings(requireActivity());
            }
        });

        syncRow = RowBuilder.add(b.calendarRows, "同步到系统日历", "", "同步", v -> syncCalendar());
        removeRow = RowBuilder.add(b.calendarRows, "移除日历事件", "删除本应用写入系统日历的日程", "移除", v -> removeCalendar());
        autoSyncRow = RowBuilder.add(b.calendarRows, "自动同步", "", "切换", v -> {
            prefs.setAutoSync(!prefs.autoSync());
            refresh();
        });
        calLeadRow = RowBuilder.add(b.calendarRows, "日历提前提醒", "", "调整", v -> chooseCalendarLead());

        subUrlRow = RowBuilder.add(b.subscriptionRows, "订阅链接", "", "设置", v -> editSubscriptionUrl());
        subIntervalRow = RowBuilder.add(b.subscriptionRows, "自动更新频率", "", "调整",
                v -> chooseSubscriptionInterval());
        subSyncRow = RowBuilder.add(b.subscriptionRows, "立即同步", "拉取订阅链接的最新方案", "同步",
                v -> syncSubscriptionNow());

        notifyRow = RowBuilder.add(b.permissionRows, "通知权限", "", "去开启", v -> {
            if (Perms.notifications(requireContext())) Perms.openNotificationSettings(requireActivity());
            else if (android.os.Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                Perms.openNotificationSettings(requireActivity());
            }
        });
        exactRow = RowBuilder.add(b.permissionRows, "精确闹钟", "Android 12 起需要单独授权，否则提醒可能延迟", "去开启",
                v -> Perms.openExactAlarmSettings(requireActivity()));
        batteryRow = RowBuilder.add(b.permissionRows, "后台运行", "关闭电池优化，避免系统冻结闹钟", "去设置",
                v -> Perms.openBatterySettings(requireActivity()));
        RowBuilder.add(b.permissionRows, "自启动 / 后台白名单", OemCompat.setupHint(), "去设置",
                v -> OemCompat.showGuide(requireActivity()));
        calendarRow = RowBuilder.add(b.permissionRows, "日历读写", "用于把服药计划写入系统日历", "去开启", v -> {
            if (Perms.calendar(requireContext())) {
                Ui.toast(requireContext(), "日历权限已开启");
            } else {
                calendarPermission.launch(new String[]{Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR});
            }
        });

        RowBuilder.add(b.dataRows, "从 .ics 文件导入", "选择手机里的日历文件", "选择",
                v -> filePicker.launch(new String[]{"text/calendar", "text/plain", "*/*"}));
        RowBuilder.add(b.dataRows, "从网址导入 .ics", "支持 http/https，下载后覆盖当前账户的方案", "导入",
                v -> importFromUrl());
        RowBuilder.add(b.dataRows, "清除已服用记录", "只清除打勾状态，不影响药品方案", "清除", v -> {
            prefs.clearTaken();
            refresh();
            Ui.toast(requireContext(), "已清除服药记录");
        });
    }

    public void refresh() {
        if (b == null || !isAdded()) return;
        prefs = AppPrefs.get(requireContext());
        for (int i = 0; i < 4; i++) {
            slotRows[i].subtitle.setText(prefs.slotTime(Slots.KEYS[i]));
        }
        leadRow.subtitle.setText(prefs.leadMinutes() == 0 ? "准点响铃" : "提前 " + prefs.leadMinutes() + " 分钟响铃");
        snoozeRow.subtitle.setText("每次 " + prefs.snoozeMinutes() + " 分钟");
        overlayRow.subtitle.setText(Perms.canOverlay(requireContext())
                ? "已开启：解锁时也会弹出底部提醒卡片"
                : "需要「显示在其他应用上层」权限；不开则解锁时只显示横幅");
        overlayRow.action.setText(Perms.canOverlay(requireContext()) ? "已开启" : "去开启");

        Account account = prefs.activeAccount();
        int doses = 0;
        for (Med med : prefs.getMeds(account.id)) {
            if (med.enabled && med.slots != null) doses += med.slots.size();
        }
        int accountMeds = prefs.getMeds(account.id).size();
        accountRow.subtitle.setText(account.name + " · " + accountMeds + " 种药"
                + (account.subUrl == null || account.subUrl.isEmpty() ? "" : " · 已订阅"));
        addAccountRow.subtitle.setText("共 " + prefs.getAccounts().size() + " 个账户；当前是「" + account.name + "」");
        b.settingsSummary.setText("「" + account.name + "」" + accountMeds + " 种药品 · 每天约 " + doses + " 次");

        long calendarId = account.calendarId;
        if (calendarId > 0) {
            String name = account.calendarName;
            if (name == null || name.isEmpty()) name = CalendarSync.calendarName(requireContext(), calendarId);
            int count = account.calendarCount;
            if (Perms.calendar(requireContext())) {
                count = CalendarSync.countAppEvents(requireContext(), calendarId);
            }
            syncRow.subtitle.setText(name + (count > 0 ? " · " + count + " 个事件" : " · 尚未写入"));
            syncRow.action.setText("重新同步");
        } else {
            syncRow.subtitle.setText("未选择日历，可创建「吃药提醒 · " + account.name + "」");
            syncRow.action.setText("选择日历");
        }
        autoSyncRow.subtitle.setText(prefs.autoSync() ? "已开启：修改药品后自动更新日历" : "已关闭");
        autoSyncRow.action.setText(prefs.autoSync() ? "关闭" : "开启");
        calLeadRow.subtitle.setText(prefs.calendarLead() == 0
                ? "准点提醒" : "提前 " + prefs.calendarLead() + " 分钟提醒");
        refreshSubscriptionRows();
        refreshPermissions();
    }

    private void refreshSubscriptionRows() {
        Account account = prefs.activeAccount();
        String url = account.subUrl == null ? "" : account.subUrl;
        if (url.isEmpty()) {
            subUrlRow.subtitle.setText("「" + account.name + "」未设置，可从日历链接自动更新");
            subUrlRow.action.setText("设置");
        } else {
            subUrlRow.subtitle.setText(url);
            subUrlRow.action.setText("修改");
        }

        int interval = account.subInterval;
        subIntervalRow.subtitle.setText(interval <= 0
                ? "手动" : "每 " + SubscriptionSync.intervalLabel(interval));
        subIntervalRow.action.setText("调整");

        long last = account.subLastSync;
        String error = account.subLastError == null ? "" : account.subLastError;
        if (last <= 0) {
            subSyncRow.subtitle.setText(error.isEmpty() ? "尚未同步过" : "上次失败：" + error);
        } else {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS);
            subSyncRow.subtitle.setText(error.isEmpty()
                    ? "上次同步 " + ago
                    : "上次失败：" + error + " · " + ago);
        }
        subSyncRow.action.setText(url.isEmpty() ? "未设置" : "同步");
        subSyncRow.action.setTextColor(ContextCompat.getColor(requireContext(),
                url.isEmpty() ? R.color.text_soft : R.color.teal_dark));
    }

    private void refreshPermissions() {
        Context context = requireContext();
        setPerm(notifyRow, Perms.notifications(context), "已开启通知", "未开启，闹钟无法显示通知");
        setPerm(exactRow, Perms.exactAlarm(context), "已开启精确闹钟", "未开启，提醒可能延迟");
        setPerm(batteryRow, Perms.battery(context), "已忽略电池优化", "未设置，系统可能冻结闹钟");
        setPerm(calendarRow, Perms.calendar(context), "已授权", "未授权");
    }

    private void setPerm(RowBuilder.Row row, boolean granted, String okText, String missingText) {
        row.subtitle.setText(granted ? okText : missingText);
        row.action.setText(granted ? "已开启" : "去开启");
        row.action.setTextColor(ContextCompat.getColor(requireContext(),
                granted ? R.color.text_soft : R.color.teal_dark));
    }

    private void pickSlotTime(int index) {
        String key = Slots.KEYS[index];
        LocalTime time = Slots.parseTime(prefs.slotTime(key), "08:00");
        TimePickerDialog dialog = new TimePickerDialog(requireContext(),
                (TimePicker view, int hour, int minute) -> {
                    prefs.setSlotTime(key, String.format(java.util.Locale.CHINA, "%02d:%02d", hour, minute));
                    AlarmScheduler.rescheduleAll(requireContext());
                    autoSyncCalendar();
                    refresh();
                }, time.getHour(), time.getMinute(), true);
        dialog.show();
    }

    private void chooseLead() {
        final int[] values = {0, 5, 10, 15, 30};
        String[] labels = {"准点响铃", "提前 5 分钟", "提前 10 分钟", "提前 15 分钟", "提前 30 分钟"};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.leadMinutes()) checked = i;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("提前多久提醒")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setLeadMinutes(values[which]);
                    AlarmScheduler.rescheduleAll(requireContext());
                    autoSyncCalendar();
                    refresh();
                    dialog.dismiss();
                })
                .show();
    }

    private void chooseSnooze() {
        final int[] values = {5, 10, 15, 30};
        String[] labels = {"5 分钟", "10 分钟", "15 分钟", "30 分钟"};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.snoozeMinutes()) checked = i;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("稍后提醒间隔")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setSnoozeMinutes(values[which]);
                    refresh();
                    dialog.dismiss();
                })
                .show();
    }

    private void chooseCalendarLead() {
        final int[] values = {0, 5, 10, 15, 30, 60};
        String[] labels = {"准点", "提前 5 分钟", "提前 10 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时"};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == prefs.calendarLead()) checked = i;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("日历提醒提前量")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setCalendarLead(values[which]);
                    refresh();
                    dialog.dismiss();
                })
                .show();
    }

    // ---------------- 系统日历 ----------------

    private void syncCalendar() {
        if (!Perms.calendar(requireContext())) {
            pendingCalendarSync = true;
            calendarPermission.launch(new String[]{Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR});
            return;
        }
        Account account = prefs.activeAccount();
        if (account.calendarId <= 0) {
            showCalendarPicker();
            return;
        }
        doSync(account.calendarId, account.calendarName);
    }

    private void showCalendarPicker() {
        Context app = requireContext().getApplicationContext();
        Account account = prefs.activeAccount();
        new Thread(() -> {
            List<CalendarSync.Cal> calendars = CalendarSync.writableCalendars(app);
            main.post(() -> {
                if (b == null || !isAdded()) return;
                List<String> items = new ArrayList<>();
                items.add("➕ 创建专用本地日历（吃药提醒 · " + account.name + "）");
                List<CalendarSync.Cal> selectable = new ArrayList<>();
                for (CalendarSync.Cal cal : calendars) {
                    if (CalendarSync.calendarUsedByAnother(requireContext(), cal.id, account.id)) continue;
                    selectable.add(cal);
                    items.add(cal.toString());
                }
                android.util.Log.d("MediRemind", "calendar picker items=" + items.size()
                        + " calendars=" + calendars.size());
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle("「" + account.name + "」写入哪个日历？")
                        .setItems(items.toArray(new String[0]), (dialog, which) -> {
                            if (which == 0) {
                                createLocalCalendarAndSync();
                            } else {
                                CalendarSync.Cal cal = selectable.get(which - 1);
                                doSync(cal.id, cal.name);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }, "medremind-calendars").start();
    }

    private void createLocalCalendarAndSync() {
        Context app = requireContext().getApplicationContext();
        Account account = prefs.activeAccount();
        String displayName = "吃药提醒 · " + account.name;
        new Thread(() -> {
            try {
                long id = CalendarSync.createLocalCalendar(app, displayName);
                CalendarSync.sync(app, account.id, id, prefs.calendarLead());
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    Account latest = prefs.findAccount(account.id);
                    if (latest == null) return;
                    Account copy = latest.copy();
                    copy.calendarId = id;
                    copy.calendarName = displayName;
                    prefs.upsertAccount(copy);
                    Ui.toast(requireContext(), "已创建「" + displayName + "」日历");
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    Ui.toast(requireContext(), "无法创建本地日历，请选择一个已有日历");
                    showCalendarPicker();
                });
            }
        }, "medremind-create-calendar").start();
    }

    private void doSync(long calendarId, String calendarName) {
        Context app = requireContext().getApplicationContext();
        Account account = prefs.activeAccount();
        new Thread(() -> {
            try {
                int count = CalendarSync.sync(app, account.id, calendarId, prefs.calendarLead());
                String name = calendarName == null || calendarName.isEmpty()
                        ? CalendarSync.calendarName(app, calendarId) : calendarName;
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    Account latest = prefs.findAccount(account.id);
                    if (latest == null) return;
                    Account copy = latest.copy();
                    copy.calendarId = calendarId;
                    copy.calendarName = name;
                    prefs.upsertAccount(copy);
                    Ui.toast(requireContext(), "已把「" + latest.name + "」的 " + count + " 个事件同步到「" + name + "」");
                    refresh();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    Ui.toast(requireContext(), "同步失败：" + e.getMessage());
                });
            }
        }, "medremind-sync").start();
    }

    private void removeCalendar() {
        Account account = prefs.activeAccount();
        long id = account.calendarId;
        if (id <= 0) {
            Ui.toast(requireContext(), "「" + account.name + "」尚未同步过日历");
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("移除日历事件")
                .setMessage("将只删除「" + account.name + "」写入系统日历的服药日程，其他账户不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    Context app = requireContext().getApplicationContext();
                    new Thread(() -> {
                        if (AppPrefs.DEFAULT_ACCOUNT_ID.equals(account.id)) {
                            CalendarSync.deleteLegacyEvents(app, id);
                        }
                        int deleted = CalendarSync.deleteAccountEvents(app, id, account.id);
                        main.post(() -> {
                            if (b == null || !isAdded()) return;
                            Account latest = prefs.findAccount(account.id);
                            if (latest != null) {
                                Account copy = latest.copy();
                                copy.calendarId = -1;
                                copy.calendarName = "";
                                copy.calendarCount = 0;
                                copy.calendarLastSync = 0;
                                prefs.upsertAccount(copy);
                            }
                            Ui.toast(requireContext(), "已移除 " + deleted + " 个日历事件");
                            refresh();
                        });
                    }, "medremind-unsync").start();
                })
                .show();
    }

    private void onAccountChanged() {
        refresh();
        AlarmScheduler.rescheduleAll(requireContext());
    }

    // ---------------- 导入 ----------------

    private void importFromUrl() {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/medicine.ics");
        input.setText(prefs.lastIcsUrl());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("从网址导入 .ics")
                .setMessage("填入能直接下载 .ics 文件的链接（http / https / webcal）。下载后会覆盖当前账户的药品方案，不影响其他账户。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载并导入", (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    startUrlImport(url);
                })
                .show();
    }

    private void startUrlImport(String url) {
        if (url.isEmpty()) {
            Ui.toast(requireContext(), "请先填写链接");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("webcal://")) {
            Ui.toast(requireContext(), "链接需要以 http://、https:// 或 webcal:// 开头");
            return;
        }
        AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("正在下载…")
                .setMessage(url)
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            try {
                String ics = IcsImporter.readUrl(url);
                List<Med> meds = IcsImporter.parse(ics);
                main.post(() -> {
                    dismissQuietly(progress);
                    if (b == null || !isAdded()) return;
                    prefs.setLastIcsUrl(url);
                    confirmImport(meds);
                });
            } catch (Exception e) {
                main.post(() -> {
                    dismissQuietly(progress);
                    if (b == null || !isAdded()) return;
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Ui.toast(requireContext(), "下载失败：" + message);
                });
            }
        }, "medremind-import-url").start();
    }

    private void dismissQuietly(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    // ---------------- 网址订阅 ----------------

    private void editSubscriptionUrl() {
        Account account = prefs.activeAccount();
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://example.com/medicine.ics");
        input.setText(account.subUrl == null || account.subUrl.isEmpty()
                ? prefs.lastIcsUrl() : account.subUrl);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("「" + account.name + "」的订阅链接")
                .setMessage("只更新这个账户的药品，不会影响其他账户。\n\n"
                        + "注意：订阅会覆盖该账户的本地药品方案；如果手动改过药品，下次同步会被链接内容覆盖。\n"
                        + "留空保存可取消订阅。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并同步", (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    saveSubscriptionUrl(url);
                })
                .show();
    }

    private void saveSubscriptionUrl(String url) {
        Context context = requireContext();
        Account account = prefs.activeAccount();
        if (url.isEmpty()) {
            Account copy = account.copy();
            copy.subUrl = "";
            copy.subInterval = 0;
            copy.subHash = "";
            copy.subLastSync = 0;
            copy.subLastError = "";
            prefs.upsertAccount(copy);
            SubscriptionSync.cancelWork(context, account.id);
            refresh();
            Ui.toast(context, "已取消「" + account.name + "」的订阅");
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("webcal://")) {
            Ui.toast(context, "链接需要以 http://、https:// 或 webcal:// 开头");
            return;
        }
        Account copy = account.copy();
        copy.subUrl = url;
        copy.subHash = "";
        prefs.upsertAccount(copy);
        SubscriptionSync.scheduleWork(context, account.id);
        refresh();
        syncSubscriptionNow();
    }

    private void chooseSubscriptionInterval() {
        Account account = prefs.activeAccount();
        int checked = 0;
        for (int i = 0; i < SubscriptionSync.INTERVALS.length; i++) {
            if (SubscriptionSync.INTERVALS[i] == account.subInterval) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("「" + account.name + "」自动更新频率")
                .setSingleChoiceItems(SubscriptionSync.INTERVAL_LABELS, checked, (dialog, which) -> {
                    int minutes = SubscriptionSync.INTERVALS[which];
                    Account latest = prefs.activeAccount();
                    Account copy = latest.copy();
                    copy.subInterval = minutes;
                    prefs.upsertAccount(copy);
                    SubscriptionSync.scheduleWork(requireContext(), latest.id);
                    dialog.dismiss();
                    refresh();
                    if (minutes > 0 && (latest.subUrl == null || latest.subUrl.isEmpty())) {
                        Ui.toast(requireContext(), "请再设置这个账户的订阅链接");
                        editSubscriptionUrl();
                    }
                })
                .show();
    }

    private void syncSubscriptionNow() {
        Account account = prefs.activeAccount();
        if (account.subUrl == null || account.subUrl.isEmpty()) {
            Ui.toast(requireContext(), "请先设置「" + account.name + "」的订阅链接");
            editSubscriptionUrl();
            return;
        }
        AlertDialog progress = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("正在同步「" + account.name + "」…")
                .setMessage(account.subUrl)
                .setCancelable(false)
                .create();
        progress.show();
        SubscriptionSync.syncAsync(requireContext(), account.id, (ok, message) -> {
            dismissQuietly(progress);
            if (b == null || !isAdded()) return;
            Ui.toast(requireContext(), ok ? message : "同步失败：" + message);
            refresh();
        });
    }

    private void importFromUri(Uri uri) {
        Context app = requireContext().getApplicationContext();
        new Thread(() -> {
            try {
                String ics = IcsImporter.readUri(app, uri);
                List<Med> meds = IcsImporter.parse(ics);
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    confirmImport(meds);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (b == null || !isAdded()) return;
                    Ui.toast(requireContext(), "读取文件失败：" + e.getMessage());
                });
            }
        }, "medremind-import-file").start();
    }

    private void confirmImport(List<Med> meds) {
        if (meds.isEmpty()) {
            Ui.toast(requireContext(), "没有解析到药品，请确认是网页版导出的 .ics 文件");
            return;
        }
        Account account = prefs.activeAccount();
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("导入 " + meds.size() + " 种药品")
                .setMessage("将导入到账户「" + account.name + "」，替换这个账户现有的药品方案，"
                        + "其他账户不受影响，并重新安排全部闹钟。")
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) -> {
                    prefs.saveMedsForAccount(account.id, meds);
                    AlarmScheduler.rescheduleAll(requireContext());
                    autoSyncCalendar();
                    refresh();
                    Ui.toast(requireContext(), "已导入 " + meds.size() + " 种药品到「" + account.name + "」");
                })
                .show();
    }

    private void autoSyncCalendar() {
        if (prefs.autoSync()) {
            Context app = requireContext().getApplicationContext();
            new Thread(() -> CalendarSync.autoSyncIfEnabled(app)).start();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}