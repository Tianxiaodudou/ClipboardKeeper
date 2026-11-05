package com.tianxiaodudou.clipboardkeeper.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card

/**
 * 统一配色与卡片外观：
 * - 页面底色比卡片深一档，卡片加 1dp 描边（不再用阴影，避免组件之间视觉重叠）
 * - 深色模式自动反色
 */
object AppColors {

    private val shape = RoundedCornerShape(12.dp)

    @Composable
    fun pageBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF141518) else Color(0xFFE7EAEE)

    @Composable
    fun topBarBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF1E2024) else Color(0xFFF7F8FA)

    @Composable
    fun cardModifier(base: Modifier = Modifier): Modifier {
        val dark = isSystemInDarkTheme()
        return base.border(
            width = 1.dp,
            color = if (dark) Color(0x3DFFFFFF) else Color(0x24000000),
            shape = shape
        )
    }
}

/** 所有卡片都走这里，保证描边与圆角一致、不会相互压盖 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = AppColors.cardModifier(modifier)) { content() }
}

/** 紧凑间距常量：把整页留白压到最小 */
object AppDims {
    val PagePadding = 8.dp
    val Gap = 6.dp
    val CardPadding = 8.dp
    val BarHeight = 44.dp
}
