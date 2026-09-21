package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing

/**
 * 主页。
 *
 * 顶部蓝色渐变头 + 圆角白色内容区，内容区包含：
 * - HomeBanners（权限/禁用/电池横幅，按需显示）
 * - HeroCard（状态 + 启停开关 + 今日概览一处）
 * - IdentityRow（设备信息）
 *
 * 页面只有两块内容：**要盯的**（HeroCard）和**配好就不动的**（IdentityRow）。
 * 原先夹在中间的三张指标卡已经并进 HeroCard —— 竖排三张卡把内容顶到上半屏、
 * 下半屏全空，而它们只表达三个数字（见 HeroCard 的说明）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: DashboardState,
    snackbarHostState: SnackbarHostState,
    checks: DeviceChecks,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onToggleService: () -> Unit,
    onCheckStatus: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                onOpenQuickConnect = onOpenQuickConnect,
                onOpenSelfTest = onOpenSelfTest,
                onOpenSettings = onOpenSettings
            )

            // 内容做成一张顶部圆角的「纸」，压在渐变头部上。
            // 这是参考图里最值得留下的一笔：成本只是一个 Surface + 圆角，辨识度却上来了。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = AppColor.Screen
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.md)
                        // 底部让开手势条/导航栏，否则最后一行会被压在下面
                        .navigationBarsPadding()
                        .padding(top = AppSpacing.lg, bottom = AppSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    HomeBanners(
                        state = state,
                        checks = checks,
                        onCheckStatus = onCheckStatus
                    )

                    // 状态 + 启停 + 今日概览合成一张主角卡：状态区上色、指标压成一行。
                    // 理由见 HeroCard 的说明（原先四块平铺、没有视觉重心）。
                    HeroCard(
                        state = state,
                        onToggleService = onToggleService,
                        onOpenQueue = onOpenQueue,
                        onOpenServerSms = onOpenServerSms
                    )

                    // 设备身份单独留一口气：它是配好就不再动的只读信息，
                    // 与上面「要盯着的状态」之间要有分界，否则整列读起来一样重。
                    Spacer(modifier = Modifier.height(AppSpacing.xs))

                    IdentityRow(state = state, onOpenSettings = onOpenSettings)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * 设备检查结果。
 */
data class DeviceChecks(
    val smsPermission: Boolean,
    val ignoringBatteryOptimizations: Boolean
)
