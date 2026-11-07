package com.medremind.app.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;
import com.medremind.app.AppPrefs;
import com.medremind.app.R;
import com.medremind.app.databinding.FragmentTodayBinding;
import com.medremind.app.databinding.ViewDosePageBinding;
import com.medremind.app.model.Account;
import com.medremind.app.model.Dose;
import com.medremind.app.model.Med;
import com.medremind.app.schedule.AlarmScheduler;
import com.medremind.app.schedule.ScheduleEngine;
import com.medremind.app.util.Perms;
import com.medremind.app.util.Slots;
import com.medremind.app.util.Ui;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class TodayFragment extends Fragment {
    private FragmentTodayBinding b;
    private PagerAdapter pagerAdapter;
    private List<Dose> pendingDoses = new ArrayList<>();
    private List<Dose> takenDoses = new ArrayList<>();
    private List<Dose> expiredDoses = new ArrayList<>();
    private String highlightDate;
    private String highlightTime;
    private int selectedTab;
    private boolean syncingTabs;
    private AppPrefs prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = this::refresh;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentTodayBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        prefs = AppPrefs.get(requireContext());
        pagerAdapter = new PagerAdapter();
        b.pager.setAdapter(pagerAdapter);
        b.pager.setOffscreenPageLimit(2);
        b.pager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        b.tabs.addTab(b.tabs.newTab().setText("待服用"));
        b.tabs.addTab(b.tabs.newTab().setText("已服用"));
        b.tabs.addTab(b.tabs.newTab().setText("已过期"));
        b.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectedTab = tab.getPosition();
                if (!syncingTabs && b.pager.getCurrentItem() != selectedTab) {
                    b.pager.setCurrentItem(selectedTab, true);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        b.pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                selectedTab = position;
                TabLayout.Tab tab = b.tabs.getTabAt(position);
                if (tab != null && b.tabs.getSelectedTabPosition() != position) {
                    syncingTabs = true;
                    tab.select();
                    syncingTabs = false;
                }
            }
        });
        pagerAdapter.setHighlight(highlightDate, highlightTime);
        b.permBanner.setOnClickListener(v -> {
            Activity activity = getActivity();
            if (!(activity instanceof MainActivity)) return;
            List<String> missing = Perms.missing(requireContext());
            if (missing.contains("悬浮窗")) {
                Perms.openOverlaySettings(activity);
            } else if (missing.contains("精确闹钟")) {
                Perms.openExactAlarmSettings(activity);
            } else if (missing.contains("后台运行")) {
                Perms.openBatterySettings(activity);
            } else {
                ((MainActivity) activity).selectTab(R.id.nav_settings);
            }
        });
        b.accountChip.setOnClickListener(v -> AccountUi.show(this, this::refresh));
        // 今日页没有「+」按钮：没药品时点提示直接跳到「药品」页添加。
        b.emptyState.setOnClickListener(v -> {
            Activity activity = getActivity();
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).selectTab(R.id.nav_meds);
            }
        });
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /** 从闹钟“查看详情”进来：切到待服用，并把这一钟点的药标成绿色。 */
    public void focusAlarm(String date, String time) {
        highlightDate = date;
        highlightTime = time;
        selectedTab = 0;
        if (b == null) return;
        if (b.pager.getCurrentItem() != 0) b.pager.setCurrentItem(0, false);
        if (b.tabs.getTabAt(0) != null) b.tabs.getTabAt(0).select();
        if (pagerAdapter != null) pagerAdapter.setHighlight(date, time);
        refresh();
    }

    public void refresh() {
        if (b == null || !isAdded()) return;
        Context context = requireContext();
        prefs = AppPrefs.get(context);
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        b.dateText.setText(Ui.longDate(today) + " " + Ui.weekday(today));

        Account account = prefs.activeAccount();
        b.accountChip.setText("账户：" + account.name + " ▾");
        List<Med> meds = prefs.getMeds(account.id);

        List<Dose> todayDoses = ScheduleEngine.range(meds, today, 1, prefs::slotTime);
        for (Dose dose : todayDoses) {
            dose.taken = prefs.isTaken(dose.medId, dose.date, dose.slotKey);
        }

        List<Dose> pending = new ArrayList<>();
        List<Dose> taken = new ArrayList<>();
        List<Dose> expiredList = new ArrayList<>();
        for (Dose dose : todayDoses) {
            if (dose.taken) taken.add(dose);
            else if (expired(dose)) expiredList.add(dose);
            else pending.add(dose);
        }
        pending.sort((a, b) -> a.dateTime().compareTo(b.dateTime()));
        expiredList.sort((a, b) -> a.dateTime().compareTo(b.dateTime()));
        pendingDoses = pending;
        takenDoses = taken;
        expiredDoses = expiredList;

        boolean empty = todayDoses.isEmpty();
        b.tabs.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.pager.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            // 今日页不显示「+」按钮，提示必须引导到「药品」页，否则会让人找不到添加入口。
            boolean noMeds = meds.isEmpty();
            b.emptyState.setText(noMeds
                    ? "「" + account.name + "」还没有药品\n点这里去「药品」页添加，或在「设置」里导入"
                    : "今天没有需要服用的药");
            b.emptyState.setClickable(noMeds);
            b.emptyState.setTextColor(context.getColor(
                    noMeds ? R.color.teal_deep : R.color.text_soft));
            pagerAdapter.submit(0, new ArrayList<>());
            pagerAdapter.submit(1, new ArrayList<>());
            pagerAdapter.submit(2, new ArrayList<>());
        } else {
            updateTabTitles();
            pagerAdapter.submit(0, pendingDoses);
            pagerAdapter.submit(1, takenDoses);
            pagerAdapter.submit(2, expiredDoses);
            if (b.pager.getCurrentItem() != selectedTab) {
                b.pager.setCurrentItem(selectedTab, false);
            }
        }

        updatePermissionBanner(context);
        scheduleTick(todayDoses, now);
    }

    private void updateTabTitles() {
        TabLayout.Tab pendingTab = b.tabs.getTabAt(0);
        TabLayout.Tab takenTab = b.tabs.getTabAt(1);
        TabLayout.Tab expiredTab = b.tabs.getTabAt(2);
        if (pendingTab != null) pendingTab.setText("待服用（" + pendingDoses.size() + "）");
        if (takenTab != null) takenTab.setText("已服用（" + takenDoses.size() + "）");
        if (expiredTab != null) expiredTab.setText("已过期（" + expiredDoses.size() + "）");
    }

    /** 到服药时间、或待服药超过 1 小时变“已过期”时自动刷新。 */
    private void scheduleTick(List<Dose> todayDoses, LocalDateTime now) {
        handler.removeCallbacks(refreshTick);
        LocalDateTime next = null;
        for (Dose dose : todayDoses) {
            if (dose.taken) continue;
            LocalDateTime doseAt = dose.dateTime();
            if (doseAt.isAfter(now) && (next == null || doseAt.isBefore(next))) {
                next = doseAt;
            }
            LocalDateTime expireAt = doseAt.plusHours(1);
            if (expireAt.isAfter(now) && (next == null || expireAt.isBefore(next))) {
                next = expireAt;
            }
        }
        if (next == null) return;
        long delay = Duration.between(now, next).toMillis() + 1500;
        if (delay > 0 && delay < 24L * 60 * 60 * 1000) {
            handler.postDelayed(refreshTick, delay);
        }
    }

    private void updatePermissionBanner(Context context) {
        List<String> missing = Perms.missing(context);
        if (missing.isEmpty()) {
            b.permBanner.setVisibility(View.GONE);
        } else {
            b.permBanner.setVisibility(View.VISIBLE);
            b.permText.setText("缺少 " + String.join("、", missing) + "，闹钟可能无法准时响起");
        }
    }

    private boolean expired(Dose dose) {
        return !dose.taken && dose.dateTime().plusHours(1).isBefore(LocalDateTime.now());
    }

    private void showDoseDetail(Dose dose) {
        if (!isAdded()) return;
        Med med = prefs.findMed(dose.medId);
        Account account = med == null ? prefs.activeAccount()
                : prefs.findAccount(AppPrefs.accountIdOf(med));
        boolean future = dose.dateTime().isAfter(LocalDateTime.now());
        boolean expired = expired(dose);
        String doseText = dose.dose == null || dose.dose.isEmpty() ? "按医嘱" : dose.dose;

        StringBuilder s = new StringBuilder();
        if (prefs.getAccounts().size() > 1 && account != null) {
            s.append("账户：").append(account.name).append("\n");
        }
        s.append("时间：").append(Ui.longDate(dose.date)).append(" ")
                .append(dose.timeLabel()).append("（").append(dose.slotLabel).append("）\n");
        s.append("用量：").append(doseText).append("\n");
        s.append("状态：").append(dose.taken ? "已服用"
                : expired ? "已过期（超过 1 小时，不能修改）"
                : future ? "未到时间，" + Ui.dayLabel(dose.date) + " " + dose.timeLabel() + " 后可打勾"
                : "待服用，可补记").append("\n");
        if (dose.note != null && !dose.note.isEmpty()) {
            s.append("备注：").append(dose.note).append("\n");
        }
        if (med != null) {
            s.append("\n用药方案\n");
            StringBuilder slots = new StringBuilder();
            if (med.slots != null) {
                for (String key : med.slots) {
                    if (slots.length() > 0) slots.append("、");
                    slots.append(Slots.labelOf(key));
                }
            }
            s.append("时段：").append(slots.length() == 0 ? "未设置" : slots).append("\n");
            s.append("从 ").append(med.startDate == null || med.startDate.isEmpty() ? "未设置" : med.startDate)
                    .append(" 开始，").append(freqText(med)).append("\n");
            s.append("结束：").append(endText(med));
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(dose.name)
                .setMessage(s.toString().trim())
                .setPositiveButton("知道了", null);
        if (dose.taken) {
            builder.setNeutralButton("撤销已服用", (dialog, which) -> toggleTaken(dose));
        } else if (!future && !expired) {
            builder.setNeutralButton("标记已服用", (dialog, which) -> toggleTaken(dose));
        }
        builder.show();
    }

    private String freqText(Med med) {
        if ("WEEKLY".equals(med.freqType)) {
            StringBuilder days = new StringBuilder();
            if (med.weekDays != null) {
                for (int day : med.weekDays) {
                    if (day < 1 || day > 7) continue;
                    if (days.length() > 0) days.append("、");
                    days.append("周").append("一二三四五六日".charAt(day - 1));
                }
            }
            String text = days.length() == 0 ? "每周" : "每周 " + days;
            return med.interval > 1 ? text + "，每 " + med.interval + " 周" : text;
        }
        if ("MONTHLY".equals(med.freqType)) {
            String text = "每月 " + med.monthDay + " 日";
            return med.interval > 1 ? text + "，每 " + med.interval + " 个月" : text;
        }
        return med.interval > 1 ? "每 " + med.interval + " 天" : "每天";
    }

    private String endText(Med med) {
        if ("UNTIL".equals(med.endType)) {
            return med.endDate == null || med.endDate.isEmpty() ? "到指定日期" : "到 " + med.endDate;
        }
        if ("COUNT".equals(med.endType)) {
            return "共 " + med.endCount + " 次";
        }
        return "长期";
    }

    private void toggleTaken(Dose dose) {
        if (!isAdded()) return;
        if (!dose.taken && dose.dateTime().isAfter(LocalDateTime.now())) {
            Ui.toast(requireContext(), "还没到服药时间，不能提前打勾");
            return;
        }
        if (!dose.taken && dose.dateTime().plusHours(1).isBefore(LocalDateTime.now())) {
            Ui.toast(requireContext(), "已超过 1 小时，这条记录已过期，不能修改");
            return;
        }
        boolean taken = !dose.taken;
        prefs.setTaken(dose.medId, dose.date, dose.slotKey, taken);
        AlarmScheduler.rescheduleAll(requireContext());
        refresh();
        Ui.toast(requireContext(), taken
                ? "已标记「" + dose.name + "」为已服用"
                : "已撤销「" + dose.name + "」的服药记录");
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTick);
        pagerAdapter = null;
        super.onDestroyView();
        b = null;
    }

    /** 三页横向排列：待服用 / 已服用 / 已过期，左右滑动切换。 */
    private final class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.PageVH> {
        private final List<List<Dose>> pages = new ArrayList<>();
        private final String[] emptyTexts = {
                "今天没有待服用的药",
                "今天还没有已服用的药",
                "没有已过期的药"
        };
        private String hDate;
        private String hTime;

        PagerAdapter() {
            pages.add(new ArrayList<>());
            pages.add(new ArrayList<>());
            pages.add(new ArrayList<>());
        }

        void submit(int position, List<Dose> doses) {
            pages.get(position).clear();
            pages.get(position).addAll(doses);
            notifyItemChanged(position);
        }

        void setHighlight(String date, String time) {
            hDate = date;
            hTime = time;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ViewDosePageBinding page = ViewDosePageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            page.pageList.setLayoutManager(new LinearLayoutManager(parent.getContext()));
            DoseAdapter doseAdapter = new DoseAdapter(TodayFragment.this::toggleTaken,
                    TodayFragment.this::showDoseDetail);
            page.pageList.setAdapter(doseAdapter);
            return new PageVH(page, doseAdapter);
        }

        @Override
        public void onBindViewHolder(@NonNull PageVH holder, int position) {
            List<Dose> doses = pages.get(position);
            holder.adapter.setHighlight(hDate, hTime);
            holder.adapter.submit(doses);
            boolean empty = doses.isEmpty();
            holder.b.pageList.setVisibility(empty ? View.GONE : View.VISIBLE);
            holder.b.pageEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            holder.b.pageEmpty.setText(emptyTexts[position]);
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        final class PageVH extends RecyclerView.ViewHolder {
            final ViewDosePageBinding b;
            final DoseAdapter adapter;

            PageVH(ViewDosePageBinding binding, DoseAdapter adapter) {
                super(binding.getRoot());
                this.b = binding;
                this.adapter = adapter;
            }
        }
    }
}