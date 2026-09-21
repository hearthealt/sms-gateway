package com.smsgateway.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 徽章类型枚举。
 */
enum class StatusBadgeType {
    Success,    // 成功状态（绿色）
    Danger,     // 危险/错误状态（红色）
    Warning,    // 警告状态（橙色）
    Info,       // 信息状态（蓝色）
    Neutral,    // 中性警告（紫色）
    Default     // 默认状态（灰色）
}

/**
 * 状态徽章组件。
 *
 * 用于显示状态标签，如「待发送」、「已发送」、「失败」等。
 * 提供统一的视觉样式和颜色方案。
 *
 * @param text 徽章文本
 * @param type 徽章类型（决定颜色）
 */
@Composable
fun StatusBadge(
    text: String,
    type: StatusBadgeType = StatusBadgeType.Default
) {
    val (textColor, backgroundColor) = when (type) {
        StatusBadgeType.Success -> AppColor.Success to AppColor.SuccessBg
        StatusBadgeType.Danger -> AppColor.Danger to AppColor.DangerBg
        StatusBadgeType.Warning -> AppColor.Warning to AppColor.WarningBg
        StatusBadgeType.Info -> AppColor.Info to AppColor.InfoBg
        StatusBadgeType.Neutral -> AppColor.NeutralWarn to AppColor.NeutralWarnBg
        StatusBadgeType.Default -> AppColor.Neutral to AppColor.NeutralBg
    }

    Box(
        modifier = Modifier
            .background(
                color = backgroundColor,
                shape = AppColor.BadgeShape
            )
            .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xxs)
    ) {
        Text(
            text = text,
            style = AppTypography.label,
            color = textColor
        )
    }
}
