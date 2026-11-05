package com.tianxiaodudou.clipboardkeeper.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card

/**
 * 统一的页面/卡片配色：页面底色比卡片深一档，卡片再叠描边 + 轻阴影，
 * 让控件与背景有明显对比（Miuix 默认卡片与页面背景几乎同色）。
 */
object AppColors {

    private val shape = RoundedCornerShape(14.dp)

    @Composable
    fun pageBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF15161A) else Color(0xFFE9ECF0)

    @Composable
    fun cardModifier(base: Modifier = Modifier): Modifier {
        val dark = isSystemInDarkTheme()
        return base
            .shadow(elevation = if (dark) 0.dp else 2.dp, shape = shape, clip = false)
            .border(
                width = 1.dp,
                color = if (dark) Color(0x33FFFFFF) else Color(0x1F000000),
                shape = shape
            )
    }
}

/** 所有卡片都走这里，保证描边与阴影一致 */
@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = AppColors.cardModifier(modifier)) { content() }
}
