package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 设置页的标签-值行。
 *
 * 左侧是灰色标签，右侧是值（可选等宽字体、可选可复制）。
 * 常用于只读信息展示（设备 ID、设备名称、版本号）。
 *
 * 这里**没有** onClick / 箭头参数。曾经有一对，配套「有下一级页面才显示箭头」的
 * 分支，但全工程没有任何一个调用方传过 —— 于是那个箭头分支和 clickable 分支都是
 * 不可达代码。设置页的行目前全是只读展示：需要跳转的地方是整块的卡片或按钮。
 * 将来真要给某一行加跳转，再把 Card(onClick) 那套抄过来，别退回 Modifier.clickable
 * （水波纹不跟圆角、也没有按钮语义）。
 */
@Composable
fun StatusRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    /** 等宽显示。给设备 ID 这类需要逐位核对的标识用。 */
    monospace: Boolean = false,
    /**
     * 允许长按选中复制。
     *
     * 给「要拿去别处使用」的值用（设备 ID 得报给管理员）。
     */
    selectable: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = AppTypography.bodyMedium, color = AppColor.InkSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val valueText: @Composable () -> Unit = {
                Text(
                    text = value,
                    style = AppTypography.bodyMedium.copy(
                        fontFamily = if (monospace) FontFamily.Monospace else null
                    ),
                    color = valueColor ?: Color.Unspecified
                )
            }
            if (selectable) SelectionContainer { valueText() } else valueText()
        }
    }
}
