package com.tianxiaodudou.clipboardpolice.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.tianxiaodudou.clipboardpolice.R;

/** 设置项全部写入 Settings.Global（su 写，Hook 读）。 */
public class SettingsActivity extends AppCompatActivity {

    public static final String KEY_WRITE_ENABLED = "clipboard_watch_write_enabled";
    public static final String KEY_WRITE_THROTTLE = "clipboard_watch_broadcast_throttle_ms";
    public static final String KEY_READ_ENABLED = "clipboard_watch_read_enabled";
    public static final String KEY_READ_THROTTLE = "clipboard_watch_read_throttle_ms";

    private SwitchCompat swWriteEnabled;
    private SwitchCompat swReadEnabled;
    private Spinner spWriteThrottle;
    private Spinner spReadThrottle;
    private Spinner spMaxRecords;
    private Button btnCheckUpdate;
    private View rowWriteThrottle;
    private View rowReadThrottle;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean loading = true;
    private boolean rootGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        applyInsets();

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        swWriteEnabled = findViewById(R.id.swWriteEnabled);
        swReadEnabled = findViewById(R.id.swReadEnabled);
        spWriteThrottle = findViewById(R.id.spWriteThrottle);
        spReadThrottle = findViewById(R.id.spReadThrottle);
        spMaxRecords = findViewById(R.id.spMaxRecords);
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate);
        rowWriteThrottle = findViewById(R.id.rowWriteThrottle);
        rowReadThrottle = findViewById(R.id.rowReadThrottle);

        setupSpinner(spWriteThrottle, R.array.throttle_entries, R.array.throttle_values,
                KEY_WRITE_THROTTLE, "1000");
        setupSpinner(spReadThrottle, R.array.throttle_entries, R.array.throttle_values,
                KEY_READ_THROTTLE, "1000");
        setupSpinner(spMaxRecords, R.array.max_records_entries, R.array.max_records_values,
                ClipSync.KEY_MAX_RECORDS, String.valueOf(ClipSync.DEFAULT_MAX_RECORDS));

        swWriteEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateThrottleVisibility();
            if (loading) return;
            saveSetting(KEY_WRITE_ENABLED, isChecked ? "1" : "0");
        });
        swReadEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateThrottleVisibility();
            if (loading) return;
            saveSetting(KEY_READ_ENABLED, isChecked ? "1" : "0");
        });

        btnCheckUpdate.setOnClickListener(v -> checkUpdate());
        loadSettings();
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

    /** 节流设置只在对应监测开关打开时才显示 */
    private void updateThrottleVisibility() {
        rowWriteThrottle.setVisibility(swWriteEnabled.isChecked() ? View.VISIBLE : View.GONE);
        rowReadThrottle.setVisibility(swReadEnabled.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void setupSpinner(Spinner spinner, int entriesRes, int valuesRes,
                              String settingKey, String defValue) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, entriesRes,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loading) return;
                String[] values = getResources().getStringArray(valuesRes);
                if (position < 0 || position >= values.length) return;
                saveSetting(settingKey, values[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 忽略
            }
        });
    }

    private void loadSettings() {
        loading = true;
        new Thread(() -> {
            boolean granted = RootShell.ensureRoot();
            String writeEnabled = RootShell.readSetting(KEY_WRITE_ENABLED, "1");
            String writeThrottle = RootShell.readSetting(KEY_WRITE_THROTTLE, "1000");
            String readThrottle = RootShell.readSetting(KEY_READ_THROTTLE, "1000");
            String maxRecords = RootShell.readSetting(ClipSync.KEY_MAX_RECORDS,
                    String.valueOf(ClipSync.DEFAULT_MAX_RECORDS));
            String readEnabled = RootShell.readSetting(KEY_READ_ENABLED, "0");
            mainHandler.post(() -> {
                selectValue(spWriteThrottle, R.array.throttle_values, writeThrottle, "1000");
                selectValue(spReadThrottle, R.array.throttle_values, readThrottle, "1000");
                selectValue(spMaxRecords, R.array.max_records_values, maxRecords,
                        String.valueOf(ClipSync.DEFAULT_MAX_RECORDS));
                swWriteEnabled.setChecked(!"0".equals(writeEnabled.trim()));
                swReadEnabled.setChecked("1".equals(readEnabled.trim()));
                updateThrottleVisibility();

                rootGranted = granted;
                swWriteEnabled.setEnabled(granted);
                swReadEnabled.setEnabled(granted);
                spWriteThrottle.setEnabled(granted);
                spReadThrottle.setEnabled(granted);
                spMaxRecords.setEnabled(granted);
                btnCheckUpdate.setEnabled(true);
                loading = false;
                if (!granted) {
                    Toast.makeText(this, R.string.need_root, Toast.LENGTH_LONG).show();
                }
            });
        }, "settings-load").start();
    }

    private void selectValue(Spinner spinner, int valuesRes, String value, String defValue) {
        String[] values = getResources().getStringArray(valuesRes);
        String target = value == null || value.trim().isEmpty() ? defValue : value.trim();
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) {
                spinner.setSelection(i, false);
                return;
            }
        }
    }

    private void saveSetting(String key, String value) {
        if (!rootGranted) {
            toast(getString(R.string.need_root));
            return;
        }
        new Thread(() -> {
            boolean ok = RootShell.writeSetting(key, value);
            mainHandler.post(() -> {
                if (ok) {
                    toast(getString(R.string.saved, key + "=" + value));
                } else {
                    toast(getString(R.string.save_failed, key));
                }
            });
        }, "settings-save").start();
    }

    private void checkUpdate() {
        toast(getString(R.string.checking_update));
        UpdateChecker.check(this, (ok, version, apkUrl, message) -> {
            String current = UpdateChecker.currentVersion(this);
            if (!ok) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.update_title)
                        .setMessage(getString(R.string.update_failed, message))
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            if (UpdateChecker.compare(version, current) > 0) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.update_title)
                        .setMessage(getString(R.string.update_found, version, current))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.confirm, (dialog, which) -> {
                            UpdateChecker.download(this, version, apkUrl);
                            toast(getString(R.string.download_started));
                        })
                        .show();
            } else {
                toast(getString(R.string.update_none, current));
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
