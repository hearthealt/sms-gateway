package com.smsgateway.app.ui.screens.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatRelative
import com.smsgateway.app.ui.utils.rememberHapticFeedback
import com.smsgateway.app.ui.utils.rememberNow

/** 后端以 90 秒为判离线阈值，这里跟着它走。 */
private const val HEARTBEAT_STALE_MS = 90_000L

/**
 * 主页的主角卡片：网关状态 + 启停开关 + 今日概览。
 *
 * ## 为什么把状态和指标合成一张
 *
 * 之前是「状态卡 + 三张指标卡」四块平铺竖排，四块同宽、同圆角、同 0 投影、只差底色 ——
 * 于是页面**没有视觉重心**：「网关在不在跑」和「今天收了几条」看起来一样重要，
 * 而前者才是这个应用存在的理由。同时三张指标卡只表达三个数字，却占了全页最贵的地方
 * （约 200dp），把内容顶到上半屏、下半屏全空。
 *
 * 合成一张之后：状态区上色、字号抬到 h2，成为唯一的主角；指标压成一条，
 * 整块高度比原来省约 130dp。**这个顺序也对应「先看状态，再看数字」的阅读顺序。**
 *
 * ## 不靠阴影，靠底色和字号分层
 *
 * 沿用全应用的原则（见 AppStyle 的说明：层级靠底色与留白拉开，不靠阴影），
 * 所以这里没有加 elevation，而是把状态区整块上色、指标行留在白底上 —— 一张卡两种面，
 * 颜色只染状态那一半，指标不会被误读成「状态的一部分」。
 */
@Composable
fun HeroCard(
    state: DashboardState,
    onToggleService: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onCheckStatus: () -> Unit
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

    AppCard(
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                // 读屏把整块状态区当成**一个**节点。
                //
                // 原先 TalkBack 会先读出「网关运行中」「已连接 · 12 秒前」，再单独读
                // 「开关，已开启」—— 中间那段间隔让人以为这是两个互不相干的东西，
                // 而右侧那个开关要的就是「它说的是左边这件事」。
                //
                // 刻意**不**做「整行 toggleable」：那会让点状态区的任何位置都启停网关，
                // 而这块区域很大、上面还叠着标题与副标题 —— 误触的代价是网关被停掉、
                // 短信从此不再上报。开关本身仍是唯一的触摸目标，这一层只负责合并语义，
                // 读屏用户可以对着整行双击切换（合并之后切换动作落在合并节点上）。
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = status.icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(AppSize.iconXl)
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
            ) {
                Text(
                    text = status.title,
                    style = AppTypography.h2,
                    color = accent
                )
                Text(
                    text = status.subtitle,
                    style = AppTypography.caption,
                    // 实色，不再叠 alpha：压在状态底色上的正文本来就只剩 4~5:1，
                    // 再乘一个 0.75 就等于把「已连接 · 12 秒前」这类关键信息做到读不清。
                    color = accent
                )

                // 被禁用时，「检查状态」就放在状态自己下面 —— 原先它是主页顶部
                // 一条独立横幅上的按钮，而横幅与这块状态卡说的是同一件事
                // （横幅说「已被管理员禁用」，状态卡的标题也说「已被管理员禁用」），
                // 同一句话在屏幕上出现三遍。现在合成一处：状态在这一块说，动作也跟着来。
                if (state.isDisabled) {
                    state.testResult?.let { result ->
                        Text(
                            text = result,
                            style = AppTypography.caption,
                            color = accent
                        )
                    }
                    AppTextButton(
                        onClick = onCheckStatus,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "检查状态",
                            style = AppTypography.bodySmall,
                            color = accent
                        )
                    }
                }
            }

            // 右侧：未注册时放扫码入口，其余时候放启停开关。
            //
            // 未注册时那唯一该做的事（扫码连接）原先只是一行 11sp、半透明的提示字，
            // 旁边还挂着一个灰掉、按不动的开关 —— 页面上最重要的一件事长得最像装饰。
            // 换成实心按钮之后，「现在该干什么」不需要读字也看得出来。
            when {
                !state.isRegistered && !state.isRunning -> AppButton(onClick = onOpenQuickConnect) {
                    Text("扫码连接")
                }

                else -> Switch(
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

        HorizontalDivider(color = AppColor.Divider)

        MetricsRow(
            state = state,
            onOpenQueue = onOpenQueue,
            onOpenServerSms = onOpenServerSms
        )
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
        subtitle = "扫码连接后即可开始上报",
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
