package com.smsgateway.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 空状态视图 - 增强版。
 *
 * 相比原版纯文字的空状态，增加了：
 * - 大图标（120dp 圆形背景）
 * - 标题和描述文字
 * - 可选操作按钮
 *
 * 用于列表为空、加载失败等场景，提供更友好的用户引导。
 *
 * @param icon 图标
 * @param title 标题
 * @param description 描述（可选）
 * @param actionLabel 操作按钮文字（可选）
 * @param onAction 操作按钮点击回调（可选）
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 大图标（淡色圆形背景）。两个尺寸都取 AppSize，不散写 120/64。
            Box(
                modifier = Modifier
                    .size(AppSize.emptyBadge)
                    .clip(CircleShape)
                    .background(AppColor.Card),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppColor.Faint,
                    modifier = Modifier.size(AppSize.emptyIcon)
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            // 标题
            Text(
                text = title,
                style = AppTypography.h2,
                color = AppColor.Ink,
                textAlign = TextAlign.Center
            )

            // 描述
            if (description != null) {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = description,
                    style = AppTypography.bodyMedium,
                    color = AppColor.InkSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = AppTypography.bodyMedium.lineHeight
                )
            }

            // 操作按钮。只在真的有事可做时才摆（见调用方）——
            // 「返回主页」这种与左上角返回键重复的动作不该出现在空状态里，
            // 它会成为整页最显眼的元素，而空状态要表达的是「这里没事」。
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(AppSpacing.lg))
                AppButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
