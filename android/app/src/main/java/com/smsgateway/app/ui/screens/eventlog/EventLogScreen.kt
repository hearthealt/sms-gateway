package com.smsgateway.app.ui.screens.eventlog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.smsgateway.app.ui.components.RefreshableFill
import com.smsgateway.app.ui.components.RefreshableScreen
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLogScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    // 进页面自己读一次 —— 与队列页同一个约定：页面自己知道该加载什么，
    // 不靠 MainActivity 的导航回调代劳。
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
            // 右上角这个按钮**保留**：手势适合「手已经在列表上」的场景，
            // 按钮适合「第一次来、不知道能下拉」的场景，两者都有人用。
            IconButton(
                onClick = { viewModel.refreshEventLog() },
                enabled = !state.eventLogLoading
            ) {
                Icon(Icons.Default.Refresh, "刷新", tint = AppColor.onBrand)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        RefreshableScreen(
            onRefresh = { viewModel.refreshEventLogNow() },
            modifier = Modifier.padding(padding)
        ) {
            when {
                // 首次加载还没回来。与刷新区分开：复访时列表已有内容，
                // 整页转圈会把它闪没。
                state.eventLogLoading && state.eventLog.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                // 套一层可滚动容器：空状态本身不可滚动，手势不会进嵌套滚动链路，
                // 下拉刷新在这一页最容易想刷的时候（什么都没有）正好是失效的
                // （见 RefreshableFill）。与队列页同一个写法。
                state.eventLog.isEmpty() -> RefreshableFill {
                    EmptyState(
                        // 不用 Refresh 图标：那个图形和右上角那个「刷新」按钮一模一样，
                        // 摆在这一页正中看起来就像「这里有个按钮」，而它不可点。
                        // 这一页要表达的是「回看发生过什么」，用时钟/历史这一类意象。
                        icon = Icons.Default.History,
                        title = "还没有日志",
                        // 采集、上传、设备状态三类说全，天数照旧 —— 剩下的话由设置页那句
                        // 「什么时候该看这里」负责，两处不重复。
                        description = "采集、上传与设备状态的重要事件都会记在这里\n" +
                            "保留最近 ${EventLog.RETENTION_DAYS} 天"
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.gutter),
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
                                modifier = Modifier.fillMaxWidth().padding(AppSpacing.sm)
                            )
                        }
                    }
                }
            }
        }
    }
}
