package com.tianxiaodudou.updater.compose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tianxiaodudou.clipboardkeeper.R
import com.tianxiaodudou.updater.Updater
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TOKEN_PAGE = "https://github.com/settings/tokens"

/** 一步一步的 PAT 获取说明；放在弹窗里可滚动查看。 */
val PAT_GUIDE: String = """
一、打开令牌页面
点下方「打开 GitHub Token 页面」，登录要访问仓库的 GitHub 账号。

二、新建经典令牌
点右上角 Generate new token → Generate new token (classic)。

三、填写名称
Note 随便写，例如「应用检查更新」，方便以后辨认。

四、设置有效期
Expiration 建议选 90 days；到期后回来换一个新的即可。

五、勾选权限
勾选 repo（私有仓库必须；公开仓库也建议勾，配额更高）。

六、生成并复制
拉到页面最底部点 Generate token，复制以 ghp_（经典令牌）或 github_pat_（细粒度令牌）开头的那串字符。

七、粘贴并保存
回到这里粘贴到输入框，点「保存并重试」。

—— 用 Fine-grained token？——
选 Only select repositories → 勾选本应用仓库 → Repository permissions → Contents 设为 Read-only（Metadata 会自动带上）。

—— 为什么要填？——
匿名访问 GitHub API 每小时只有 60 次，反复检查更新容易被限流；填了令牌是 5000 次/小时。私有仓库则必须填。

令牌只保存在本机 App 的私有目录，不会上传到别处。
""".trimIndent()

/** PAT 二级弹窗的内容；从检查更新弹窗里点「访问令牌 / 填写令牌」打开。 */
@Composable
fun PatDialogContent(
    initialToken: String,
    hint: String?,
    hasSavedToken: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onOpenPage: () -> Unit,
) {
    var input by remember { mutableStateOf(initialToken) }
    var error by remember { mutableStateOf<String?>(null) }
    // 小屏少露一点、大屏多露一点；剩余步骤靠滚动，保证输入框和按钮始终可见
    val guideMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.30f)
        .coerceIn(180.dp, 280.dp)

    MiuixTheme {
        Column(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.surface)
                .padding(20.dp),
        ) {
            Text("GitHub 访问令牌（PAT）", fontSize = 19.sp)
            Spacer(Modifier.height(6.dp))
            val accent = MiuixTheme.colorScheme.primary
            Text(
                buildAnnotatedString {
                    append("私有仓库、403 或限流时使用；公开仓库可以不填。下方步骤可")
                    withStyle(
                        SpanStyle(
                            color = accent,
                            background = accent.copy(alpha = 0.12f),
                            fontWeight = FontWeight.Medium,
                        ),
                    ) {
                        append("上下滑动")
                    }
                    append("。")
                },
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFD32F2F))
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = guideMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(PAT_GUIDE, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.height(10.dp))
            UpdaterButton(
                "打开 GitHub Token 页面",
                onClick = onOpenPage,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                label = "粘贴 GitHub 访问令牌",
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = Color(0xFFD32F2F))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UpdaterButton(
                    "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                UpdaterButton(
                    "保存并重试",
                    onClick = {
                        val token = input.trim()
                        if (token.isEmpty()) {
                            error = "请先粘贴令牌，或点上面的按钮去获取一个"
                        } else {
                            onSave(token)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (hasSavedToken) {
                UpdaterButton(
                    "清除已保存的令牌",
                    onClick = {
                        input = ""
                        error = null
                        onClear()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 打开 PAT 二级弹窗。保存后通过 [onSaved] 回调让检查更新弹窗重新检查。
 *
 * @param initialToken 预填的令牌，默认读取本机已保存的
 * @param hint 父弹窗的失败原因，显示在标题下方
 */
fun showPatDialog(
    activity: Activity,
    initialToken: String = Updater.currentToken(activity),
    hint: String? = null,
    onSaved: () -> Unit = {},
    onCleared: () -> Unit = {},
) {
    val dialog = ComponentDialog(activity, R.style.UpdaterDialogTheme)
    val activeToken = Updater.currentToken(activity)
    val view = ComposeView(activity).apply {
        setContent {
            Box(
                Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                PatDialogContent(
                    initialToken = initialToken.ifBlank { activeToken },
                    hint = hint,
                    hasSavedToken = Updater.hasToken(activity),
                    onDismiss = { dialog.dismiss() },
                    onSave = { token ->
                        Updater.saveToken(activity, token)
                        dialog.dismiss()
                        onSaved()
                    },
                    onClear = {
                        Updater.clearToken(activity)
                        dialog.dismiss()
                        onCleared()
                    },
                    onOpenPage = { openTokenPage(activity) },
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
    dialog.setCanceledOnTouchOutside(true)
    // 用 PAN 而不是 RESIZE：弹窗内容较高，键盘弹出时整体上移，保证输入框和按钮可见
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    dialog.show()
}

/** 用浏览器打开 GitHub 的令牌页面；没有浏览器时把链接用 Toast 显示出来。 */
private fun openTokenPage(activity: Activity) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_PAGE))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { activity.startActivity(intent) }
        .onFailure {
            Toast.makeText(activity, TOKEN_PAGE, Toast.LENGTH_LONG).show()
        }
}
