package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
