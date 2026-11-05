package com.tianxiaodudou.updater.compose

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.updater.Updater
import com.tianxiaodudou.updater.Updater.CheckResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 更新弹窗的界面状态。 */
class UpdateUiState {
    var release by mutableStateOf<Updater.Release?>(null)
    var progress by mutableIntStateOf(0)
    var downloading by mutableStateOf(false)
    var failure by mutableStateOf<CheckResult.Failure?>(null)
    var upToDate by mutableStateOf<String?>(null)
    var downloadError by mutableStateOf<String?>(null)
    var hasToken by mutableStateOf(false)
}

/** Miuix 风格的更新弹窗内容（预览图用的就是它）。 */
@Composable
fun UpdateDialogContent(
    state: UpdateUiState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenPat: () -> Unit,
    onConfirm: () -> Unit,
) {
    MiuixTheme {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.surface)
                .padding(22.dp),
        ) {
            val release = state.release
            val failure = state.failure
            val upToDate = state.upToDate
            when {
                release != null -> ReleaseBody(
                    state = state,
                    release = release,
                    onDismiss = onDismiss,
                    onOpenPat = onOpenPat,
                    onConfirm = onConfirm,
                )

                failure != null -> FailureBody(
                    failure = failure,
                    hasToken = state.hasToken,
                    onDismiss = onDismiss,
                    onRetry = onRetry,
                    onOpenPat = onOpenPat,
                )

                upToDate != null -> UpToDateBody(
                    version = upToDate,
                    onDismiss = onDismiss,
                    onOpenPat = onOpenPat,
                )

                else -> CheckingBody(onDismiss = onDismiss, onOpenPat = onOpenPat)
            }
        }
    }
}

/*
 * 失败文案里的「是否已配置令牌」由 showUpdateDialog 在每次检查后写入 state，
 * 避免为一行文案把 Context 传遍整棵 Compose 树。
 */

@Composable
private fun ReleaseBody(
    state: UpdateUiState,
    release: Updater.Release,
    onDismiss: () -> Unit,
    onOpenPat: () -> Unit,
    onConfirm: () -> Unit,
) {
    Text("发现新版本 " + release.tag, fontSize = 19.sp)
    Spacer(Modifier.height(10.dp))
    Text(
        release.notes.ifBlank { "有新版本可用，建议立即更新。" },
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    )
    if (state.downloading || state.progress > 0) {
        Spacer(Modifier.height(14.dp))
        Progress(state.progress)
        Spacer(Modifier.height(8.dp))
        Text(progressText(state.progress, release.sizeBytes), fontSize = 12.sp)
    }
    state.downloadError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, fontSize = 12.sp, color = Color(0xFFD32F2F))
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UpdaterButton("访问令牌", onClick = onOpenPat, enabled = !state.downloading)
        Spacer(Modifier.weight(1f))
        UpdaterButton("以后再说", onClick = onDismiss, enabled = !state.downloading)
        Spacer(Modifier.width(4.dp))
        UpdaterButton(
            if (state.downloading) "下载中" else "立即更新",
            onClick = onConfirm,
            enabled = !state.downloading,
        )
    }
}

@Composable
private fun CheckingBody(onDismiss: () -> Unit, onOpenPat: () -> Unit) {
    Text("检查更新", fontSize = 19.sp)
    Spacer(Modifier.height(10.dp))
    Text("正在连接 GitHub Releases…", fontSize = 13.sp)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UpdaterButton("访问令牌", onClick = onOpenPat)
        Spacer(Modifier.weight(1f))
        UpdaterButton("关闭", onClick = onDismiss)
    }
}

