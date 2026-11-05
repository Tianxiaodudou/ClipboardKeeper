package com.tianxiaodudou.updater.compose

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 弹窗里的小号按钮。Miuix 默认 TextButton 的最小宽度较大，三个并排会把
 * 「立即更新」挤成两行，这里统一压到紧凑尺寸。
 */
@Composable
internal fun UpdaterButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        minHeight = 32.dp,
        minWidth = 0.dp,
        cornerRadius = 10.dp,
    )
}
