package com.smsgateway.app.ui.theme

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically

/**
 * 动画配置。
 *
 * 时长只有两档，全应用只用这两档 —— 原先各处自己写 tween(300) 之类，
 * 改一次手感要翻遍所有页面。
 */
object AppAnimations {

    /** 短：列表项入场、按钮反馈。 */
    const val DURATION_SHORT = 180

    /** 中：状态颜色渐变。 */
    const val DURATION_MEDIUM = 300

    /** 状态转换的颜色渐变（成功/失败/停用之间的过渡）。 */
    fun <T> colorTransition() = tween<T>(durationMillis = DURATION_MEDIUM)

    /**
     * 列表项入场：淡入 + 从下方轻微上移，按序错开。
     *
     * 错开量封顶在 [STAGGER_LIMIT] 项：队列里排到第 30 条时，若还按 index × 50ms 算，
     * 这一条要等 1.5 秒才出现 —— 动画就不像「进场」，像卡了。
     */
    fun listItemEnter(index: Int) = fadeIn(
        animationSpec = tween(durationMillis = DURATION_SHORT, delayMillis = staggerDelay(index))
    ) + slideInVertically(
        initialOffsetY = { it / 3 },
        animationSpec = tween(durationMillis = DURATION_SHORT, delayMillis = staggerDelay(index))
    )

    private fun staggerDelay(index: Int) = index.coerceIn(0, STAGGER_LIMIT) * STAGGER_STEP_MS

    private const val STAGGER_LIMIT = 8
    private const val STAGGER_STEP_MS = 40
}
