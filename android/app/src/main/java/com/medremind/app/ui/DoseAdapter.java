package com.medremind.app.ui;

import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.medremind.app.R;
import com.medremind.app.databinding.ItemDoseBinding;
import com.medremind.app.model.Dose;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 今日服药清单。未到点的药不能提前打勾，已服的可以撤销。 */
public class DoseAdapter extends RecyclerView.Adapter<DoseAdapter.DoseVH> {
    public interface OnToggle {
        void onToggle(Dose dose);
    }

    public interface OnOpen {
        void onOpen(Dose dose);
    }

    private final List<Dose> items = new ArrayList<>();
    private final OnToggle toggle;
    private final OnOpen open;
    private String highlightDate;
    private String highlightTime;

    public DoseAdapter(OnToggle toggle, OnOpen open) {
        this.toggle = toggle;
        this.open = open;
    }

    public void submit(List<Dose> doses) {
        items.clear();
        items.addAll(doses);
        notifyDataSetChanged();
    }

    /** 从闹钟详情进来时，把同一钟点的待服药行标成绿色。 */
    public void setHighlight(String date, String time) {
        highlightDate = date;
        highlightTime = time;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DoseVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new DoseVH(ItemDoseBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull DoseVH h, int position) {
        Dose dose = items.get(position);
        android.content.Context ctx = h.itemView.getContext();
        LocalDateTime now = LocalDateTime.now();
        boolean future = dose.dateTime().isAfter(now);
        // 到点后超过 1 小时还没打勾：置灰、只读，不能再补记。
        boolean expired = !dose.taken && !future
                && dose.dateTime().plusHours(1).isBefore(now);
        boolean due = !future && !expired;
        boolean explicitHighlight = !dose.taken
                && highlightDate != null && highlightDate.equals(dose.date.toString())
                && highlightTime != null && highlightTime.equals(dose.timeLabel());
        // 到点后、尚未过期/打勾的药，就是本次闹钟触发但还没吃的药；不依赖用户是否点了“查看详情”。
        boolean highlight = !dose.taken && (due || explicitHighlight);
        h.itemView.setBackgroundResource(highlight
                ? R.drawable.bg_dose_item_highlight
                : R.drawable.bg_dose_item);
        h.itemView.setAlpha(expired ? 0.55f : 1f);

        h.b.doseTime.setText(dose.timeLabel());
        h.b.doseSlot.setText(dose.slotLabel);
        h.b.doseName.setText(dose.name);
        h.b.doseDose.setText("用量 " + (dose.dose == null || dose.dose.isEmpty() ? "按医嘱" : dose.dose));

        if (dose.note != null && !dose.note.isEmpty()) {
            h.b.doseNote.setVisibility(View.VISIBLE);
            h.b.doseNote.setText("📌 " + dose.note);
        } else {
            h.b.doseNote.setVisibility(View.GONE);
        }

        if (dose.taken) {
            h.b.doseTime.setPaintFlags(h.b.doseTime.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
            h.b.doseTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseSlot.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseName.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseDose.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseNote.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseAction.setEnabled(true);
            h.b.doseAction.setText("撤销");
            h.b.doseAction.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted));
            h.b.doseAction.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.border)));
        } else if (expired) {
            h.b.doseTime.setPaintFlags(h.b.doseTime.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            h.b.doseTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseSlot.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseName.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseDose.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseNote.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseAction.setEnabled(false);
            h.b.doseAction.setText("已过期");
            h.b.doseAction.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
            h.b.doseAction.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.border)));
        } else {
            h.b.doseTime.setPaintFlags(h.b.doseTime.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            h.b.doseTime.setTextColor(ContextCompat.getColor(ctx, R.color.text));
            h.b.doseSlot.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted));
            h.b.doseName.setTextColor(ContextCompat.getColor(ctx, R.color.text));
            h.b.doseDose.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted));
            h.b.doseNote.setTextColor(ContextCompat.getColor(ctx, R.color.amber));
            if (future) {
                h.b.doseAction.setEnabled(false);
                h.b.doseAction.setText("未到时间");
                h.b.doseAction.setTextColor(ContextCompat.getColor(ctx, R.color.text_soft));
                h.b.doseAction.setStrokeColor(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.border)));
            } else {
                h.b.doseAction.setEnabled(true);
                h.b.doseAction.setText("已服");
                h.b.doseAction.setTextColor(ContextCompat.getColor(ctx, R.color.teal_dark));
                h.b.doseAction.setStrokeColor(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.teal_mid)));
            }
        }
        h.b.doseAction.setOnClickListener(v -> {
            if (!dose.taken && (dose.dateTime().isAfter(LocalDateTime.now())
                    || dose.dateTime().plusHours(1).isBefore(LocalDateTime.now()))) return;
            if (toggle != null) toggle.onToggle(dose);
        });
        h.itemView.setOnClickListener(v -> {
            if (open != null) open.onOpen(dose);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class DoseVH extends RecyclerView.ViewHolder {
        final ItemDoseBinding b;

        DoseVH(ItemDoseBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}