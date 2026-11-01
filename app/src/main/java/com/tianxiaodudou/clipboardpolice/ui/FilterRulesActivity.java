package com.tianxiaodudou.clipboardpolice.ui;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tianxiaodudou.clipboardpolice.R;

import java.util.ArrayList;
import java.util.List;

/** 过滤规则列表：启用/停用、编辑、删除。命中规则时 Hook 会直接拦截写入。 */
public class FilterRulesActivity extends AppCompatActivity implements FilterRuleAdapter.Listener {

    private final List<FilterRule> rules = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FilterRuleAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyView;
    private boolean rootGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_rules);
        applyInsets();

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());
        recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.emptyView);
        Button btnAdd = findViewById(R.id.btnAdd);

        adapter = new FilterRuleAdapter(rules, this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);
        btnAdd.setOnClickListener(v -> openEditor(-1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void openEditor(int index) {
        if (!rootGranted) {
            toast(getString(R.string.need_root));
            return;
        }
        Intent intent = new Intent(this, FilterRuleEditActivity.class);
        intent.putExtra(FilterRuleEditActivity.EXTRA_INDEX, index);
        startActivity(intent);
    }

    private void load() {
        new Thread(() -> {
            boolean granted = RootShell.ensureRoot();
            List<FilterRule> loaded = granted ? FilterStore.load() : new ArrayList<>();
            mainHandler.post(() -> {
                rootGranted = granted;
                rules.clear();
                rules.addAll(loaded);
                adapter.notifyDataSetChanged();
                boolean empty = rules.isEmpty();
                emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        }, "filter-load").start();
    }

    private void persist() {
        new Thread(() -> {
            boolean ok = FilterStore.save(new ArrayList<>(rules));
            mainHandler.post(() -> {
                if (!ok) toast(getString(R.string.filter_save_failed, getString(R.string.refresh_internal)));
            });
        }, "filter-save").start();
    }

    @Override
    public void onToggle(int position, boolean enabled) {
        if (position < 0 || position >= rules.size()) return;
        rules.get(position).enabled = enabled;
        adapter.notifyItemChanged(position);
        persist();
    }

    @Override
    public void onEdit(int position) {
        openEditor(position);
    }

    @Override
    public void onDelete(int position) {
        if (position < 0 || position >= rules.size()) return;
        FilterRule rule = rules.get(position);
        String title = rule.name == null || rule.name.trim().isEmpty()
                ? getString(R.string.filter_unnamed) : rule.name;
        new AlertDialog.Builder(this)
                .setTitle(R.string.filter_delete)
                .setMessage(getString(R.string.filter_delete_confirm, title))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    rules.remove(position);
                    adapter.notifyItemRemoved(position);
                    adapter.notifyItemRangeChanged(position, rules.size());
                    emptyView.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
                    recycler.setVisibility(rules.isEmpty() ? View.GONE : View.VISIBLE);
                    persist();
                })
                .show();
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        View header = findViewById(R.id.header);
        android.view.Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(true);
        final int headerTop = header.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            header.setPadding(header.getPaddingLeft(), headerTop + bars.top,
                    header.getPaddingRight(), header.getPaddingBottom());
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
