package com.smsgateway.app.ui.screens.home

import androidx.compose.runtime.Composable
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.components.BatteryBannerCompact
import com.smsgateway.app.ui.components.DisabledBannerCompact
import com.smsgateway.app.ui.components.PermissionBannerCompact

/**
 * 主页顶部的警告横幅组 - 简化版。
 *
 * 按照优先级从高到低显示：权限 > 禁用状态 > 电池优化
 * 使用简化的浮动提示条样式，从 ~100dp 压缩到 ~48dp。
 */
@Composable
fun HomeBanners(
    state: DashboardState,
    checks: DeviceChecks,
    onCheckStatus: () -> Unit
) {
    // 顺序即优先级：最可能让人「什么也没发生」的问题排在最上面
    if (!checks.smsPermission) {
        PermissionBannerCompact()
    }

    if (state.isDisabled) {
        // testResult 的唯一消费者就是这块横幅（见 DashboardState.testResult 的注释）
        DisabledBannerCompact(
            onCheckStatus = onCheckStatus,
            checkResult = state.testResult
        )
    }

    if (!checks.ignoringBatteryOptimizations) {
        BatteryBannerCompact()
    }
}
