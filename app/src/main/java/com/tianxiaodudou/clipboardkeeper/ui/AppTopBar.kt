package com.tianxiaodudou.clipboardkeeper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 紧凑顶栏：标题吃掉剩余宽度并单行省略，按钮只占自身宽度，
 * 从布局上保证标题和操作按钮不会互相压盖。
 */
@Composable
fun AppTopBar(
    title: String,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    val textColor = if (isSystemInDarkTheme()) Color(0xFFECEDF0) else Color(0xFF1A1C1E)
    val dividerColor = if (isSystemInDarkTheme()) Color(0x33FFFFFF) else Color(0x1A000000)
    Column(modifier = Modifier.fillMaxWidth().background(AppColors.topBarBackground())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AppDims.BarHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        navigationIcon?.invoke()
        BasicText(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            style = TextStyle(
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
            actions?.invoke()
        }
        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(dividerColor))
    }
}
