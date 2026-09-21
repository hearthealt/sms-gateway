package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.components.MetricCard
import com.smsgateway.app.ui.theme.AppSpacing

/**
 * 三个指标块 - 垂直布局。
 *
 * 每个指标独立成卡，数字更大更醒目。
 * 相比原来横向三列的布局，垂直布局更清晰、更易点击。
 */
@Composable
fun MetricGrid(
    state: DashboardState,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
    ) {
        // 待上传
        MetricCard(
            icon = Icons.Default.CloudUpload,
            label = "待上传",
            value = state.pendingCount,
            hint = if (state.pendingCount == 0) "全部已上传" else "本地队列中",
            alert = state.pendingCount > 0,
            onClick = onOpenQueue
        )

        // 今日短信
        MetricCard(
            icon = Icons.Default.Forum,
            label = "今日短信",
            value = state.todaySmsCount,
            hint = if (state.todaySmsCount == 0) "今天还没收到" else "查看详情",
            alert = false,
            onClick = onOpenServerSms
        )

        // 今日验证码
        MetricCard(
            icon = Icons.Default.VerifiedUser,
            label = "今日验证码",
            value = state.todayCodeCount,
            hint = when {
                state.todayCodeCount > 0 -> "查看详情"
                state.todaySmsCount > 0 -> "有短信，未提取到"
                else -> "今天还没收到"
            },
            alert = state.todayCodeCount == 0 && state.todaySmsCount > 0,
            onClick = onOpenServerSms
        )
    }
}
