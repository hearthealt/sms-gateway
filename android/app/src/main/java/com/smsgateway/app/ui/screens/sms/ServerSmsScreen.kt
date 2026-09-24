package com.smsgateway.app.ui.screens.sms

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.components.InlineNotice
import com.smsgateway.app.ui.components.NoticeType
import com.smsgateway.app.ui.components.RefreshableFill
import com.smsgateway.app.ui.components.RefreshableScreen
import com.smsgateway.app.ui.components.ServerSmsRow
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.util.UploadEvents
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
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 滚到底就续下一页。不摆「加载更多」按钮：那种按钮在列表末尾，而要看更多
    // 恰恰是在滚到末尾的时候 —— 让「继续滚」本身把它带回来，比多一次点击顺
    // （下拉刷新保持它本来的意思：回到最新那一页）。
    // state 是普通参数、不是 State 委托，而 LaunchedEffect(listState) 的 block 会被
    // 协程长期持有 —— 闭包里那份 state 永远是**首次组合那一刻**的。首次组合时
    // smsRecords 还是空的，`size` 恒为 0，于是判断退化成 `lastVisible >= -3`（恒真），
    // 滚一下就把历史连续全拉完。rememberUpdatedState 让闭包读到的是当前值。
    //
    // 不能改成把 size 放进 LaunchedEffect 的 key：那样每次翻页都会重启 flow，
    // 而重启会立刻重新发射一次当前值，又触发一次加载 —— 变成链式翻页。
    val recordCount by rememberUpdatedState(state.smsRecords.size)

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null &&
                    lastVisible >= recordCount - LOAD_MORE_THRESHOLD
                ) {
                    viewModel.loadMoreServerSms()
                }
            }
    }

    LaunchedEffect(Unit) { viewModel.loadServerSmsNow() }

    // 回前台刷新。Activity 没被销毁时组合不会重建，上面那句 LaunchedEffect(Unit)
    // 不会再跑 —— 而「切出去看别的、回来时列表已经过期」正是最需要刷新的时刻。
    //
    // firstResume 不能省：LifecycleRegistry 在 addObserver 时会把新观察者**同步到当前状态**，
    // 于是进页面的那一刻就会来一次 ON_RESUME —— 不跳过的话每次进页面都要发两遍请求。
    // 用 DisposableEffect 内的普通局部变量而不是 Compose state：它是纯排重用的，
    // 不参与重组，写成 state 反而会因为变化再触发一次组合。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (firstResume) {
                firstResume = false
                return@LifecycleEventObserver
            }
            // 会回到第 1 页 —— 与下拉刷新同义（「回到最新那一页」），是刻意的。
            viewModel.loadServerSms()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 停在这一页时，本机上传成功就立刻刷新 —— 这是这一页唯一的「有新数据了」本地信号。
    // 挂在页面上而不是 ViewModel 的轮询里：协程随页面销毁，离开页面就不再发请求。
    //
    // StateFlow 订阅时会**先重放当前值**，而那不是「刚刚发生」的事件。只判 count > 0
    // 挡不住它：只要本进程传成功过一次，之后每次进这一页都会白发一次请求，
    // 还会把翻页位置顶回第 1 页。所以拿当前值当基线，只对变化作出反应。
    LaunchedEffect(Unit) {
        var lastSeen = UploadEvents.successCount.value
        UploadEvents.successCount.collect { count ->
            if (count != lastSeen) {
                lastSeen = count
                viewModel.onUploadedWhileViewingServerSms()
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
            IconButton(
                onClick = { viewModel.loadServerSms() },
                // 请求在飞的时候禁掉，免得连点发出一串请求（与自检页那个刷新按钮一致）
                enabled = !state.smsLoading
            ) {
                Icon(Icons.Default.Refresh, "刷新", tint = AppColor.onBrand)
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        RefreshableScreen(
            onRefresh = { viewModel.loadServerSmsNow() },
            modifier = Modifier.padding(padding)
        ) { pullState ->
            when {
                // 「一次都没读完过」才整页转圈。复访时 smsRecords 非空，转圈会把它闪没 ——
                // 而那种情况下用户看到的「什么都没发生」正是「以为没在刷新」的由来。
                state.smsLoading && !state.smsLoaded -> Box(
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
                        // 不挂「返回主页」按钮：左上角的返回键是同一个动作，
                        // 而空状态里摆一个实心大按钮会变成整页最显眼的元素。
                        description = "本机上传成功的短信会出现在这里"
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AppSpacing.gutter),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    state.smsError?.let { error ->
                        item { RefreshFailedNotice(error = error, onRetry = { viewModel.loadServerSms() }) }
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

            // 列表里已经有内容时，「在刷新」用顶部一条细进度条表达，而不是整页转圈 ——
            // 不遮内容、不跳布局。下拉刷新自己那个圈已经在转了，别叠第二条。
            if (state.smsLoading && state.smsLoaded && !pullState.isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
        }
    }
}

/**
 * 列表顶部一行：刷新失败的原因 + 一个就地重试的入口。
 *
 * 原先这里只是一行红色小字。它太弱有两个后果：一是**看不见**（列表内容一多就淹没在
 * 卡片之间），二是**没有下一步**（用户知道失败了，但只能退出去再进来，或者猜着再去点
 * 顶栏那个刷新）。所以换成 [InlineNotice]：有色块、有图标，右边直接给「重试」。
 *
 * 它不是「整页报错」的替代品 —— 一条记录都没有时走的是 EmptyState 那条路。
 * 这里是「已经有内容、只是这次没刷上」的降级表达。
 */
@Composable
private fun RefreshFailedNotice(error: String, onRetry: () -> Unit) {
    InlineNotice(
        text = "刷新失败：$error\n下面是上次读到的记录。",
        type = NoticeType.Danger,
        action = {
            TextButton(onClick = onRetry) { androidx.compose.material3.Text("重试") }
        }
    )
}

/**
 * 离列表末尾还有几项就开始续下一页。
 *
 * 留 3 项而不是「到最后一项才加载」：到顶了再请求，用户会看到一片空白等一秒。
 * 提前一点，下一页通常在他滚到那儿时已经到了。
 */
private const val LOAD_MORE_THRESHOLD = 3
