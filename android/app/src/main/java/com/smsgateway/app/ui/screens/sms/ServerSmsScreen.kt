package com.smsgateway.app.ui.screens.sms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

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
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 滚到底就续下一页。不摆「加载更多」按钮：那种按钮在列表末尾，而要看更多
    // 恰恰是在滚到末尾的时候 —— 让「继续滚」本身把它带回来，比多一次点击顺
    // （下拉刷新保持它本来的意思：回到最新那一页）。
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null &&
                    lastVisible >= state.smsRecords.size - LOAD_MORE_THRESHOLD
                ) {
                    viewModel.loadMoreServerSms()
                }
            }
    }

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

    // 原先这里还有一个「复制今日验证码」（整页的码一次拿走）。删掉了：
    // 一格点一下复制眼前这条才是常态，而顶栏那个看不出范围的图标只会让人
    // 以为点下去是复制眼前这条 —— 结果拿到几十条。见 ServerSmsRow 里的单条复制。
    AppScreen(
        title = "服务端记录",
        onBack = onBack,
        // 条数放在标题右边：它是一眼扫过的量，占一行不如贴着标题。
        // 还没读到时不显示 —— 「共 0 条」在加载中是个假话。
        subtitle = if (state.smsTotal > 0) "共 ${state.smsTotal} 条" else null,
        actions = {
            IconButton(onClick = { viewModel.loadServerSms() }) {
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
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    item {
                        ListHeader(error = state.smsError)
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
                            ServerSmsRow(
                                record = record,
                                onCopyCode = { code ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("验证码", code))
                                    scope.launch { snackbarHostState.showSnackbar("已复制 $code") }
                                }
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
 * 列表顶部一行：刷新失败的原因（有的话）+ 总数。
 *
 * 条数已经挪到顶栏标题右边（见 AppScreen 的 subtitle）—— 同一个数字摆两处，
 * 一处在滚动区里会跟着滚走，不如只在头部说一次。这里只剩失败提示。
 */
@Composable
private fun ListHeader(error: String?) {
    if (error == null) return

    Text(
        text = "刷新失败：$error —— 下面是上次读到的记录。",
        style = AppTypography.caption,
        color = AppColor.Danger,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 离列表末尾还有几项就开始续下一页。
 *
 * 留 3 项而不是「到最后一项才加载」：到顶了再请求，用户会看到一片空白等一秒。
 * 提前一点，下一页通常在他滚到那儿时已经到了。
 */
private const val LOAD_MORE_THRESHOLD = 3
