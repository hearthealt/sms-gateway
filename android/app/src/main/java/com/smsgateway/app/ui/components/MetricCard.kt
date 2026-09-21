package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 垂直布局的指标卡片 - 用于主页的三指标块。
 *
 * 相比原来横向三列的 MetricCell：
 * - 每个指标独立成卡，垂直排列
 * - 数字从 26sp 增大到 32sp
 * - 添加「查看详情」引导
 * - alert 状态用橙色背景突出显示
 */
@Composable
fun MetricCard(
    icon: ImageVector,
    label: String,
    value: Int,
    hint: String,
    alert: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (alert) AppColor.WarningBg else AppColor.Card
    val valueColor = if (alert) AppColor.Warning else AppColor.Ink
    val iconColor = if (alert) AppColor.Warning else AppColor.InkSecondary

    Card(
        // 用 Card 自己的 onClick 重载，不要在外面套 Modifier.clickable：
        // 后者的水波纹是按矩形裁剪的，压在 14dp 圆角的卡上会露出四个角；
        // 而且这样也没有 Button 语义，读屏只会说「双击激活」，听不出这是个按钮
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (alert) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：图标 + 标签
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    Text(
                        text = label,
                        style = AppTypography.bodyMedium,
                        color = AppColor.Ink
                    )
                    Text(
                        text = hint,
                        style = AppTypography.hint,
                        color = AppColor.InkMuted
                    )
                }
            }

            // 中间：大数字
            Text(
                text = value.toString(),
                color = valueColor,
                style = AppTypography.metricLarge,
                textAlign = TextAlign.End
            )

            Spacer(modifier = Modifier.width(AppSpacing.xs))

            // 右侧：箭头
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "查看详情",
                tint = AppColor.InkMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
