package com.smsgateway.app.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 触觉反馈辅助类。
 *
 * 封装 Android 系统的触觉反馈 API，提供统一的调用接口。
 */
class HapticFeedback(private val view: View) {
    /**
     * 轻触反馈（按钮点击）。
     */
    fun light() {
        view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /**
     * 中等反馈（开关切换）。
     */
    fun medium() {
        view.performHapticFeedback(
            HapticFeedbackConstants.CONTEXT_CLICK,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /**
     * 重反馈（长按、删除等重要操作）。
     */
    fun heavy() {
        view.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }
}

/**
 * Composable 中获取触觉反馈实例。
 */
@Composable
fun rememberHapticFeedback(): HapticFeedback {
    val view = LocalView.current
    return remember(view) { HapticFeedback(view) }
}
