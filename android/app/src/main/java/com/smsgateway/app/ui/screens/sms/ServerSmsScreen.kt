package com.smsgateway.app.ui.screens.sms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.components.RefreshableFill
import com.smsgateway.app.ui.components.ServerSmsRow
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 服务端记录页。
 *
 * 显示服务端已接收的短信列表，每条记录包含发送方、内容、验证码、服务端状态、接收时间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSmsScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) { viewModel.loadServerSmsNow() }

    // 这一页的数据在服务端，下拉刷新是它唯一的「我要最新」入口，
    // 所以要等请求真的回来再收手（见 loadServerSmsNow）。
    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            // finally 收尾：现在两个加载函数内部把异常全收口了，走不到 else 分支，
            // 但那是它们的实现细节 —— 将来谁让它们抛异常，指示器就再也收不回来
            try {
                viewModel.loadServerSmsNow()
            } finally {
                pullState.endRefresh()
            }
        }
    }

    AppScreen(
        title = "服务端记录",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.loadServerSms() }) {
                Icon(Icons.Default.Refresh, "刷新", tint = AppColor.onBrand)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullState.nestedScrollConnection)
        ) {
            when {
                state.smsLoading && state.smsRecords.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                // 失败要说清楚是「读不到」还是「真没有」：原先两种情况共用一句
                // 「服务端还没有记录」，连不上服务器时看起来就像服务端是空的。
                //
                // 只在这一条都没有的时候才把整页换成报错。列表里已经有记录时，
                // 一次网络抖动不该把用户眼前的数据整块换走 —— 那种情况降级成
                // 列表顶部一行提示（见下面的 item）。
                state.smsError != null && state.smsRecords.isEmpty() -> RefreshableFill {
                    EmptyState(
                        icon = Icons.Default.CloudOff,
                        title = "读不到服务端记录",
                        description = state.smsError,
                        actionLabel = "重试",
                        onAction = { viewModel.loadServerSms() }
                    )
                }

                state.smsRecords.isEmpty() -> RefreshableFill {
                    EmptyState(
                        icon = Icons.Default.Sms,
                        title = "服务端还没有记录",
                        description = "本机上传成功的短信会出现在这里",
                        actionLabel = "返回主页",
                        onAction = onBack
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    item {
                        ListHeader(
                            error = state.smsError,
                            total = state.smsTotal,
                            shown = state.smsRecords.size
                        )
                    }

                    itemsIndexed(
                        state.smsRecords,
                        // 用服务端的主键做 key。SmsView 是带 id 的（后端 model/dto/SmsView.java），
                        // 不要拿「发送方 + 时间 + 内容」拼：正文为空时 hash 部分是字面量 "null"，
                        // 两条同发送方、同秒、正文为空的记录会拼出同一个 key，而 LazyColumn
                        // 撞 key 是直接抛 IllegalArgumentException —— 是崩溃不是错位。
                        key = { _, record -> record.id }
                    ) { index, record ->
                        // rememberSaveable：LazyColumn 按 key 保存每项状态，滚出去再滚回来不重播动画
                        var shown by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(Unit) { shown = true }

                        AnimatedVisibility(
                            visible = shown,
                            enter = AppAnimations.listItemEnter(index)
                        ) {
                            ServerSmsRow(record)
                        }
                    }

                    // 还有没加载完的就给一个入口。放在列表末尾而不是自动触发：
                    // 现场多数时候只看最近几条，不该为了「可能要看」把流量和等待
                    // 都提前花掉；而列表只有 20 条时，滚到底就是一次点击的距离。
                    if (state.smsRecords.size < state.smsTotal) {
                        item {
                            LoadMoreRow(
                                loading = state.smsLoading,
                                remaining = state.smsTotal - state.smsRecords.size,
                                onLoadMore = { viewModel.loadMoreServerSms() }
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

/**
 * 列表顶部的一行说明：刷新失败的原因（有的话）+ 加载进度。
 *
 * 计数不是装饰：一页只有 20 条，这个上限原先在界面上是**看不见**的 ——
 * 用户数了 20 条，会以为服务端只有 20 条。说清楚「已加载 20 / 共 57 条」，
 * 才知道列表下面还有东西、该往下滚。
 */
@Composable
private fun ListHeader(error: String?, total: Long, shown: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
        if (error != null) {
            Text(
                text = "刷新失败：$error —— 下面是上次读到的记录。",
                style = AppTypography.caption,
                color = AppColor.Danger
            )
        }
        val count = if (total > shown) {
            "已加载 $shown / 共 $total 条"
        } else {
            "共 $total 条"
        }
        Text(text = count, style = AppTypography.caption, color = AppColor.InkMuted)
    }
}

/**
 * 列表末尾的「加载更多」。
 *
 * 正在读的时候换成一个小转圈：按钮本身不能表达「上一次还没回来」，
 * 而连点会把同一页请求发好几遍（DashboardViewModel.loadMoreServerSms 会挡，
 * 但按钮得让这件事看得出来）。
 */
@Composable
private fun LoadMoreRow(loading: Boolean, remaining: Long, onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xs),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onLoadMore) {
                Text("加载更多（还有 $remaining 条）", style = AppTypography.bodySmall)
            }
        }
    }
}
