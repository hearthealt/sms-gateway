package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 让「不是列表」的内容（空状态、错误态）也能被下拉刷新带起来。
 *
 * 下拉刷新靠嵌套滚动工作：手势先交给内容，内容拉到头之后**多出来的那点位移**
 * 才会上报给下拉刷新的连接。空状态不是一个可滚动组件，位移压根不会上报 ——
 * 于是最想刷新一下的时候（列表是空的）反而刷不动。
 *
 * 这里垫一层撑满视口的可滚动容器，把手势接回那条链路；内容仍然垂直居中，
 * 与直接摆一个 [EmptyState] 的观感一致。
 */
@Composable
fun RefreshableFill(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 撑到视口高度：不给下限的话，可滚动容器会缩到内容高度，
                // 居中就变成了「贴顶」（因为垂直方向已经没有多余空间可分）
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center
        ) { content() }
    }
}

/**
 * 列表页共用的下拉刷新外壳：手势 + 指示器 + 「等这次真的回来再收手」。
 *
 * 三个列表页（队列 / 服务端记录 / 重要日志）原先各自把这一整套抄了一遍 ——
 * 每份都要记得：`nestedScroll` 挂在 Box 上、指示器要贴 `TopCenter`、
 * `isRefreshing` 置位后必须**在 finally 里** `endRefresh()`（漏了就是转圈收不回来，
 * 而屏幕上没有任何提示，只有一个永远转的圈）。抄三遍就有三处可能漏。
 *
 * @param onRefresh 真正要等的那件事。用挂起版本（如 `refreshQueueNow`），
 *   不是「把活派给 viewModelScope 就返回」的那层壳 —— 后者在数据回来之前就返回了，
 *   圈会在请求还没落地时被收掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableScreen(
    onRefresh: suspend () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(PullToRefreshState) -> Unit
) {
    val pullState = rememberPullToRefreshState()

    // rememberUpdatedState：LaunchedEffect 只在 isRefreshing 变化时重启，闭包里那份
    // onRefresh 永远是最初组合那一刻的。对于当前的调用方（都是稳定的方法引用）没有差别，
    // 但这层壳将来的用户未必如此，而「传了新 lambda 却仍跑旧的」是极难查的一类。
    val currentRefresh by rememberUpdatedState(onRefresh)

    LaunchedEffect(pullState.isRefreshing) {
        if (pullState.isRefreshing) {
            // 用挂起函数而不是派发出去就返回：转圈必须等到这次读库/请求真的结束再收手。
            // finally 收尾：刷新失败时也要把圈收回来，否则页面上留一个永远转的圈。
            try {
                currentRefresh()
            } finally {
                pullState.endRefresh()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pullState.nestedScrollConnection)
    ) {
        content(pullState)

        PullToRefreshContainer(
            state = pullState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
