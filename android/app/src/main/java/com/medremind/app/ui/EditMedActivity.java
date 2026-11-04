package com.medremind.app.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.calendar.CalendarSync;
import com.medremind.app.databinding.ActivityEditMedBinding;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.schedule.ScheduleEngine;
import com.medremind.app.util.Slots;
import com.medremind.app.util.Ui;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class EditMedActivity extends AppCompatActivity {
    public static final String EXTRA_MED_ID = "med_id";

    private ActivityEditMedBinding b;
    private AppPrefs prefs;
    private Med editing;
    private LocalDate startDate;
    private LocalDate endDate;
    private String freq = "DAILY";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityEditMedBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        prefs = AppPrefs.get(this);
        startDate = LocalDate.now();
        endDate = startDate.plusMonths(1);

        String id = getIntent().getStringExtra(EXTRA_MED_ID);
        if (id != null) {
            editing = prefs.findMed(id);
        }

        setupListeners();
        if (editing != null) {
            populate(editing);
        } else {
            b.btnStartDate.setText(Ui.longDate(startDate));
            b.btnEndDate.setText(Ui.longDate(endDate));
            b.btnDelete.setVisibility(android.view.View.GONE);
            b.editTitle.setText(R.string.add_med);
        }
        updateFrequencyUi();
        updateEndUi();
        updateTimesHint();
    }

    private void setupListeners() {
        b.btnClose.setOnClickListener(v -> finish());
        b.btnSave.setOnClickListener(v -> save());
        b.btnDelete.setOnClickListener(v -> confirmDelete());
        b.btnStartDate.setOnClickListener(v -> pickDate(true));
        b.btnEndDate.setOnClickListener(v -> pickDate(false));
        b.slotChips.setOnCheckedStateChangeListener((group, checkedIds) -> updateTimesHint());
        b.freqGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btn_weekly) freq = "WEEKLY";
            else if (checkedId == R.id.btn_monthly) freq = "MONTHLY";
            else freq = "DAILY";
            updateFrequencyUi();
        });
        b.endGroup.setOnCheckedChangeListener((group, checkedId) -> updateEndUi());
    }

    private void populate(Med med) {
        b.editTitle.setText(R.string.edit_med);
        b.inputName.setText(med.name);
        b.inputDose.setText(med.dose);
        b.inputNote.setText(med.note);
        startDate = ScheduleEngine.parseDate(med.startDate, LocalDate.now());
        b.btnStartDate.setText(Ui.longDate(startDate));

        b.chipMorning.setChecked(med.slots != null && med.slots.contains(Slots.MORNING));
        b.chipNoon.setChecked(med.slots != null && med.slots.contains(Slots.NOON));
        b.chipEvening.setChecked(med.slots != null && med.slots.contains(Slots.EVENING));
        b.chipNight.setChecked(med.slots != null && med.slots.contains(Slots.NIGHT));

        freq = med.freqType;
        if ("WEEKLY".equals(freq)) b.btnWeekly.setChecked(true);
        else if ("MONTHLY".equals(freq)) b.btnMonthly.setChecked(true);
        else b.btnDaily.setChecked(true);
        b.inputInterval.setText(String.valueOf(Math.max(1, med.interval)));
        if (med.weekDays != null) {
            b.chipMon.setChecked(med.weekDays.contains(1));
            b.chipTue.setChecked(med.weekDays.contains(2));
            b.chipWed.setChecked(med.weekDays.contains(3));
            b.chipThu.setChecked(med.weekDays.contains(4));
            b.chipFri.setChecked(med.weekDays.contains(5));
            b.chipSat.setChecked(med.weekDays.contains(6));
            b.chipSun.setChecked(med.weekDays.contains(7));
        }
        b.inputMonthDay.setText(String.valueOf(Math.max(1, med.monthDay)));

        LocalDate medEnd = ScheduleEngine.parseDate(med.endDate, startDate.plusMonths(1));
        endDate = medEnd == null ? startDate.plusMonths(1) : medEnd;
        b.btnEndDate.setText(Ui.longDate(endDate));
        if ("UNTIL".equals(med.endType)) b.radioUntil.setChecked(true);
        else if ("COUNT".equals(med.endType)) b.radioCount.setChecked(true);
        else b.radioNever.setChecked(true);
        b.inputEndCount.setText(String.valueOf(Math.max(1, med.endCount)));
        b.btnDelete.setVisibility(android.view.View.VISIBLE);
    }

    private void updateFrequencyUi() {
        boolean weekly = "WEEKLY".equals(freq);
        boolean monthly = "MONTHLY".equals(freq);
        b.weekChips.setVisibility(weekly ? android.view.View.VISIBLE : android.view.View.GONE);
        b.monthRow.setVisibility(monthly ? android.view.View.VISIBLE : android.view.View.GONE);
        b.intervalUnit.setText(weekly ? "周" : monthly ? "月" : "天");
    }

    private void updateEndUi() {
        b.endDateRow.setVisibility(b.radioUntil.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE);
        b.endCountRow.setVisibility(b.radioCount.isChecked() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void updateTimesHint() {
        List<String> parts = new ArrayList<>();
        if (b.chipMorning.isChecked()) parts.add("早 " + prefs.slotTime(Slots.MORNING));
        if (b.chipNoon.isChecked()) parts.add("午 " + prefs.slotTime(Slots.NOON));
        if (b.chipEvening.isChecked()) parts.add("晚 " + prefs.slotTime(Slots.EVENING));
        if (b.chipNight.isChecked()) parts.add("睡前 " + prefs.slotTime(Slots.NIGHT));
        b.timesHint.setText(parts.isEmpty()
                ? "请选择至少一个服药时间；具体时间可在「设置」里修改"
                : "提醒时间：" + TextUtils.join("  ·  ", parts));
    }

    private void pickDate(boolean isStart) {
        LocalDate base = isStart ? startDate : endDate;
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            LocalDate picked = LocalDate.of(year, month + 1, dayOfMonth);
            if (isStart) {
                startDate = picked;
                b.btnStartDate.setText(Ui.longDate(startDate));
                if (endDate.isBefore(startDate)) {
                    endDate = startDate.plusMonths(1);
                    b.btnEndDate.setText(Ui.longDate(endDate));
                }
            } else {
                endDate = picked;
                b.btnEndDate.setText(Ui.longDate(endDate));
            }
        }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth());
        dialog.show();
    }

    private void save() {
        b.layoutName.setError(null);
        String name = text(b.inputName).trim();
        if (name.isEmpty()) {
            b.layoutName.setError("请输入药品名称");
            b.inputName.requestFocus();
            return;
        }

        List<String> slots = new ArrayList<>();
        if (b.chipMorning.isChecked()) slots.add(Slots.MORNING);
        if (b.chipNoon.isChecked()) slots.add(Slots.NOON);
        if (b.chipEvening.isChecked()) slots.add(Slots.EVENING);
        if (b.chipNight.isChecked()) slots.add(Slots.NIGHT);
        if (slots.isEmpty()) {
            Ui.toast(this, "请至少选择一个服药时间");
            return;
        }

        List<Integer> weekDays = new ArrayList<>();
        if (b.chipMon.isChecked()) weekDays.add(1);
        if (b.chipTue.isChecked()) weekDays.add(2);
        if (b.chipWed.isChecked()) weekDays.add(3);
        if (b.chipThu.isChecked()) weekDays.add(4);
        if (b.chipFri.isChecked()) weekDays.add(5);
        if (b.chipSat.isChecked()) weekDays.add(6);
        if (b.chipSun.isChecked()) weekDays.add(7);
        if ("WEEKLY".equals(freq) && weekDays.isEmpty()) {
            Ui.toast(this, "按周重复时请选择星期几");
            return;
        }

        int interval = parseInt(text(b.inputInterval), 1, 1, 365);
        int monthDay = parseInt(text(b.inputMonthDay), 1, 1, 31);
        int endCount = parseInt(text(b.inputEndCount), 10, 1, 9999);

        String endType = "NEVER";
        if (b.radioUntil.isChecked()) endType = "UNTIL";
        else if (b.radioCount.isChecked()) endType = "COUNT";
        if (endType.equals("UNTIL") && endDate.isBefore(startDate)) {
            Ui.toast(this, "结束日期不能早于开始日期");
            return;
        }

        Med med = editing != null ? editing.copy() : new Med();
        if (editing == null) {
            med.id = UUID.randomUUID().toString();
            med.accountId = prefs.activeAccountId();
        }
        med.name = name;
        med.dose = text(b.inputDose).trim();
        if (med.dose.isEmpty()) med.dose = "按医嘱";
        med.note = text(b.inputNote).trim();
        med.startDate = startDate.toString();
        med.slots = slots;
        med.freqType = freq;
        med.interval = interval;
        med.weekDays = weekDays;
        med.monthDay = monthDay;
        med.endType = endType;
        med.endDate = endType.equals("UNTIL") ? endDate.toString() : "";
        med.endCount = endCount;
        med.enabled = true;

        prefs.upsert(med);
        AlarmScheduler.rescheduleAll(this);
        autoSync();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        if (editing == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除药品")
                .setMessage("确认删除「" + editing.name + "」及其所有提醒吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    prefs.deleteMed(editing.id);
                    AlarmScheduler.cancelForMed(this, editing.id);
                    AlarmScheduler.rescheduleAll(this);
                    autoSync();
                    finish();
                })
                .show();
    }

    private void autoSync() {
        if (prefs.autoSync()) {
            Context app = getApplicationContext();
            new Thread(() -> CalendarSync.autoSyncIfEnabled(app)).start();
        }
    }

    private static String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception e) {
            return fallback;
        }
    }
}