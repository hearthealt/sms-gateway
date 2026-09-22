package com.smsgateway.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatClockTime
import com.smsgateway.app.ui.utils.rememberHapticFeedback

/**
 * 队列页的单条记录展示 - 优化版。
 *
 * 改进：
 * - 添加状态 Chip（失败状态用红色标签突出）
 * - 验证码用图标+等宽字体突出显示
 * - 优化层级和视觉分隔
 *
 * @param row 队列实体
 * @param now 倒计时要用的「现在」。由列表统一传进来而不是在这里各取各的：
 *   一是每行各起一个走针太浪费，二是组合期现取的时间**不会自己走** ——
 *   队列不动时没有任何状态发射，「45 秒后」会一直停在 45 秒上，也不消失。
 * @param onRetry 重试回调
 * @param onDelete 删除回调
 */
@Composable
fun QueueRow(
    row: SmsQueueEntity,
    now: Long,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onCopyCode: (String) -> Unit = {}
) {
    val failed = row.status == "failed"
    // 这两个按钮都会立刻改变眼前的列表（重试会挪位置、删除会让它消失），
    // 手感上给一下回执，免得以为是点漏了
    val haptic = rememberHapticFeedback()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (failed) AppColor.DangerBg else AppColor.Card
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (failed) 1.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            // 顶部：失败状态标签
            if (failed) {
                StatusBadge(
                    text = "已被服务端拒绝",
                    type = StatusBadgeType.Danger
                )
                Spacer(modifier = Modifier.height(AppSpacing.xs))
            }

            // 发送方 + 收信时刻
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.sender,
                    style = AppTypography.bodyLarge,
                    color = AppColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                // 收信时刻放这里而不是底部：扫一眼就知道「这条是什么时候的」，
                // 底部留给「接下来会怎样」（重试次数与倒计时）
                Text(
                    text = formatClockTime(row.receiveTime),
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
            }

            // 短信内容
            Text(
                text = row.content,
                style = AppTypography.bodyMedium,
                color = AppColor.Ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // 验证码（如果有）。整块可点 → 复制这一条 ——
            // 与服务端记录页同一套交互，两页来回看不会一个能点一个不能点
            row.code.takeIf { it.isNotBlank() }?.let { code ->
                Spacer(modifier = Modifier.height(AppSpacing.xxs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(AppColor.BadgeShape)
                        .clickable { onCopyCode(code) }
                        .padding(horizontal = AppSpacing.xxs, vertical = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "验证码，点一下复制",
                        tint = AppColor.InkSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xxs))
                    Text(
                        text = code,
                        style = AppTypography.mono(AppTypography.h3),
                        color = AppColor.Ink
                    )
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xxs))
            HorizontalDivider(color = AppColor.Divider)
            Spacer(modifier = Modifier.height(AppSpacing.xxs))

            // 底部：重试信息和操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：重试信息
                Column(modifier = Modifier.weight(1f)) {
                    // 没重试过就不提「重试」：一条完好的待发短信头上顶着「第 0 次重试」，
                    // 读起来像出了什么事
                    if (row.retryCount > 0) {
                        Text(
                            text = "第 ${row.retryCount} 次重试",
                            style = AppTypography.caption,
                            color = AppColor.InkSecondary
                        )
                    }
                    if (!failed) {
                        val remaining = row.nextRetryAt - now
                        if (remaining > 0) {
                            Text(
                                text = formatCountdown(remaining),
                                style = AppTypography.hint,
                                color = AppColor.InkMuted
                            )
                        }
                    }
                }

                // 右侧：操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                    TextButton(
                        onClick = {
                            haptic.light()
                            onRetry()
                        },
                        contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                    ) {
                        Text(
                            text = if (failed) "重新排队" else "立即重试",
                            style = AppTypography.bodySmall
                        )
                    }
                    TextButton(
                        onClick = {
                            haptic.heavy()
                            onDelete()
                        },
                        contentPadding = PaddingValues(horizontal = AppSpacing.sm, vertical = AppSpacing.xxs)
                    ) {
                        Text(
                            text = "删除",
                            style = AppTypography.bodySmall,
                            color = AppColor.Danger
                        )
                    }
                }
            }
        }
    }
}

/** 队列页显示「还有多久重试」，与「多久之前」方向相反，所以单独一个函数。 */
private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒后"
        seconds < 3600 -> "${seconds / 60} 分钟后"
        else -> "${seconds / 3600} 小时后"
    }
}
