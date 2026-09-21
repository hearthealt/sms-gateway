package com.smsgateway.app.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * 一个会自己走的「现在」。
 *
 * 直接写 `val now = System.currentTimeMillis()` 有两个坑，合起来正好毁掉这一页最需要
 * 说清的那件事：
 *
 * 1. 它只在组合期求值一次。心跳正常时看不出问题（心跳时间每 30 秒变一次，会顺带
 *    触发重组把 now 刷新），但**心跳一停就不再有任何状态发射**了 —— 于是
 *    「距上次心跳超过 90 秒」这个判断永远不再重新求值，界面会一直停在
 *    「网关运行中 · 已连接 · 刚刚」，永远不翻成「连接中断」。
 * 2. 反过来，若把它做成每帧都变的状态，页面就会永远重组下去。
 *
 * 所以：低频、只在页面可见时走，值为 state，消费方照旧当普通 Long 用。
 *
 * @param periodMs 走针间隔。相对时间（「12 分钟前」）5 秒足够；
 *   倒计时（「45 秒后」）要 1 秒，否则数字会跳。
 */
@Composable
fun rememberNow(periodMs: Long = 5_000L): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lifecycleOwner, periodMs) {
        // 用 RESUMED 而不是 Unit：消费它的地方算的全是「距今多久」「还有多久」，
        // 页面不可见时没人看，却会照旧按 periodMs 触发重组。
        // 切回来时会立刻重取一次，不会先显示一个停在上次退出时的旧值。
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(periodMs)
            }
        }
    }

    return now
}
