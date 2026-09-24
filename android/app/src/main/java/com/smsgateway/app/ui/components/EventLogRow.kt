package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.smsgateway.app.database.EventLogEntity
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatClockTime
import com.smsgateway.app.util.EventLog

/**
 * 重要日志的单条展示。
 *
 * 三行结构，按「排查时先看什么」排序：
 * 1. 事件标签（带级别配色）+ 时刻 —— 扫一眼就知道这条是不是我要找的那类；
 * 2. 发送方 —— 与手机短信里的号码对上；
 * 3. 原因 —— 具体的判定结果。
 *
 * **这里没有短信正文，也不该有。** 事件表刻意不存正文与验证码（见 [EventLogEntity]），
 * 要正文请去队列页或服务端记录页按发送方和时刻找。
 */
@Composable
fun EventLogRow(row: EventLogEntity) {
    AppCard(
        contentPadding = PaddingValues(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(
                text = EventLog.labelOf(row.type),
                type = badgeTypeOf(row.level)
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Text(
                text = formatClockTime(row.createdAt),
                style = AppTypography.caption,
                color = AppColor.InkMuted
            )
        }

        // 发送方与**收信号码**并排。后者是多卡设备上排查的关键一维 ——
        // 调用方等的验证码是按号码缓存与匹配的，不知道是哪个号收到的，
        // 就分不清「这张卡没收到」和「收到了但标错了号码」（见 EventLogEntity.phone）。
        //
        // 两个都在时标一下「收信」：光看两串数字分不出哪个是哪个。
        // 都可能为空：设备级事件（注册、心跳、服务启停）两个都没有，
        // 而不该留一行空白占位 —— 那会让日志看起来缺了东西。
        val sender = row.sender?.takeIf { it.isNotBlank() }
        val phone = row.phone?.takeIf { it.isNotBlank() }
        val parties = when {
            sender != null && phone != null -> "$sender · 收信 $phone"
            sender != null -> sender
            phone != null -> "收信 $phone"
            else -> null
        }
        parties?.let {
            Text(
                text = it,
                style = AppTypography.bodyMedium,
                color = AppColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        row.reason?.let { reason ->
            Text(
                text = reason,
                style = AppTypography.caption,
                // 原因染成次要色而不是正文色：它是补充说明，主信息是上面那个标签
                color = AppColor.InkSecondary
            )
        }
    }
}

/** 级别 → 徽章配色。error 红、warn 橙、info 中性灰，与队列页的失败标签同一套视觉。 */
private fun badgeTypeOf(level: String): StatusBadgeType = when (level) {
    EventLog.LEVEL_ERROR -> StatusBadgeType.Danger
    EventLog.LEVEL_WARN -> StatusBadgeType.Warning
    else -> StatusBadgeType.Default
}
