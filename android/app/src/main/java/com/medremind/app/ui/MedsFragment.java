package com.medremind.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.calendar.CalendarSync;
import com.medremind.app.databinding.FragmentMedsBinding;
import com.medremind.app.model.Account;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.util.Ui;

import java.util.List;

public class MedsFragment extends Fragment implements MedAdapter.Listener {
    private FragmentMedsBinding b;
    private MedAdapter adapter;
    private AppPrefs prefs;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentMedsBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = AppPrefs.get(requireContext());
        adapter = new MedAdapter(this);
        b.medList.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.medList.setAdapter(adapter);
        b.accountChip.setOnClickListener(v -> AccountUi.show(this, this::refresh));
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    public void refresh() {
        if (b == null || !isAdded()) return;
        prefs = AppPrefs.get(requireContext());
        Account account = prefs.activeAccount();
        b.accountChip.setText("账户：" + account.name + " ▾");
        List<Med> meds = prefs.getMeds(account.id);
        adapter.submit(meds);
        int dosesPerDay = 0;
        for (Med med : meds) {
            if (med.enabled) dosesPerDay += med.slots == null ? 0 : med.slots.size();
        }
        b.medsSummary.setText("「" + account.name + "」共 " + meds.size() + " 种药品 · 每天约 " + dosesPerDay + " 次");
        boolean empty = meds.isEmpty();
        b.emptyState.setText("「" + account.name + "」还没有药品，点击右下角 + 添加，或在设置里导入");
        b.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        b.medList.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onOpen(Med med) {
        Intent intent = new Intent(requireContext(), EditMedActivity.class);
        intent.putExtra(EditMedActivity.EXTRA_MED_ID, med.id);
        startActivity(intent);
    }

    @Override
    public void onDelete(Med med) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除药品")
                .setMessage("确认删除「" + med.name + "」及其提醒吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    Context context = requireContext();
                    prefs.deleteMed(med.id);
                    AlarmScheduler.cancelForMed(context, med.id);
                    AlarmScheduler.rescheduleAll(context);
                    autoSync(context);
                    refresh();
                    Ui.toast(context, "已删除「" + med.name + "」");
                })
                .show();
    }

    private void autoSync(Context context) {
        if (prefs.autoSync()) {
            Context app = context.getApplicationContext();
            new Thread(() -> CalendarSync.autoSyncIfEnabled(app)).start();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}