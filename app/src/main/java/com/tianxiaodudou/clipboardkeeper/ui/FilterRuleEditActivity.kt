package com.tianxiaodudou.clipboardkeeper.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.clipboardkeeper.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 新建 / 编辑一条过滤规则。 */
class FilterRuleEditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        private val OP_LABELS = arrayOf(
            "包含", "不包含", "开头是", "开头不是", "结尾是",
            "结尾不是", "完全等于", "不等于", "正则匹配", "正则不匹配"
        )
    }

    private class CondState(val op: String, val value: String)

    private var index = -1
    private var name by mutableStateOf("")
    private var enabled by mutableStateOf(true)
    private var anyLogic by mutableStateOf(false)
    private var negate by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var opPickerFor by mutableStateOf<Int?>(null)
    private val conditions = mutableStateListOf<CondState>()
    private var allRules: List<FilterRule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                EditScreen()
            }
        }
        load()
    }

    private fun load() {
        Thread {
            RootShell.ensureRoot()
            val loaded = FilterStore.load()
            runOnUiThread {
                allRules = loaded
                val rule = if (index in loaded.indices) loaded[index] else FilterRule()
                name = rule.name ?: ""
                enabled = rule.enabled
                anyLogic = rule.logicOr
                negate = rule.negate
                conditions.clear()
                if (rule.conditions.isEmpty()) {
                    conditions.add(CondState(FilterRule.OP_VALUES[0], ""))
                } else {
                    rule.conditions.forEach { conditions.add(CondState(it.op, it.value)) }
                }
            }
        }.start()
    }

    private fun opIndex(op: String): Int {
        val idx = FilterRule.OP_VALUES.indexOf(op)
        return if (idx >= 0) idx else 0
    }

    private fun save() {
        if (saving) return
        val rule = FilterRule()
        rule.name = name.trim()
        rule.enabled = enabled
        rule.logicOr = anyLogic
        rule.negate = negate
        conditions.forEach { state ->
            if (state.value.isNotEmpty()) {
                val cond = FilterRule.Cond()
                cond.op = state.op
                cond.value = state.value
                rule.conditions.add(cond)
            }
        }
        if (rule.conditions.isEmpty()) {
            toast(getString(R.string.filter_need_condition))
            return
        }
        val updated = allRules.toMutableList()
        if (index in updated.indices) updated[index] = rule else updated.add(rule)
        saving = true
        Thread {
            val ok = FilterStore.save(updated)
            runOnUiThread {
                saving = false
                if (ok) {
                    toast(getString(R.string.filter_saved))
                    finish()
                } else {
                    toast(getString(R.string.filter_save_failed, getString(R.string.refresh_internal)))
                }
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun EditScreen() {
        Scaffold(
            modifier = Modifier.fillMaxSize()
                .background(AppColors.pageBackground())
                .systemBarsPadding(),
            topBar = {
                SmallTopAppBar(
                    title = getString(if (index >= 0) R.string.filter_edit else R.string.filter_add),
                    navigationIcon = {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, text = getString(R.string.back), onClick = { finish() })
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    AppCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = getString(R.string.filter_name), fontSize = 13.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(6.dp))
                            TextField(
                                value = name,
                                onValueChange = { name = it },
                                label = getString(R.string.filter_name_hint)
                            )
                        }
                    }
                }
                item {
                    AppCard {
                        SwitchPreference(
                            title = getString(R.string.filter_enabled),
                            checked = enabled,
                            onCheckedChange = { enabled = it }
                        )
                    }
                }
                item {
                    AppCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(text = getString(R.string.filter_logic), fontSize = 13.sp, color = Color.Gray)
                            Spacer(modifier = Modifier.height(4.dp))
                            RadioButtonPreference(
                                title = getString(R.string.filter_logic_all),
                                selected = !anyLogic,
                                onClick = { anyLogic = false }
                            )
                            RadioButtonPreference(
                                title = getString(R.string.filter_logic_any),
                                selected = anyLogic,
                                onClick = { anyLogic = true }
                            )
                        }
                    }
                }
                item {
                    AppCard {
                        SwitchPreference(
                            title = getString(R.string.filter_negate),
                            checked = negate,
                            onCheckedChange = { negate = it }
                        )
                    }
                }
                item {
                    Text(
                        text = getString(R.string.filter_conditions),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(conditions.size) { position ->
                    AppCard {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                                    text = OP_LABELS[opIndex(conditions[position].op)],
                                    onClick = { opPickerFor = position }
                                )
                                TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                                    text = getString(R.string.filter_remove_condition),
                                    onClick = {
                                        if (conditions.size > 1) conditions.removeAt(position)
                                    }
                                )
                            }
                            TextField(
                                value = conditions[position].value,
                                onValueChange = { conditions[position] = CondState(conditions[position].op, it) },
                                label = getString(R.string.filter_value_hint)
                            )
                        }
                    }
                }
                item {
                    TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                        text = getString(R.string.filter_add_condition),
                        onClick = { conditions.add(CondState(FilterRule.OP_VALUES[0], "")) }
                    )
                }
                item {
                    Button(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                        onClick = { save() },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getString(R.string.filter_save))
                    }
                }
            }

            val position = opPickerFor
            if (position != null && position < conditions.size) {
                OverlayDialog(
                    title = getString(R.string.filter_conditions),
                    show = true,
                    onDismissRequest = { opPickerFor = null }
                ) {
                    Column {
                        FilterRule.OP_VALUES.forEachIndexed { opIdx, op ->
                            RadioButtonPreference(
                                title = OP_LABELS[opIdx],
                                selected = conditions[position].op == op,
                                onClick = {
                                    conditions[position] = CondState(op, conditions[position].value)
                                    opPickerFor = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
