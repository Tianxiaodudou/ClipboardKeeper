package com.tianxiaodudou.clipboardkeeper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 主界面：列表 + 刷新 / 清空 / 设置，全部使用 Miuix 组件。 */
class MainActivity : ComponentActivity() {

    private var records by mutableStateOf<List<ClipRecord>>(emptyList())
    private var rootGranted by mutableStateOf(false)
    private var expandedIds by mutableStateOf<Set<Long>>(emptySet())
    private var showClearDialog by mutableStateOf(false)

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private lateinit var updateReceiver: ClipUpdateReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateReceiver = ClipUpdateReceiver { if (rootGranted) refresh(false) }
        setContent {
            MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                MainScreen()
            }
        }
        checkRoot()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(updateReceiver, ClipUpdateReceiver.intentFilter(), Context.RECEIVER_EXPORTED)
        if (rootGranted) refresh(false)
    }

    override fun onPause() {
        try {
            unregisterReceiver(updateReceiver)
        } catch (_: Throwable) {
        }
        super.onPause()
    }

    private fun checkRoot() {
        Thread {
            val granted = RootShell.ensureRoot()
            runOnUiThread {
                rootGranted = granted
                if (granted) refresh(false) else records = emptyList()
            }
        }.start()
    }

    private fun refresh(manual: Boolean) {
        if (!rootGranted) return
        if (manual) toast(getString(R.string.refreshing))
        ClipSync.refresh(this) { ok, message, inserted, list ->
            if (ok) {
                records = list ?: emptyList()
                if (manual) toast(getString(R.string.refresh_done, inserted))
            } else if (manual) {
                toast(
                    if (message == null) getString(R.string.refresh_busy)
                    else getString(R.string.refresh_failed, message)
                )
            }
        }
    }

    private fun clearAll() {
        ClipSync.clearAll(this) {
            records = emptyList()
            toast(getString(R.string.cleared))
            refresh(false)
        }
    }

    private fun copy(label: String, text: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            toast(getString(R.string.copied, label))
        } catch (_: Throwable) {
            toast(getString(R.string.copy_failed, getString(R.string.refresh_internal)))
        }
    }

    private fun toggleExpanded(record: ClipRecord) {
        expandedIds = if (expandedIds.contains(record.id)) expandedIds - record.id
        else expandedIds + record.id
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @Composable
    private fun MainScreen() {
        Scaffold(
            modifier = Modifier.systemBarsPadding(),
            topBar = {
                SmallTopAppBar(
                    title = getString(R.string.app_name),
                    actions = {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, text = getString(R.string.refresh), onClick = { refresh(true) })
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, text = getString(R.string.clear), onClick = { showClearDialog = true })
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                            text = getString(R.string.settings),
                            onClick = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
                        )
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (!rootGranted) {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = getString(R.string.root_missing),
                            color = Color(0xFFB25000),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                if (records.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(text = getString(R.string.empty_list), fontSize = 15.sp, color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(records, key = { it.id }) { record ->
                            ClipCard(record)
                        }
                    }
                }
            }
            if (showClearDialog) {
                OverlayDialog(
                    title = getString(R.string.clear_confirm_title),
                    summary = getString(R.string.clear_confirm_msg),
                    show = true,
                    onDismissRequest = { showClearDialog = false }
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                            text = getString(R.string.cancel),
                            onClick = { showClearDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(insideMargin = CompactButton.InsideMargin, minHeight = CompactButton.MinHeight, minWidth = CompactButton.MinWidth, 
                            text = getString(R.string.confirm),
                            onClick = {
                                showClearDialog = false
                                clearAll()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ClipCard(record: ClipRecord) {
        val blocked = record.operation == "blocked"
        val isWrite = record.operation == "write"
        val badge = when {
            blocked -> getString(R.string.op_blocked)
            isWrite -> getString(R.string.op_write)
            else -> getString(R.string.op_read)
        }
        val badgeColor = when {
            blocked -> Color(0xFFD32F2F)
            isWrite -> Color(0xFF1565C0)
            else -> Color.Gray
        }
        val expanded = expandedIds.contains(record.id)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { toggleExpanded(record) },
                        onLongClick = { copy(getString(R.string.copy_content), record.content) }
                    )
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = badge, color = badgeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeFormat.format(Date(record.timestamp)),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = record.sourcePackage,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.combinedClickable(
                        onClick = { },
                        onLongClick = { copy(getString(R.string.copy_package), record.sourcePackage) }
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = record.content,
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
