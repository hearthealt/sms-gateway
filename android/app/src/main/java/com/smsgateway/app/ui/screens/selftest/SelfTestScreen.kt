package com.smsgateway.app.ui.screens.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing

/**
 * 自检页。
 *
 * 只负责页面框架与三种态（跑着 / 没跑过 / 有结果），结果卡片在 [SelfTestCard]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    AppScreen(
        title = "自检",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.runSelfTest() },
                enabled = !state.selfTestRunning
            ) {
                Icon(Icons.Default.Refresh, "重新自检", tint = AppColor.onBrand)
            }
        }
    ) { padding ->
        when {
            // 第一次自检时列表还是空的，除了转圈没别的可说
            state.selfTestRunning && state.selfTest.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            // 这条只有「自检跑完却一项都没产出」时才到得了（进页面已自动跑过一次），
            // 留着是为了那种情况下页面别是一片空白
            state.selfTest.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.FactCheck,
                    title = "还没有自检结果",
                    description = "点右上角开始一次自检\n会依次检查权限、网关状态、服务器连通性",
                    actionLabel = "开始自检",
                    onAction = { viewModel.runSelfTest() }
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                SelfTestCard(state.selfTest)
            }
        }
    }
}
