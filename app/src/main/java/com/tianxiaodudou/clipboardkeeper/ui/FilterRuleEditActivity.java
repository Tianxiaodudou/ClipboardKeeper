package com.tianxiaodudou.clipboardkeeper.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.tianxiaodudou.clipboardkeeper.R;

import java.util.ArrayList;
import java.util.List;

/** 新建 / 编辑一条过滤规则：名称、启用、与或、反向、多条条件。 */
public class FilterRuleEditActivity extends AppCompatActivity {

    public static final String EXTRA_INDEX = "index";

    private final List<FilterRule> allRules = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int index = -1;
    private boolean loaded;

    private EditText etName;
    private SwitchCompat swEnabled;
    private RadioGroup rgLogic;
    private SwitchCompat swNegate;
    private LinearLayout conditionContainer;
    private boolean logicOr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_rule_edit);
        applyInsets();

        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        ((TextView) findViewById(R.id.tvTitle)).setText(
                index >= 0 ? R.string.filter_edit : R.string.filter_add);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        etName = findViewById(R.id.etName);
        swEnabled = findViewById(R.id.swEnabled);
        rgLogic = findViewById(R.id.rgLogic);
        swNegate = findViewById(R.id.swNegate);
        conditionContainer = findViewById(R.id.conditionContainer);
        Button btnAddCondition = findViewById(R.id.btnAddCondition);
        Button btnSave = findViewById(R.id.btnSave);

        btnAddCondition.setOnClickListener(v -> addRow(new FilterRule.Cond()));
        btnSave.setOnClickListener(v -> save());
        load();
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

    private void load() {
        new Thread(() -> {
            RootShell.ensureRoot();
            List<FilterRule> loadedRules = FilterStore.load();
            mainHandler.post(() -> {
                allRules.clear();
                allRules.addAll(loadedRules);
                loaded = true;
                FilterRule rule = (index >= 0 && index < allRules.size())
                        ? allRules.get(index) : new FilterRule();
                etName.setText(rule.name);
                swEnabled.setChecked(rule.enabled);
                logicOr = rule.logicOr;
                ((RadioButton) findViewById(logicOr ? R.id.rbAny : R.id.rbAll)).setChecked(true);
                swNegate.setChecked(rule.negate);
                rgLogic.setOnCheckedChangeListener((group, checkedId) ->
                        logicOr = checkedId == R.id.rbAny);
                conditionContainer.removeAllViews();
                rows.clear();
                if (rule.conditions.isEmpty()) {
                    addRow(new FilterRule.Cond());
                } else {
                    for (FilterRule.Cond cond : rule.conditions) addRow(cond);
                }
            });
        }, "filter-edit-load").start();
    }

    private void addRow(FilterRule.Cond cond) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.filter_op_entries, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < FilterRule.OP_VALUES.length; i++) {
            if (FilterRule.OP_VALUES[i].equals(cond.op)) {
                spinner.setSelection(i, false);
                break;
            }
        }
        row.addView(spinner);

        EditText value = new EditText(this);
        value.setHint(R.string.filter_value_hint);
        value.setText(cond.value);
        value.setSingleLine(false);
        value.setMaxLines(4);
        value.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        row.addView(value);

        Button remove = new Button(this, null, android.R.attr.borderlessButtonStyle);
        remove.setText(R.string.filter_remove_condition);
        row.addView(remove);

        Row holder = new Row(spinner, value);
        remove.setOnClickListener(v -> {
            conditionContainer.removeView(row);
            rows.remove(holder);
        });
        rows.add(holder);
        conditionContainer.addView(row);
    }

    private void save() {
        if (!loaded) return;
        String name = etName.getText().toString().trim();
        FilterRule rule = new FilterRule();
        rule.name = name;
        rule.enabled = swEnabled.isChecked();
        rule.logicOr = logicOr;
        rule.negate = swNegate.isChecked();
        for (Row row : rows) {
            FilterRule.Cond cond = new FilterRule.Cond();
            int pos = row.spinner.getSelectedItemPosition();
            cond.op = FilterRule.OP_VALUES[Math.max(0, Math.min(pos, FilterRule.OP_VALUES.length - 1))];
            cond.value = row.value.getText().toString().trim();
            rule.conditions.add(cond);
        }
        if (rule.conditions.isEmpty()) {
            toast(getString(R.string.filter_need_condition));
            return;
        }
        if (index >= 0 && index < allRules.size()) {
            allRules.set(index, rule);
        } else {
            allRules.add(rule);
        }
        Button btn = findViewById(R.id.btnSave);
        btn.setEnabled(false);
        new Thread(() -> {
            boolean ok = FilterStore.save(allRules);
            mainHandler.post(() -> {
                if (ok) {
                    toast(getString(R.string.filter_saved));
                    finish();
                } else {
                    btn.setEnabled(true);
                    toast(getString(R.string.filter_save_failed, getString(R.string.refresh_internal)));
                }
            });
        }, "filter-edit-save").start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class Row {
        final Spinner spinner;
        final EditText value;

        Row(Spinner spinner, EditText value) {
            this.spinner = spinner;
            this.value = value;
        }
    }
}
