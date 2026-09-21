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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
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
            // 大图标（淡色圆形背景）
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(AppColor.Card),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppColor.Faint,
                    modifier = Modifier.size(64.dp)
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

            // 操作按钮
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(AppSpacing.lg))
                Button(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}
