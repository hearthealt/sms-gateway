package com.smsgateway.app.ui.screens.selftest

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 自检页的「转发链路」：主动让服务端往每个启用的转发渠道各发一条测试消息。
 *
 * ## 为什么必须是**主动**的
 *
 * 自检里那六项都是只读的（查权限、探测、心跳），跑一百遍也不会有副作用，所以进页面
 * 就自动跑。这一项不一样：它真的会往微信/钉钉发消息。自动跑等于每次进自检页都往外发
 * 一条 —— 那既烦人又很快会被管理员当成噪音关掉。所以做成一个按钮，而且服务端按设备
 * 限流 5 分钟一次。
 *
 * ## 为什么结果要逐渠道列出来
 *
 * 转发断掉是**静默**的：手机照收、心跳照发、管理端一切正常，只是码送不到微信里。
 * 而「坏了哪一个」才是能照着处置的信息 —— 一句「测试失败」等于没说。
 * 结果留在页面上（不进 snackbar）：这是要拿着去排查的东西。
 */
@Composable
fun NotifyTestCard(state: DashboardState, onTest: () -> Unit) {
    val results = state.notifyTestResults

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(text = "转发链路", style = AppTypography.h3, color = AppColor.Ink)
            Text(
                text = "转发断掉是静默的：手机照收、心跳照发，只是码送不到微信里。" +
                    "点一下会给每个启用的转发渠道各发一条测试消息（5 分钟内只能测一次）。",
                style = AppTypography.caption,
                color = AppColor.InkMuted
            )

            Button(
                onClick = onTest,
                enabled = !state.notifyTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.notifyTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppColor.InkMuted
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                }
                Text(if (state.notifyTesting) "正在发送…" else "发一条测试消息")
            }

            state.notifyTestError?.let { error ->
                Text(text = error, style = AppTypography.bodySmall, color = AppColor.Danger)
            }

            if (results != null) {
                HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

                if (results.isEmpty()) {
                    Text(
                        text = "服务器上没有启用的转发渠道 —— 码只会存在服务端，不会有任何转发。",
                        style = AppTypography.bodySmall,
                        color = AppColor.Warning
                    )
                } else {
                    results.forEach { result ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (result.ok) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.Cancel
                                },
                                contentDescription = null,
                                tint = if (result.ok) AppColor.Success else AppColor.Danger,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = result.channelName,
                                    style = AppTypography.bodyMedium,
                                    color = AppColor.Ink
                                )
                                // 失败原因要留着：那是唯一能拿去修的东西
                                result.message?.takeIf { it.isNotBlank() }?.let { message ->
                                    Text(
                                        text = message,
                                        style = AppTypography.caption,
                                        color = AppColor.Danger
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(AppSpacing.xs))
                            Text(
                                text = if (result.ok) "已送达" else "失败",
                                style = AppTypography.caption,
                                color = if (result.ok) AppColor.Success else AppColor.Danger
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(0.dp))
        }
    }
}
