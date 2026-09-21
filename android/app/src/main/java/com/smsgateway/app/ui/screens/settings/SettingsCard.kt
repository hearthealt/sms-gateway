package com.smsgateway.app.ui.screens.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 设置页的卡片容器。
 *
 * 带标题的白色卡片，用于组织设置页的各个区块。
 */
@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard {
        Text(text = title, style = AppTypography.h3, color = AppColor.Ink)
        content()
    }
}
