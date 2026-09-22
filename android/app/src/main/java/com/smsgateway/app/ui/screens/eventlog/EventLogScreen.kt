package com.smsgateway.app.ui.screens.eventlog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.components.EventLogRow
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.util.EventLog

/**
 * 重要日志页。
 *
 * 回答的是那个曾经答不上来的问题：**「这条短信到底走到哪一步了？」**
 * 一条验证码短信静默丢失时，服务端和本地队列都查不到，而采集链路上的每条
 * 失败分支原先都不留痕。这里就是那些分支的出口。
 *
 * 只显示**重要事件**，不是 logcat 的副本：常规心跳、上传重试轮转、界面操作都不进来
 * （判断标准见 [EventLog] 的类注释）。因此这一页通常是安静的 —— 这是对的，
 * 它安静就意味着没有东西在出错。
 *
 * 保留 7 天，由上传 worker 与网关心跳循环两处定期剪枝。
 */
@Composable
fun EventLogScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    // 进页面自己读一次 —— 与队列页同一个约定（见 QueueScreen）：
    // 页面自己知道该加载什么，不靠 MainActivity 的导航回调代劳。
    LaunchedEffect(Unit) { viewModel.refreshEventLogNow() }

    AppScreen(
        title = "重要日志",
        // 条数放标题右边，与服务端记录页、队列页同一个样式。
        // 还没读到时不显示 —— 「共 0 条」在加载中是个假话。
        subtitle = if (state.eventLogTotal > 0) {
            "共 ${state.eventLogTotal} 条 · 保留 ${EventLog.RETENTION_DAYS} 天"
        } else null,
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.refreshEventLog() },
                enabled = !state.eventLogLoading
            ) {
                Icon(Icons.Default.Refresh, "刷新", tint = AppColor.onBrand)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // 首次加载还没回来。与刷新区分开：复访时列表已有内容，
                // 整页转圈会把它闪没。
                state.eventLogLoading && state.eventLog.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.eventLog.isEmpty() -> EmptyState(
                    icon = Icons.Default.Refresh,
                    title = "还没有日志",
                    description = "这里记录短信采集、上传与设备状态的重要事件\n" +
                        "保留最近 ${EventLog.RETENTION_DAYS} 天，便于事后排查",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    itemsIndexed(state.eventLog, key = { _, row -> row.id }) { index, row ->
                        // 进场动画的开关。与队列页同一个写法：用 rememberSaveable，
                        // LazyColumn 按 key 保存每项状态，滚出去再滚回来不会重播动画。
                        var shown by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) { shown = true }

                        AnimatedVisibility(
                            visible = shown,
                            enter = AppAnimations.listItemEnter(index)
                        ) {
                            EventLogRow(row)
                        }
                    }

                    // 展示有上限（最近 500 条），说清楚 —— 否则「共 800 条」配 500 行列表
                    // 看起来像丢了 300 条。
                    if (state.eventLogTotal > state.eventLog.size) {
                        item {
                            Text(
                                text = "只显示最近 ${state.eventLog.size} 条",
                                style = AppTypography.hint,
                                color = AppColor.InkMuted,
                                modifier = Modifier.fillMaxSize().padding(AppSpacing.sm)
                            )
                        }
                    }
                }
            }
        }
    }
}
