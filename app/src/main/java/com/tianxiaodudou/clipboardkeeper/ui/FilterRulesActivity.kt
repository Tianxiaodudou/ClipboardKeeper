package com.tianxiaodudou.clipboardkeeper.ui

import android.content.Intent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 过滤规则列表：启用/停用、编辑、删除。 */
class FilterRulesActivity : ComponentActivity() {

    private var rules by mutableStateOf<List<FilterRule>>(emptyList())
    private var rootGranted by mutableStateOf(false)
    private var pendingDelete by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                RulesScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        Thread {
            val granted = RootShell.ensureRoot()
            val loaded = if (granted) FilterStore.load() else emptyList()
            runOnUiThread {
                rootGranted = granted
                rules = loaded
            }
        }.start()
    }

    private fun persist(updated: List<FilterRule>) {
        rules = updated
        Thread {
            val ok = FilterStore.save(ArrayList(updated))
            if (!ok) runOnUiThread {
                toast(getString(R.string.filter_save_failed, getString(R.string.refresh_internal)))
            }
        }.start()
    }

    private fun openEditor(index: Int) {
        if (!rootGranted) {
            toast(getString(R.string.need_root))
            return
        }
        startActivity(
            Intent(this, FilterRuleEditActivity::class.java)
                .putExtra(FilterRuleEditActivity.EXTRA_INDEX, index)
        )
    }

    private fun ruleName(rule: FilterRule): String =
        rule.name?.takeIf { it.isNotBlank() } ?: getString(R.string.filter_unnamed)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun RulesScreen() {
        Scaffold(
            modifier = Modifier.fillMaxSize()
                .background(AppColors.pageBackground())
                .systemBarsPadding(),
            topBar = {
                SmallTopAppBar(
                    title = getString(R.string.filter_title),
                    navigationIcon = {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, text = getString(R.string.back), onClick = { finish() })
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (rules.isEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = getString(R.string.filter_empty),
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(rules) { index, rule ->
                            AppCard {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    SwitchPreference(
                                        title = if (rule.enabled) ruleName(rule)
                                        else getString(R.string.filter_disabled_name, ruleName(rule)),
                                        checked = rule.enabled,
                                        onCheckedChange = { checked ->
                                            rule.enabled = checked
                                            persist(rules.toList())
                                        }
                                    )
                                    Text(
                                        text = rule.summary(this@FilterRulesActivity),
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                                    )
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                                            text = getString(R.string.filter_edit),
                                            onClick = { openEditor(index) }
                                        )
                                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                                            text = getString(R.string.filter_delete),
                                            onClick = { pendingDelete = index }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Button(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                    onClick = { openEditor(-1) },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(getString(R.string.filter_add))
                }
            }

            val index = pendingDelete
            if (index != null && index < rules.size) {
                OverlayDialog(
                    title = getString(R.string.filter_delete),
                    summary = getString(
                        R.string.filter_delete_confirm,
                        ruleName(rules[index])
                    ),
                    show = true,
                    onDismissRequest = { pendingDelete = null }
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                            text = getString(R.string.cancel),
                            onClick = { pendingDelete = null },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                            text = getString(R.string.confirm),
                            onClick = {
                                val updated = rules.toMutableList()
                                updated.removeAt(index)
                                pendingDelete = null
                                persist(updated)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