@Composable
private fun FailureBody(
    failure: CheckResult.Failure,
    hasToken: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onOpenPat: () -> Unit,
) {
    Text("检查更新失败", fontSize = 19.sp)
    Spacer(Modifier.height(10.dp))
    Text(failure.message(hasToken), fontSize = 13.sp, lineHeight = 19.sp)
    if (failure.detail.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(failure.detail, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
    if (failure.needsToken) {
        Spacer(Modifier.height(8.dp))
        Text(
            "点「填写令牌」按弹窗里的 7 个步骤获取 PAT；公开仓库也可以填，配额更高。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UpdaterButton("关闭", onClick = onDismiss)
        Spacer(Modifier.weight(1f))
        UpdaterButton("重试", onClick = onRetry)
        Spacer(Modifier.width(2.dp))
        UpdaterButton(if (failure.needsToken) "填写令牌" else "访问令牌", onClick = onOpenPat)
    }
}

@Composable
private fun UpToDateBody(version: String, onDismiss: () -> Unit, onOpenPat: () -> Unit) {
    Text("已是最新版本", fontSize = 19.sp)
    Spacer(Modifier.height(10.dp))
    Text(
        if (version.isBlank()) "没有发现更新。" else "当前版本 $version，没有发现更新。",
        fontSize = 13.sp,
    )
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        UpdaterButton("访问令牌", onClick = onOpenPat)
        Spacer(Modifier.weight(1f))
        UpdaterButton("关闭", onClick = onDismiss)
    }
}

@Composable
private fun Progress(pct: Int) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier.fillMaxWidth(pct / 100f).height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MiuixTheme.colorScheme.primary),
        )
    }
}

private fun progressText(pct: Int, total: Long): String {
    if (total <= 0) return "正在下载…  " + pct + "%"
    fun fmt(b: Long): String {
        val mb = b / 1024.0 / 1024.0
        return if (mb >= 1) String.format("%.1f MB", mb) else String.format("%.0f KB", b / 1024.0)
    }
    return "正在下载…  " + pct + "%   (" + fmt(total * pct / 100) + " / " + fmt(total) + ")"
}

/** 用 Miuix 弹窗跑完整流程：检查 → 下载（进度）→ 安装。 */
fun showUpdateDialog(activity: Activity, onResult: (CheckResult) -> Unit = {}) {
    val dialog = ComponentDialog(activity, R.style.UpdaterDialogTheme)
    val state = UpdateUiState()

    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun check() {
        state.release = null
        state.failure = null
        state.upToDate = null
        state.downloading = false
        state.progress = 0
        state.downloadError = null
        state.hasToken = Updater.hasToken(activity)
        scope.launch {
            val result = Updater.checkDetailed()
            state.hasToken = Updater.hasToken(activity)
            when (result) {
                is CheckResult.Update -> state.release = result.release
                is CheckResult.UpToDate -> state.upToDate = result.current
                is CheckResult.Failure -> state.failure = result
            }
            onResult(result)
        }
    }

    fun openPat() {
        showPatDialog(
            activity = activity,
            hint = state.failure?.let { it.message(state.hasToken) },
            onSaved = { check() },
            onCleared = { check() },
        )
    }

    val view = ComposeView(activity).apply {
        setContent {
            val s = remember { state }
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                UpdateDialogContent(
                    state = s,
                    onDismiss = { dialog.dismiss() },
                    onRetry = { check() },
                    onOpenPat = { openPat() },
                    onConfirm = confirm@{
                        val release = s.release ?: return@confirm
                        if (s.downloading) return@confirm
                        s.downloading = true
                        s.downloadError = null
                        scope.launch {
                            val apk = Updater.download(activity, release) { pct ->
                                activity.runOnUiThread { s.progress = pct }
                            }
                            s.downloading = false
                            if (apk == null) {
                                s.downloadError = "下载失败，请检查网络或令牌权限后重试"
                            } else {
                                dialog.dismiss()
                                Updater.install(activity, apk)
                            }
                        }
                    },
                )
            }
        }
    }
    dialog.setContentView(view)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    dialog.window?.setLayout(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
    )
    dialog.setOnDismissListener { scope.cancel() }
    dialog.setCanceledOnTouchOutside(true)
    dialog.show()
    // 先展示再检查，避免点按钮后长时间没反应
    check()
}

/** 便捷重载：初始化仓库信息（自动复用已保存的 PAT）后直接弹窗。 */
fun showUpdateDialog(
    activity: Activity,
    config: Updater.Config,
    onResult: (CheckResult) -> Unit = {},
) {
    Updater.init(activity, config)
    showUpdateDialog(activity, onResult)
}
