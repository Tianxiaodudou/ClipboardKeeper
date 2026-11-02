package com.tianxiaodudou.clipboardkeeper.ui;

import android.graphics.Color;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.tianxiaodudou.clipboardkeeper.R;

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
    private TextView tvVersionState;
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
        tvVersionState = findViewById(R.id.tvVersionState);
        renderVersionState(R.string.state_idle, null, 0);
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

        findViewById(R.id.rowFilterRules).setOnClickListener(v ->
                startActivity(new Intent(this, FilterRulesActivity.class)));
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
                    toast(getString(R.string.saved, settingLabel(key), valueLabel(key, value)));
                } else {
                    toast(getString(R.string.save_failed, settingLabel(key)));
                }
            });
        }, "settings-save").start();
    }

    /** 设置项的中文名，用于提示文案 */
    private String settingLabel(String key) {
        switch (key) {
            case KEY_WRITE_ENABLED:
                return getString(R.string.set_write_enabled);
            case KEY_WRITE_THROTTLE:
                return getString(R.string.set_write_throttle);
            case KEY_READ_ENABLED:
                return getString(R.string.set_read_enabled);
            case KEY_READ_THROTTLE:
                return getString(R.string.set_read_throttle);
            case ClipSync.KEY_MAX_RECORDS:
                return getString(R.string.set_max_records);
            default:
                return key;
        }
    }

    /** 设置值的中文展示，避免把 1 / 0 / 1000 这些原始值直接弹给用户 */
    private String valueLabel(String key, String value) {
        switch (key) {
            case KEY_WRITE_ENABLED:
            case KEY_READ_ENABLED:
                return "1".equals(value)
                        ? getString(R.string.state_on) : getString(R.string.state_off);
            case KEY_WRITE_THROTTLE:
            case KEY_READ_THROTTLE:
                return "0".equals(value)
                        ? getString(R.string.value_no_throttle) : getString(R.string.value_ms, value);
            case ClipSync.KEY_MAX_RECORDS:
                return getString(R.string.value_records, value);
            default:
                return value;
        }
    }

    private void checkUpdate() {
        renderVersionState(R.string.state_checking, null, 0);
        btnCheckUpdate.setEnabled(false);
        UpdateChecker.check(this, result -> {
            btnCheckUpdate.setEnabled(true);
            switch (result.state) {
                case UpdateChecker.STATE_NEWER:
                    renderVersionState(R.string.state_newer, result.remoteVersion, R.color.ok_green);
                    UpdateDialog.show(this, result.remoteVersion, result.apkUrl, result.changelog);
                    break;
                case UpdateChecker.STATE_SAME:
                    renderVersionState(R.string.state_same, null, R.color.ok_green);
                    toast(getString(R.string.update_none, result.localVersion));
                    break;
                case UpdateChecker.STATE_LOCAL_AHEAD:
                    renderVersionState(R.string.state_local_ahead, result.remoteVersion, R.color.text_secondary);
                    toast(getString(R.string.state_local_ahead_toast,
                            result.localVersion, result.remoteVersion));
                    break;
                default:
                    int textRes;
                    if (result.errorKind == UpdateChecker.ERROR_NETWORK) {
                        textRes = R.string.state_error_network;
                    } else if (result.errorKind == UpdateChecker.ERROR_REPO) {
                        textRes = R.string.state_error_repo;
                    } else {
                        textRes = R.string.state_error_unknown;
                    }
                    renderVersionState(textRes, result.message, R.color.blocked_red);
            }
        });
    }

    /** 检查更新按钮下方：始终显示本地版本，后面跟最近一次检查结果 */
    private void renderVersionState(int statusRes, String arg, int colorRes) {
        String local = UpdateChecker.currentVersion(this);
        String status = arg == null ? getString(statusRes) : getString(statusRes, arg);
        tvVersionState.setText(getString(R.string.version_state_base, local) + " · " + status);
        if (colorRes != 0) {
            tvVersionState.setTextColor(ContextCompat.getColor(this, colorRes));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
