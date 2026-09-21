package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatRelative
import com.smsgateway.app.ui.utils.rememberNow
import com.smsgateway.app.util.ServerTime

/**
 * 主页的「最近收到」：服务端最新几条短信，外加一个「复制今日验证码」。
 *
 * ## 为什么是这一块
 *
 * 主页原先回答的是「网关在跑吗」和「今天收了多少」两个问题，都是**间接**的：
 * 今天 64 条说明不了此刻还在不在收 —— 可能两小时前就断了，而数字还挂在那儿。
 * 「最近一条是 3 分钟前」才是直接证据。
 *
 * 而且这条证据只有这一处能给：状态卡看的是心跳（手机到服务器通不通），
 * 心跳正常但卡停了、短信进不来，状态卡照样是绿的。**心跳说的是「我能连上服务器」，
 * 这里说的是「我真的收到东西了」**，两件事。
 *
 * ## 验证码默认遮住
 *
 * 主页是常驻屏幕，这台手机就摆在某个地方的桌面上。默认明文摆着三条能用的验证码，
 * 等于谁路过都能读走一个登录凭证 —— 而验证码是能拿去登录的，不是只读数据。
 * 所以默认 `●●●●`，要看得点右上角那只眼睛。
 *
 * 遮的只是**显示**：不复述成「有码 / 无码」，因为在没解锁的状态下那正是要藏的信息。
 * 需要明文去记录页，那是用户主动打开的一页。
 *
 * ## 只显示验证码，不显示正文
 *
 * 正文长，会把卡片撑成一面文字墙，而「是哪条短信」靠发送方 + 时间已经能认出来。
 * 要看全文点进记录页。
 *
 * ## 失败与空态
 *
 * 拉取失败（未注册 / 服务器不可达）时**保留上一次的结果、不报错**：主页冒一句红字
 * 比摘要过期几秒糟糕得多，真要排查有记录页和服务端记录页两处。
 * 一次都没取到过时退化成一行弱化文案，卡片不塌。
 */
@Composable
fun RecentSmsCard(
    state: DashboardState,
    onOpenServerSms: () -> Unit,
    onCopyTodayCodes: () -> Unit
) {
    // 会自己走的「现在」：相对时间不跟着走的话，断网之后会一直停在「3 分钟前」
    val now = rememberNow()

    // 默认遮住，且**不持久化**：每次打开 App 都要重新点一次眼睛。
    // 存起来的话，某次在工位上点开之后就一直明文摆着了 —— 那正是这块要防的事。
    var codesVisible by remember { mutableStateOf(false) }
    val records = state.recentSms

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = AppSpacing.lg, end = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近收到",
                    style = AppTypography.hint,
                    color = AppColor.InkMuted,
                    modifier = Modifier.weight(1f)
                )

                // 有验证码可看时才给这只眼睛：一条码都没有时它点了没反应，
                // 摆着只会让人以为坏了
                if (records.any { !it.code.isNullOrBlank() }) {
                    IconButton(onClick = { codesVisible = !codesVisible }) {
                        Icon(
                            imageVector = if (codesVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (codesVisible) "隐藏验证码" else "显示验证码",
                            tint = AppColor.InkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .clickable(onClick = onOpenServerSms)
                        .padding(vertical = AppSpacing.sm, horizontal = AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "全部记录", style = AppTypography.hint, color = AppColor.InkMuted)
                    Spacer(modifier = Modifier.width(AppSpacing.xxs))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = AppColor.Faint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (records.isEmpty()) {
                Text(
                    text = "还没有收到过短信",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted,
                    modifier = Modifier.padding(
                        start = AppSpacing.lg,
                        end = AppSpacing.lg,
                        bottom = AppSpacing.md
                    )
                )
            } else {
                records.forEachIndexed { index, record ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg),
                            thickness = 1.dp,
                            color = AppColor.Divider
                        )
                    }
                    RecentSmsLine(
                        record = record,
                        now = now,
                        codeVisible = codesVisible,
                        onClick = onOpenServerSms
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
                thickness = 1.dp,
                color = AppColor.Divider
            )

            // 联调、客服常用：把今天所有码一次拿走，不用一条条抄
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCopyTodayCodes)
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = AppColor.InkMuted,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "复制今日验证码",
                    style = AppTypography.bodySmall,
                    color = AppColor.InkSecondary
                )
            }
        }
    }
}

@Composable
private fun RecentSmsLine(
    record: SmsRecord,
    now: Long,
    codeVisible: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = record.sender.orEmpty().ifBlank { "未知发送方" },
            style = AppTypography.bodyMedium,
            color = AppColor.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))

        // 有验证码才摆这一格。遮住时用等长的圆点而不是固定几个：
        // 位数本身不是秘密（也就 4 位 6 位两种），但等长之后这一行不会在切换显隐时跳动
        record.code?.takeIf { it.isNotBlank() }?.let { code ->
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = AppColor.InkMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.xxs))
            Text(
                text = if (codeVisible) code else "●".repeat(code.length),
                style = AppTypography.mono(AppTypography.bodyMedium),
                color = if (codeVisible) AppColor.Ink else AppColor.InkMuted
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
        }

        Text(
            text = formatRelative(ServerTime.toEpochMillis(record.receiveTime), now),
            style = AppTypography.caption,
            color = AppColor.InkMuted
        )
    }
}
