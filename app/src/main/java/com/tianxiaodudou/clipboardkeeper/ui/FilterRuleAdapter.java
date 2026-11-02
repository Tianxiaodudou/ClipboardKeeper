package com.tianxiaodudou.clipboardkeeper.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.tianxiaodudou.clipboardkeeper.R;

import java.util.List;

public class FilterRuleAdapter extends RecyclerView.Adapter<FilterRuleAdapter.VH> {

    public interface Listener {
        void onToggle(int position, boolean enabled);

        void onEdit(int position);

        void onDelete(int position);
    }

    private final List<FilterRule> rules;
    private final Listener listener;

    public FilterRuleAdapter(List<FilterRule> rules, Listener listener) {
        this.rules = rules;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_filter_rule, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FilterRule rule = rules.get(position);
        String title = rule.name == null || rule.name.trim().isEmpty()
                ? holder.itemView.getContext().getString(R.string.filter_unnamed) : rule.name;
        holder.tvName.setText(rule.enabled ? title
                : holder.itemView.getContext().getString(R.string.filter_disabled_name, title));
        holder.tvSummary.setText(rule.summary(holder.itemView.getContext()));

        holder.swEnabled.setOnCheckedChangeListener(null);
        holder.swEnabled.setChecked(rule.enabled);
        holder.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onToggle(pos, isChecked);
        });

        holder.btnEdit.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onEdit(pos);
        });
        holder.btnDelete.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onDelete(pos);
        });
    }

    @Override
    public int getItemCount() {
        return rules.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvSummary;
        final SwitchCompat swEnabled;
        final Button btnEdit;
        final Button btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvSummary = itemView.findViewById(R.id.tvSummary);
            swEnabled = itemView.findViewById(R.id.swEnabled);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
