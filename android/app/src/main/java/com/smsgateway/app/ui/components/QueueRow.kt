package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatClockTime
import com.smsgateway.app.ui.utils.rememberHapticFeedback

/**
 * 队列页的单条记录展示。
 *
 * 骨架与日志行、服务端记录行一致：16dp 内边距、[StatusBadge] 表状态、
 * [formatClockTime] 那套时间格式。
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
    onDelete: () -> Unit
) {
    val failed = row.status == "failed"
    // 这两个按钮都会立刻改变眼前的列表（重试会挪位置、删除会让它消失），
    // 手感上给一下回执，免得以为是点漏了
    val haptic = rememberHapticFeedback()

    AppCard(
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        // 失败态整块染红。不再额外加阴影：同一列里只有它带投影，看起来像厚了一层
        // （层级本来就该靠底色拉开，见 AppStyle 的说明）。
        containerColor = if (failed) AppColor.DangerBg else AppColor.Card
    ) {
        // 顶部：失败状态标签。
        // 标签下面不再补 Spacer —— Column 已经有 spacedBy(xs)，再加一个就是 12+4=16dp，
        // 而「徽章 → 发送方」之间本不该比行内其他间距更宽。
        if (failed) {
            StatusBadge(
                text = "已被服务端拒绝",
                type = StatusBadgeType.Danger
            )
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

        // 这里刻意**不再显示验证码**（原先有一个可点复制的验证码块）。
        //
        // 客户端不再解析验证码（认码只留服务端一处，见 SmsReceiver 的说明），
        // 所以本地这一列恒为空 —— 留着那块 UI 只会是一个永远不显示的东西。
        //
        // 想复制验证码去**服务端记录页**：那里的码是服务端从正文提取的，
        // 是权威值。而队列页这一列此前存的是设备端自己猜的码，正是会给出
        // 错答案的那一套（「您的验证码已发送，流水号 999999」会猜成 999999）。
        // 何况正文本身在上一行完整显示着，码不是没地方看。

        // 号码读不到时这一行传得上去，但服务端会跳过写按号码的验证码缓存 ——
        // 「传上去了、调用方却等不到」是这条链路上最难查的一种：到这里队列行
        // 会消失、事件表写的是「上传成功」，三处都没有异常信号。
        // 队列是现场唯一还看得见这条短信的地方，先说清楚。
        // 用 InlineNotice 而不是一行橙字：它必须被看到，而一行小橙字在卡片里
        // 很容易被读成正文的一部分。
        if (row.phone.isBlank()) {
            InlineNotice(
                text = "号码未知：服务端不会按号码缓存这条验证码，调用方可能取不到",
                type = NoticeType.Warning
            )
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
                AppTextButton(
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
                AppTextButton(
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

/** 队列页显示「还有多久重试」，与「多久之前」方向相反，所以单独一个函数。 */
private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒后"
        seconds < 3600 -> "${seconds / 60} 分钟后"
        else -> "${seconds / 3600} 小时后"
    }
}
