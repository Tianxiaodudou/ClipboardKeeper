package com.tianxiaodudou.clipboardkeeper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.clipboardkeeper.R
import com.tianxiaodudou.updater.Updater
import com.tianxiaodudou.updater.compose.showUpdateDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 设置页。更新相关一律交给 android-updater v1.5.1 的 showUpdateDialog：
 * 弹窗自带「访问令牌 / PAT 七步教程 / 401·403·404 错误分类」，这里不再自己实现任何检查逻辑。
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        const val KEY_WRITE_ENABLED = "clipboard_keeper_write_enabled"
        const val KEY_WRITE_THROTTLE = "clipboard_keeper_broadcast_throttle_ms"
        const val KEY_READ_ENABLED = "clipboard_keeper_read_enabled"
        const val KEY_READ_THROTTLE = "clipboard_keeper_read_throttle_ms"

        private val THROTTLE_VALUES = listOf("0", "500", "1000", "2000", "5000")
        private val MAX_RECORD_VALUES = listOf("1000", "5000", "10000", "20000", "50000")
    }

    private enum class Picker { WRITE_THROTTLE, READ_THROTTLE, MAX_RECORDS }

    private var writeEnabled by mutableStateOf(true)
    private var readEnabled by mutableStateOf(false)
    private var writeThrottle by mutableStateOf("1000")
    private var readThrottle by mutableStateOf("1000")
    private var maxRecords by mutableStateOf("10000")
    private var rootGranted by mutableStateOf(false)
    private var picker by mutableStateOf<Picker?>(null)
    private var currentVersion by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentVersion = readVersionName()
        Updater.init(
            applicationContext,
            Updater.Config(
                owner = "Tianxiaodudou",
                repo = "ClipboardKeeper",
                currentVersion = currentVersion
            )
        )
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                SettingsScreen()
            }
        }
        loadSettings()
    }

    private fun readVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun loadSettings() {
        Thread {
            val granted = RootShell.ensureRoot()
            val write = RootShell.readSetting(KEY_WRITE_ENABLED, "1")
            val writeMs = RootShell.readSetting(KEY_WRITE_THROTTLE, "1000")
            val read = RootShell.readSetting(KEY_READ_ENABLED, "0")
            val readMs = RootShell.readSetting(KEY_READ_THROTTLE, "1000")
            val max = RootShell.readSetting(ClipSync.KEY_MAX_RECORDS, "10000")
            runOnUiThread {
                rootGranted = granted
                writeEnabled = write.trim() != "0"
                readEnabled = read.trim() == "1"
                writeThrottle = writeMs.trim()
                readThrottle = readMs.trim()
                maxRecords = max.trim()
                if (!granted) toast(getString(R.string.need_root))
            }
        }.start()
    }

    private fun saveSetting(key: String, value: String, label: String) {
        if (!rootGranted) {
            toast(getString(R.string.need_root))
            return
        }
        Thread {
            val ok = RootShell.writeSetting(key, value)
            runOnUiThread {
                if (ok) toast(getString(R.string.saved, label, valueLabel(key, value)))
                else toast(getString(R.string.save_failed, label))
            }
        }.start()
    }

    private fun valueLabel(key: String, value: String): String = when (key) {
        KEY_WRITE_THROTTLE, KEY_READ_THROTTLE ->
            if (value == "0") getString(R.string.value_no_throttle) else getString(R.string.value_ms, value)
        ClipSync.KEY_MAX_RECORDS -> getString(R.string.value_records, value)
        else -> if (value == "1") getString(R.string.state_on) else getString(R.string.state_off)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun SettingsScreen() {
        Scaffold(
            modifier = Modifier.fillMaxSize()
                .background(AppColors.pageBackground())
                .systemBarsPadding(),
            topBar = {
                AppTopBar(
                    title = getString(R.string.settings_title),
                    navigationIcon = {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius,
                            text = getString(R.string.back), onClick = { finish() })
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(AppDims.PagePadding),
                verticalArrangement = Arrangement.spacedBy(AppDims.Gap)
            ) {
                item {
                    AppCard {
                        SwitchPreference(
                            title = getString(R.string.set_write_enabled),
                            checked = writeEnabled,
                            onCheckedChange = {
                                writeEnabled = it
                                saveSetting(KEY_WRITE_ENABLED, if (it) "1" else "0",
                                    getString(R.string.set_write_enabled))
                            }
                        )
                    }
                }
                if (writeEnabled) {
                    item {
                        PickerRow(
                            title = getString(R.string.set_write_throttle),
                            value = valueLabel(KEY_WRITE_THROTTLE, writeThrottle)
                        ) { picker = Picker.WRITE_THROTTLE }
                    }
                }
                item {
                    AppCard {
                        SwitchPreference(
                            title = getString(R.string.set_read_enabled),
                            checked = readEnabled,
                            onCheckedChange = {
                                readEnabled = it
                                saveSetting(KEY_READ_ENABLED, if (it) "1" else "0",
                                    getString(R.string.set_read_enabled))
                            }
                        )
                    }
                }
                if (readEnabled) {
                    item {
                        PickerRow(
                            title = getString(R.string.set_read_throttle),
                            value = valueLabel(KEY_READ_THROTTLE, readThrottle)
                        ) { picker = Picker.READ_THROTTLE }
                    }
                }
                item {
                    PickerRow(
                        title = getString(R.string.set_max_records),
                        value = valueLabel(ClipSync.KEY_MAX_RECORDS, maxRecords)
                    ) { picker = Picker.MAX_RECORDS }
                }
                item { FilterRulesEntry() }
                item {
                    Button(
                        onClick = {
                            // 完全使用库自带的更新弹窗（含 PAT 入口与错误分类）
                            showUpdateDialog(this@SettingsActivity)
                        },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        insideMargin = CompactButton.InsideMargin,
                        minHeight = CompactButton.MinHeight,
                        minWidth = CompactButton.MinWidth,
                        cornerRadius = CompactButton.CornerRadius,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getString(R.string.set_check_update))
                    }
                }
                item {
                    Text(
                        text = getString(R.string.version_state_base, currentVersion),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                item {
                    Text(
                        text = getString(R.string.about_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                item {
                    AppCard {
                        Text(
                            text = getString(R.string.about_body),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(AppDims.CardPadding)
                        )
                    }
                }
                item {
                    Text(
                        text = getString(R.string.set_hint),
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
            }

            val chosen = picker
            if (chosen != null) {
                val (title, values, selected) = when (chosen) {
                    Picker.WRITE_THROTTLE -> Triple(
                        getString(R.string.set_write_throttle), THROTTLE_VALUES, writeThrottle
                    )
                    Picker.READ_THROTTLE -> Triple(
                        getString(R.string.set_read_throttle), THROTTLE_VALUES, readThrottle
                    )
                    Picker.MAX_RECORDS -> Triple(
                        getString(R.string.set_max_records), MAX_RECORD_VALUES, maxRecords
                    )
                }
                OverlayDialog(
                    title = title,
                    show = true,
                    onDismissRequest = { picker = null }
                ) {
                    Column {
                        values.forEach { value ->
                            RadioButtonPreference(
                                title = if (chosen == Picker.MAX_RECORDS) getString(R.string.value_records, value)
                                else if (value == "0") getString(R.string.value_no_throttle)
                                else getString(R.string.value_ms, value),
                                selected = value == selected,
                                onClick = {
                                    when (chosen) {
                                        Picker.WRITE_THROTTLE -> {
                                            writeThrottle = value
                                            saveSetting(KEY_WRITE_THROTTLE, value,
                                                getString(R.string.set_write_throttle))
                                        }
                                        Picker.READ_THROTTLE -> {
                                            readThrottle = value
                                            saveSetting(KEY_READ_THROTTLE, value,
                                                getString(R.string.set_read_throttle))
                                        }
                                        Picker.MAX_RECORDS -> {
                                            maxRecords = value
                                            saveSetting(ClipSync.KEY_MAX_RECORDS, value,
                                                getString(R.string.set_max_records))
                                        }
                                    }
                                    picker = null
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PickerRow(title: String, value: String, onClick: () -> Unit) {
        AppCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AppDims.CardPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = value, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "›", fontSize = 18.sp, color = Color.Gray)
            }
        }
    }

    @Composable
    private fun FilterRulesEntry() {
        AppCard(
            modifier = Modifier.fillMaxWidth().clickable {
                startActivity(Intent(this@SettingsActivity, FilterRulesActivity::class.java))
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(AppDims.CardPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = getString(R.string.set_filter_rules), fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = getString(R.string.set_filter_rules_hint),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Text(text = "›", fontSize = 18.sp, color = Color.Gray)
            }
        }
    }
}
