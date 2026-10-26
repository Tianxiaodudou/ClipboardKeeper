package com.tianxiaodudou.clipboardpolice.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.widget.Button;
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
        applyInsets();

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

    /** targetSdk 35 起强制 edge-to-edge：自己把状态栏/导航栏高度让出来，顶部卡片仍延伸到状态栏后面 */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        View header = findViewById(R.id.header);
        Window window = getWindow();
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
            RecyclerView list = findViewById(R.id.recycler);
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(),
                    bars.bottom + Math.round(12 * getResources().getDisplayMetrics().density));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
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
                if (message == null) {
                    toast(getString(R.string.refresh_busy));
                } else {
                    toast(getString(R.string.refresh_failed, message));
                }
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
            toast(getString(R.string.copy_failed, getString(R.string.refresh_internal)));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
