package com.tianxiaodudou.clipboardpolice.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.tianxiaodudou.clipboardpolice.R;

/** 记录列表：卡片条目，内容默认 3 行折叠，单击展开/收起，长按复制。 */
public class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.ClipViewHolder> {

    public interface Listener {
        void onLongPressCopy(String label, String text);
    }

    private static final int COLLAPSED_LINES = 3;

    private final List<ClipRecord> items = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public ClipAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<ClipRecord> records) {
        items.clear();
        if (records != null) items.addAll(records);
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public ClipViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_clip, parent, false);
        return new ClipViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ClipViewHolder holder, int position) {
        ClipRecord record = items.get(position);
        boolean isWrite = "write".equals(record.operation);
        boolean isBlocked = "blocked".equals(record.operation);

        holder.tvOp.setText(isBlocked ? R.string.op_blocked
                : (isWrite ? R.string.op_write : R.string.op_read));
        holder.tvOp.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                isBlocked ? R.color.blocked_red : (isWrite ? R.color.write_blue : R.color.read_gray)));
        holder.tvTime.setText(timeFormat.format(new Date(record.timestamp)));
        holder.tvPkg.setText(record.sourcePackage);
        holder.tvContent.setText(record.content);
        holder.tvContent.setMaxLines(record.expanded ? Integer.MAX_VALUE : COLLAPSED_LINES);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            ClipRecord r = items.get(pos);
            r.expanded = !r.expanded;
            notifyItemChanged(pos);
        });

        holder.tvPkg.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onLongPressCopy(holder.itemView.getContext().getString(R.string.copy_package),
                        record.sourcePackage);
            }
            return true;
        });
        holder.tvContent.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onLongPressCopy(holder.itemView.getContext().getString(R.string.copy_content),
                        record.content);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ClipViewHolder extends RecyclerView.ViewHolder {
        final TextView tvOp;
        final TextView tvTime;
        final TextView tvPkg;
        final TextView tvContent;

        ClipViewHolder(@NonNull View itemView) {
            super(itemView);
            tvOp = itemView.findViewById(R.id.tvOp);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvPkg = itemView.findViewById(R.id.tvPkg);
            tvContent = itemView.findViewById(R.id.tvContent);
        }
    }
}
