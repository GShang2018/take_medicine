package com.medremind.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.medremind.app.databinding.ItemMedBinding;
import com.medremind.app.model.Med;
import com.medremind.app.util.MedFormat;

import java.util.ArrayList;
import java.util.List;

public class MedAdapter extends RecyclerView.Adapter<MedAdapter.VH> {
    public interface Listener {
        void onOpen(Med med);

        void onDelete(Med med);
    }

    private final List<Med> items = new ArrayList<>();
    private final Listener listener;

    public MedAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Med> meds) {
        items.clear();
        items.addAll(meds);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemMedBinding binding = ItemMedBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Med med = items.get(position);
        holder.b.medName.setText(med.name);
        holder.b.medDose.setText("用量 " + (med.dose == null || med.dose.isEmpty() ? "按医嘱" : med.dose));
        holder.b.medSchedule.setText(MedFormat.full(med));
        if (med.note != null && !med.note.isEmpty()) {
            holder.b.medNote.setVisibility(View.VISIBLE);
            holder.b.medNote.setText("📌 " + med.note);
        } else {
            holder.b.medNote.setVisibility(View.GONE);
        }
        holder.b.getRoot().setOnClickListener(v -> {
            if (listener != null) listener.onOpen(med);
        });
        holder.b.medDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDelete(med);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ItemMedBinding b;

        VH(ItemMedBinding binding) {
            super(binding.getRoot());
            this.b = binding;
        }
    }
}