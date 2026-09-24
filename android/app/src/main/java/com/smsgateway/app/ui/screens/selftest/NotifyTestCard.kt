package com.smsgateway.app.ui.screens.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.components.CheckResultRow
import com.smsgateway.app.ui.components.InlineNotice
import com.smsgateway.app.ui.components.NoticeType
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 自检页的「转发链路」：主动让服务端往每个启用的转发渠道各发一条测试消息。
 *
 * ## 为什么必须是**主动**的
 *
 * 自检里那些项都是只读的（查权限、探测、心跳），跑一百遍也不会有副作用，所以进页面
 * 就自动跑。这一项不一样：它真的会往微信/钉钉发消息。自动跑等于每次进自检页都往外发
 * 一条 —— 那既烦人又很快会被管理员当成噪音关掉。所以做成一个按钮，而且服务端按设备
 * 限流 5 分钟一次。
 *
 * ## 为什么结果要逐渠道列出来
 *
 * 转发断掉是**静默**的：手机照收、心跳照发、管理端一切正常，只是码送不到微信里。
 * 而「坏了哪一个」才是能照着处置的信息 —— 一句「测试失败」等于没说。
 * 结果留在页面上（不进 snackbar）：这是要拿着去排查的东西。
 *
 * ## 排版与 [SelfTestCard] 一致
 *
 * 两张卡上下相邻，内边距必须一样（原先一个 16/12、一个 20），否则标题左边缘差 4dp，
 * 一眼就看得出来没对齐。
 */
@Composable
fun NotifyTestCard(state: DashboardState, onTest: () -> Unit) {
    val results = state.notifyTestResults

    AppCard(
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(text = "转发链路", style = AppTypography.h3, color = AppColor.Ink)

            // 先说清「会发给谁」，再让人点。原先只有一句「给每个启用的转发渠道各发一条」，
            // 而实际一个渠道都没启用时，点下去只返回一个空列表 —— 人看到的是
            // 「测过了，什么都没发生」，比什么都不知道更糟。
            val channels = state.notifyChannels
            val hasChannels = channels?.isNotEmpty() == true

            when {
                channels == null -> Text(
                    text = if (state.isRegistered) {
                        "转发断掉是静默的：手机照收、心跳照发，只是码送不到微信里。"
                    } else {
                        // 未注册时连渠道列表都问不到（那个接口要鉴权，
                        // 没带令牌的请求会被 401 挡回来，见 DashboardViewModel.refreshNotifyChannels）。
                        // 与其显示一个空的「会发给：」，不如直接说清现在缺的是什么。
                        "设备还没注册，先扫码连接服务器。"
                    },
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )

                hasChannels -> Text(
                    text = "会发给：${channels.joinToString("、")}",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )

                else -> InlineNotice(
                    // 星号是 Markdown 的写法，界面上不认 —— 原先这一行直接把
                    // `**没有启用**` 原样打出来，屏幕上是两对星号夹着一句话。
                    text = "当前没有启用的转发渠道，点了也不会发到任何地方。" +
                        "短信只会存在服务端，需要管理员在控制台启用渠道。",
                    type = NoticeType.Warning
                )
            }

            AppButton(
                onClick = onTest,
                // 一个渠道都没有、或还没问到时置灰：点下去只会返回空列表，
                // 那不是「测试失败」，但看起来像
                enabled = !state.notifyTesting && hasChannels,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.notifyTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AppSize.spinnerInline),
                        strokeWidth = 2.dp,
                        color = AppColor.InkMuted
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                }
                Text(if (state.notifyTesting) "正在发送…" else "发一条测试消息")
            }

            state.notifyTestError?.let { error ->
                InlineNotice(text = error, type = NoticeType.Danger)
            }

            if (results != null) {
                HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

                if (results.isEmpty()) {
                    InlineNotice(
                        text = "服务器上没有启用的转发渠道 —— 码只会存在服务端，不会有任何转发。",
                        type = NoticeType.Warning
                    )
                } else {
                    results.forEach { result ->
                        CheckResultRow(
                            ok = result.ok,
                            title = result.channelName,
                            // 失败原因要留着：那是唯一能拿去修的东西。
                            // 通过时不显示 message（「已送达」这类话在这里是噪音）。
                            detail = result.message?.takeIf { it.isNotBlank() && !result.ok },
                            trailing = {
                                Text(
                                    text = if (result.ok) "已送达" else "失败",
                                    style = AppTypography.caption,
                                    color = if (result.ok) AppColor.Success else AppColor.Danger
                                )
                            }
                        )
                    }
                }
            }
            // 原先这里还有一个 Spacer(0.dp)：零高的占位什么都不做，
            // 而它上面那层 spacedBy(sm) 已经给了 12dp，底下又跟着卡片的 16dp ——
            // 底部留白因此比顶部多出一倍。删掉之后上下对称。
        }
    }
}
