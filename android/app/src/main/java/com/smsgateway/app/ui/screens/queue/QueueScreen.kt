package com.smsgateway.app.ui.screens.queue

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.smsgateway.app.ui.components.QueueRow
import com.smsgateway.app.ui.components.RefreshableFill
import com.smsgateway.app.ui.components.RefreshableScreen
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.utils.rememberNow

/**
 * 队列页。
 *
 * 显示待上传的短信列表，每条记录包含发送方、内容、验证码、重试信息。
 * 支持下拉刷新：队列里的事都是「刚刚发生的」，手动刷一下是最自然的动作。
 *
 * 列表本身是从 ViewModel 订阅来的（见 DashboardViewModel.observeQueue），
 * 所以后台 worker 传上去一条、或补上号码，这一页会自己更新 —— 下拉刷新是给人用的，
 * 不是给数据同步用的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    // 一秒钟走一次，只为「还有多久重试」那个倒计时 —— 队列不动的时候没有任何状态
    // 发射，倒计时会一直停在那儿，到点了也不会消失。整个列表共用这一个走针，
    // 不要挪进 QueueRow 里让它每行各起一个（见 rememberNow）。
    val now = rememberNow(periodMs = 1_000L)

    // 进页面自己读一次。原先这件事由 MainActivity 的导航回调代劳，
    // 结果是「页面不知道自己该加载什么」，多一个入口就要多记一次。
    LaunchedEffect(Unit) { viewModel.refreshQueueNow() }

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
        RefreshableScreen(
            onRefresh = { viewModel.refreshQueueNow() },
            modifier = Modifier.padding(padding)
        ) {
            when {
                // 「一次都没读完过」才整页转圈。这个分支是必需的：
                // 没有它，第一帧拿到的是空列表，页面会先亮一句「全部已上传」再换成真实内容 ——
                // 而那句话的意思恰恰是「积压已经清空」，是这一页最不该说错的一句。
                state.queueLoading && state.queue.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.queue.isEmpty() -> RefreshableFill {
                    EmptyState(
                        icon = Icons.Default.CloudDone,
                        title = "全部已上传",
                        // 不再挂「返回主页」按钮：左上角那个返回键就是同一个动作，
                        // 而一个实心大按钮摆在这里会成为整页最显眼的元素 ——
                        // 空状态的目的是安慰，不是催人离开。
                        description = "当前没有待上传的短信\n新短信到达后会自动上传到服务器"
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.gutter),
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
        }
    }
}
