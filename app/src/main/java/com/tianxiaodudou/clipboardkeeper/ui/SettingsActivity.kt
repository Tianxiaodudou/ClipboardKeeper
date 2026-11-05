package com.tianxiaodudou.clipboardkeeper.ui

import android.content.Intent
import android.os.Bundle
import java.io.File
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.clipboardkeeper.R
import com.tianxiaodudou.updater.Updater
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 设置页：全部使用 Miuix 组件，业务逻辑沿用 RootShell / UpdateChecker / FilterStore。 */
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
    private var versionState by mutableStateOf("")
    private var versionColor by mutableStateOf(Color.Gray)
    private var checking by mutableStateOf(false)
    private var picker by mutableStateOf<Picker?>(null)
    private var updateVersion by mutableStateOf<String?>(null)
    private var pendingRelease by mutableStateOf<Updater.Release?>(null)
    private var updateLog by mutableStateOf("")
    private var downloadPercent by mutableStateOf<Int?>(null)
    private var downloadError by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        versionState = getString(R.string.version_state_base, UpdateChecker.currentVersion(this)) +
                " · " + getString(R.string.state_idle)
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                SettingsScreen()
            }
        }
        loadSettings()
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

    private fun checkUpdate() {
        checking = true
        versionState = getString(R.string.version_state_base, UpdateChecker.currentVersion(this)) +
                " · " + getString(R.string.state_checking)
        versionColor = Color.Gray
        val current = UpdateChecker.currentVersion(this)
        Updater.init(
            Updater.Config(
                owner = "Tianxiaodudou",
                repo = "ClipboardKeeper",
                currentVersion = current
            )
        )
        lifecycleScope.launch {
            val release = Updater.check(current)
            checking = false
            if (release != null && UpdateChecker.compare(release.version, current) > 0) {
                versionState = getString(R.string.version_state_base, current) + " · " +
                        getString(R.string.state_newer, release.version)
                versionColor = Color(0xFF2E7D32)
                pendingRelease = release
                updateVersion = release.version
                // 库只给 Release 正文，正文为空时用四路兜底再取一次
                updateLog = release.notes.ifBlank { ChangelogFetcher.fetch(release.version, "") }
                downloadPercent = null
                downloadError = null
                return@launch
            }
            // 库返回 null 分不清"已是最新/网络问题/仓库问题"，用原有诊断补一次
            UpdateChecker.check(this@SettingsActivity) { result ->
                val base = getString(R.string.version_state_base, result.localVersion) + " · "
                when (result.state) {
                    UpdateChecker.STATE_SAME -> {
                        versionState = base + getString(R.string.state_same)
                        versionColor = Color(0xFF2E7D32)
                    }
                    UpdateChecker.STATE_LOCAL_AHEAD -> {
                        versionState = base + getString(R.string.state_local_ahead, result.remoteVersion)
                        versionColor = Color.Gray
                    }
                    UpdateChecker.STATE_ERROR -> {
                        val text = when (result.errorKind) {
                            UpdateChecker.ERROR_NETWORK -> getString(R.string.state_error_network, result.message)
                            UpdateChecker.ERROR_REPO -> getString(R.string.state_error_repo, result.message)
                            else -> getString(R.string.state_error_unknown, result.message)
                        }
                        versionState = base + text
                        versionColor = Color(0xFFD32F2F)
                    }
                    else -> {
                        versionState = base + getString(R.string.state_newer, result.remoteVersion)
                        versionColor = Color(0xFF2E7D32)
                    }
                }
            }
        }
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
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, text = getString(R.string.back), onClick = { finish() })
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(AppDims.PagePadding),
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
                    Button(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                        onClick = { if (!checking) checkUpdate() },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(getString(R.string.set_check_update))
                    }
                }
                item {
                    Text(
                        text = versionState,
                        color = versionColor,
                        fontSize = 12.sp
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

            val current = picker
            if (current != null) {
                val (title, values, selected) = when (current) {
                    Picker.WRITE_THROTTLE -> Triple(getString(R.string.set_write_throttle),
                        THROTTLE_VALUES, writeThrottle)
                    Picker.READ_THROTTLE -> Triple(getString(R.string.set_read_throttle),
                        THROTTLE_VALUES, readThrottle)
                    Picker.MAX_RECORDS -> Triple(getString(R.string.set_max_records),
                        MAX_RECORD_VALUES, maxRecords)
                }
                OverlayDialog(
                    title = title,
                    show = true,
                    onDismissRequest = { picker = null }
                ) {
                    Column {
                        values.forEach { value ->
                            RadioButtonPreference(
                                title = if (current == Picker.MAX_RECORDS) getString(R.string.value_records, value)
                                else if (value == "0") getString(R.string.value_no_throttle)
                                else getString(R.string.value_ms, value),
                                selected = value == selected,
                                onClick = {
                                    when (current) {
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

            val newVersion = updateVersion
            if (newVersion != null) {
                OverlayDialog(
                    title = getString(R.string.update_title) + " · " + getString(R.string.update_changelog),
                    show = true,
                    onDismissRequest = { if (downloadPercent == null) updateVersion = null }
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = getString(
                                R.string.update_found, newVersion,
                                UpdateChecker.currentVersion(this@SettingsActivity)
                            ),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = updateLog.ifBlank { getString(R.string.update_no_changelog) },
                                fontSize = 13.sp
                            )
                        }
                        val percent = downloadPercent
                        if (percent != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            UpdateProgress(percent)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = getString(R.string.update_downloading, percent),
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        val error = downloadError
                        if (error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = getString(R.string.update_download_failed, error),
                                fontSize = 12.sp,
                                color = Color(0xFFD32F2F)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                                text = getString(R.string.cancel),
                                onClick = { if (downloadPercent == null) updateVersion = null },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, cornerRadius = CompactButton.CornerRadius, 
                                text = getString(R.string.update_download),
                                onClick = { startDownload() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun UpdateProgress(percent: Int) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x22000000))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent / 100f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF1565C0))
            )
        }
    }

    private fun startDownload() {
        val release = pendingRelease ?: return
        downloadError = null
        downloadPercent = 0
        lifecycleScope.launch {
            val apk = Updater.download(this@SettingsActivity, release) { percent ->
                runOnUiThread { downloadPercent = percent }
            }
            runOnUiThread {
                if (apk != null) {
                    updateVersion = null
                    downloadPercent = null
                    toast(getString(R.string.update_installing))
                    val installed = Updater.install(this@SettingsActivity, apk)
                    if (!installed) {
                        downloadError = getString(R.string.update_install_failed)
                    }
                } else {
                    downloadPercent = null
                    downloadError = getString(R.string.update_download_failed, getString(R.string.refresh_internal))
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
