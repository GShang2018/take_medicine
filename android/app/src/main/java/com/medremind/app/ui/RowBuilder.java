package com.medremind.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.medremind.app.R;

/** 设置页复用行：标题 + 副标题 + 右侧按钮。 */
public final class RowBuilder {
    public static class Row {
        public final View root;
        public final TextView title;
        public final TextView subtitle;
        public final MaterialButton action;

        Row(View root, TextView title, TextView subtitle, MaterialButton action) {
            this.root = root;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private RowBuilder() {
    }

    public static Row add(LinearLayout parent, String title, String subtitle, String actionText,
                          View.OnClickListener listener) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.view_row, parent, false);
        TextView titleView = view.findViewById(R.id.row_title);
        TextView subtitleView = view.findViewById(R.id.row_subtitle);
        MaterialButton actionView = view.findViewById(R.id.row_action);

        titleView.setText(title);
        subtitleView.setText(subtitle == null ? "" : subtitle);
        if (actionText == null || actionText.isEmpty()) {
            actionView.setVisibility(View.GONE);
        } else {
            actionView.setText(actionText);
        }
        if (listener != null) {
            view.setOnClickListener(listener);
            actionView.setOnClickListener(listener);
        }
        if (parent.getChildCount() > 0) {
            View divider = new View(parent.getContext());
            divider.setBackgroundColor(ContextCompat.getColor(parent.getContext(), R.color.border));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) parent.getResources().getDisplayMetrics().density));
            lp.leftMargin = (int) (16 * parent.getResources().getDisplayMetrics().density);
            parent.addView(divider, lp);
        }
        parent.addView(view);
        return new Row(view, titleView, subtitleView, actionView);
    }
}