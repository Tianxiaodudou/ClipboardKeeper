package com.tianxiaodudou.clipboardpolice.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** 主界面：列表 + 刷新 / 清空 / 设置。 */
public class MainActivity extends AppCompatActivity implements ClipAdapter.Listener {

    private RecyclerView recycler;
    private TextView emptyView;
    private TextView rootBanner;
    private Button btnRefresh;
    private Button btnClear;
    private Button btnSettings;

    private ClipAdapter adapter;
    private ClipUpdateReceiver updateReceiver;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean rootGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.emptyView);
        rootBanner = findViewById(R.id.rootBanner);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnClear = findViewById(R.id.btnClear);
        btnSettings = findViewById(R.id.btnSettings);

        adapter = new ClipAdapter(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> refresh(true));
        btnClear.setOnClickListener(v -> confirmClear());
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        updateReceiver = new ClipUpdateReceiver(() -> refresh(false));
        setRootAvailable(false);
        checkRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(updateReceiver, ClipUpdateReceiver.intentFilter(),
                Context.RECEIVER_EXPORTED);
        if (rootGranted) refresh(false);
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(updateReceiver);
        } catch (Throwable ignored) {
            // 忽略
        }
        super.onPause();
    }

    // ------------------------------------------------------------------ Root

    private void checkRoot() {
        new Thread(() -> {
            boolean granted = RootShell.ensureRoot();
            mainHandler.post(() -> {
                setRootAvailable(granted);
                if (granted) {
                    refresh(false);
                } else {
                    adapter.submit(java.util.Collections.emptyList());
                    updateEmptyView();
                }
            });
        }, "root-check").start();
    }

    private void setRootAvailable(boolean granted) {
        rootGranted = granted;
        rootBanner.setVisibility(granted ? View.GONE : View.VISIBLE);
        btnRefresh.setEnabled(granted);
        btnClear.setEnabled(granted);
        btnSettings.setEnabled(granted);
    }

    // --------------------------------------------------------------- 刷新/清空

    private void refresh(boolean manual) {
        if (!rootGranted) return;
        if (manual) toast(getString(R.string.refreshing));
        ClipSync.refresh(this, (ok, message, inserted, records) -> {
            if (ok) {
                adapter.submit(records);
                updateEmptyView();
                if (manual) toast(getString(R.string.refresh_done, inserted));
            } else if (manual) {
                toast(getString(R.string.refresh_failed, message));
            }
        });
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(R.string.clear_confirm_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> clearAll())
                .show();
    }

    private void clearAll() {
        ClipSync.clearAll(this, () -> {
            adapter.submit(java.util.Collections.emptyList());
            updateEmptyView();
            toast(getString(R.string.cleared));
            refresh(false);
        });
    }

    private void updateEmptyView() {
        emptyView.setVisibility(adapter.size() == 0 ? View.VISIBLE : View.GONE);
        recycler.setVisibility(adapter.size() == 0 ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------ 复制

    @Override
    public void onLongPressCopy(String label, String text) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            manager.setPrimaryClip(ClipData.newPlainText(label, text == null ? "" : text));
            toast(getString(R.string.copied, label));
        } catch (Throwable t) {
            toast(getString(R.string.refresh_failed, String.valueOf(t.getMessage())));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
