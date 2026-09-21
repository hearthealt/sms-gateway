package com.smsgateway.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.rememberHapticFeedback
import com.smsgateway.app.ui.utils.rememberNow

/** 后端以 90 秒为判离线阈值，这里跟着它走。 */
private const val HEARTBEAT_STALE_MS = 90_000L

/**
 * 状态控制卡 - 合并状态展示和启停控制。
 *
 * 将原来的 HeroStatusCard 和 GatewayActionButton 合并为一个扁平化组件：
 * - 左侧：图标 + 状态文字
 * - 右侧：启停开关
 *
 * 视觉效果从原来的 ~200dp（状态卡 + 大圆钮）压缩到 ~72dp。
 */
@Composable
fun StatusControlCard(
    state: DashboardState,
    onToggleService: () -> Unit
) {
    // 必须是会自己走的「现在」，不能是组合期取一次的快照：心跳一停就没有任何状态
    // 发射了，下面那个 90 秒的超时判断会永远停在最后一次求值的结果上，
    // 界面于是一直显示「网关运行中」—— 恰好是这一块最该说清的事
    val now = rememberNow()
    val status = calculateStatus(state, now)
    val haptic = rememberHapticFeedback()

    // 状态会变（比如刚启动 → 已连接），颜色跟着渐变过去
    val accent by animateColorAsState(
        targetValue = status.accent,
        animationSpec = AppAnimations.colorTransition(),
        label = "statusAccent"
    )
    val background by animateColorAsState(
        targetValue = status.background,
        animationSpec = AppAnimations.colorTransition(),
        label = "statusBackground"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：图标 + 状态文字
            Icon(
                imageVector = status.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
            ) {
                Text(
                    text = status.title,
                    style = AppTypography.h3,
                    color = accent
                )
                Text(
                    text = status.subtitle,
                    style = AppTypography.caption,
                    color = accent.copy(alpha = 0.7f)
                )
                // 如果未注册，显示提示
                if (!state.isRegistered && !state.isRunning) {
                    Text(
                        text = "点顶部「扫一扫」扫码连接",
                        style = AppTypography.hint,
                        color = accent.copy(alpha = 0.6f)
                    )
                }
            }

            // 右侧：启停开关
            Switch(
                checked = state.isRunning,
                onCheckedChange = {
                    haptic.medium()
                    onToggleService()
                },
                // 未注册不许启动。**被禁用时仍然允许启动** —— 心跳是设备唯一能
                // 发现自己被恢复的通道，禁掉它就会造出「不可启动 → 不轮询 →
                // 永远学不到已恢复」的死锁。
                enabled = state.isRegistered || state.isRunning,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accent,
                    checkedTrackColor = accent.copy(alpha = 0.5f),
                    uncheckedThumbColor = AppColor.SwitchOffThumb,
                    uncheckedTrackColor = AppColor.SwitchOffTrack
                )
            )
        }
    }
}

/**
 * 状态判定逻辑。
 *
 * 分支顺序就是「谁更该被先说」：被禁用 > 未注册 > 没在跑 > 心跳有问题 > 正常。
 */
@Composable
private fun calculateStatus(state: DashboardState, now: Long): StatusInfo = when {
    state.isDisabled -> StatusInfo(
        title = "已被管理员禁用",
        subtitle = "心跳仍在跑，恢复后会自动接上",
        accent = AppColor.NeutralWarn,
        background = AppColor.NeutralWarnBg,
        icon = Icons.Default.Block
    )

    !state.isRegistered -> StatusInfo(
        title = "未注册",
        subtitle = "先注册设备才能启动",
        accent = AppColor.Info,
        background = AppColor.InfoBg,
        icon = Icons.Default.AppRegistration
    )

    !state.isRunning -> StatusInfo(
        title = "网关已停止",
        subtitle = "短信会留在本地，不会上报",
        accent = AppColor.Danger,
        background = AppColor.DangerBg,
        icon = Icons.Default.Cancel
    )

    // 「一次都没成功过」要分成两支：刚起来的几十秒内说「正在连」是实话，
    // 一直没成功就得按故障报。区分只能靠「这次启动到现在过了多久」——
    // 少了它，一台从启动就没信号的设备会永远显示「服务刚起来，正在连服务器」。
    state.lastHeartbeatAt == null &&
        (state.gatewayStartedAt == null || now - state.gatewayStartedAt < HEARTBEAT_STALE_MS) ->
        StatusInfo(
            title = "已启动，等待心跳",
            subtitle = "服务刚起来，正在连服务器",
            accent = AppColor.Warning,
            background = AppColor.WarningBg,
            icon = Icons.Default.Sync
        )

    state.lastHeartbeatAt == null -> StatusInfo(
        title = "连接中断",
        subtitle = "已启动 ${formatElapsed(state.gatewayStartedAt, now)}，一次心跳都没成功",
        accent = AppColor.Warning,
        background = AppColor.WarningBg,
        icon = Icons.Default.Warning
    )

    now - state.lastHeartbeatAt > HEARTBEAT_STALE_MS -> StatusInfo(
        title = "连接中断",
        subtitle = "最后心跳 ${formatRelative(state.lastHeartbeatAt, now)}",
        accent = AppColor.Warning,
        background = AppColor.WarningBg,
        icon = Icons.Default.Warning
    )

    else -> StatusInfo(
        title = "网关运行中",
        subtitle = "已连接 · ${formatRelative(state.lastHeartbeatAt, now)}",
        accent = AppColor.Success,
        background = AppColor.SuccessBg,
        icon = Icons.Default.CheckCircle
    )
}

private data class StatusInfo(
    val title: String,
    val subtitle: String,
    val accent: Color,
    val background: Color,
    val icon: ImageVector
)

/**
 * 相对时间格式化。
 */
private fun formatRelative(at: Long?, now: Long): String {
    if (at == null || at <= 0L) return "无"
    val deltaSeconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 5 -> "刚刚"
        deltaSeconds < 60 -> "$deltaSeconds 秒前"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} 分钟前"
        else -> "${deltaSeconds / 3600} 小时前"
    }
}

/**
 * 「持续了多久」。与 [formatRelative] 方向相反（那是「多久之前」），所以单独一个函数。
 *
 * 不带上「前」字：这句读起来是「已启动 12 分钟，一次心跳都没成功」，
 * 要的是一个时长，不是一个时间点。
 */
private fun formatElapsed(from: Long?, now: Long): String {
    if (from == null || from <= 0L) return "一段时间"
    val seconds = ((now - from) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒"
        seconds < 3600 -> "${seconds / 60} 分钟"
        else -> "${seconds / 3600} 小时"
    }
}
