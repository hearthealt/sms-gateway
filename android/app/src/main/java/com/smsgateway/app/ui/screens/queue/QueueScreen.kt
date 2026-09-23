package com.smsgateway.app.ui.screens.queue

import androidx.compose.animation.AnimatedVisibility
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.components.QueueRow
import com.smsgateway.app.ui.components.RefreshableFill
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.utils.rememberNow
import kotlinx.coroutines.launch

/**
 * 队列页。
 *
 * 显示待上传的短信列表，每条记录包含发送方、内容、验证码、重试信息。
 * 支持下拉刷新：队列里的事都是「刚刚发生的」，手动刷一下是最自然的动作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val pullState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一秒钟走一次，只为「还有多久重试」那个倒计时 —— 队列不动的时候没有任何状态
    // 发射，倒计时会一直停在那儿，到点了也不会消失。整个列表共用这一个走针，
    // 不要挪进 QueueRow 里让它每行各起一个（见 rememberNow）。
    val now = rememberNow(periodMs = 1_000L)

    // 进页面自己读一次。原先这件事由 MainActivity 的导航回调代劳，
    // 结果是「页面不知道自己该加载什么」，多一个入口就要多记一次。
    LaunchedEffect(Unit) { viewModel.refreshQueueNow() }

    // 松手后 isRefreshing 置位，这里等这次读库真的结束再收手 ——
    // 所以用的是挂起版的 refreshQueueNow，而不是派发完就返回的 refreshQueue。
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            // finally 收尾：转圈收不回来是比「刷新失败」更难查的那种毛病 ——
            // 屏幕上没有任何一处提示，只有一个永远转的圈
            try {
                viewModel.refreshQueueNow()
            } finally {
                pullState.endRefresh()
            }
        }
    }

    AppScreen(
        // 条数放标题右边，与服务端记录页同一个样式（原先写成「待上传（N）」，
        // 同一个数字两种写法，两页来回看会觉得别扭）
        title = "待上传",
        subtitle = "共 ${state.queue.size} 条",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.refreshQueue() }) {
                Icon(Icons.Default.Refresh, "刷新", tint = AppColor.onBrand)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            if (state.queue.isEmpty()) {
                // 套一层可滚动容器：空状态本身不可滚动，手势不会进嵌套滚动链路，
                // 下拉刷新在这一页最容易想刷的时候正好是失效的（见 RefreshableFill）
                RefreshableFill {
                    EmptyState(
                        icon = Icons.Default.CloudDone,
                        title = "全部已上传",
                        description = "当前没有待上传的短信\n新短信到达后会自动上传到服务器",
                        actionLabel = "返回主页",
                        onAction = onBack
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    itemsIndexed(state.queue, key = { _, row -> row.id }) { index, row ->
                        // 进场动画的开关。用 rememberSaveable 而不是 remember：
                        // LazyColumn 按 key 保存每项的状态，滚出去再滚回来不会重播动画。
                        var shown by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) { shown = true }

                        AnimatedVisibility(
                            visible = shown,
                            enter = AppAnimations.listItemEnter(index)
                        ) {
                            QueueRow(
                                row = row,
                                now = now,
                                onRetry = { viewModel.retrySms(row.id) },
                                onDelete = { viewModel.deleteSms(row.id) }
                            )
                        }
                    }
                }
            }

            PullToRefreshContainer(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}
