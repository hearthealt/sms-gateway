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
 * 顶部蓝色渐变头 + 圆角白色内容区，按「先看什么」排序：
 * - HomeBanners（权限/禁用/电池横幅，按需显示）
 * - HeroCard（状态 + 启停开关 + 今日概览）——在跑吗 / 今天收了多少
 * - TrendCard（近 7 天 + 今日逐小时）——正在变坏吗
 * - IdentityRow（设备信息）——配好就不动，所以放最后
 *
 * 刻意只有这三块。「最近收到」那三行与「自检」那一行曾经也在这一列，
 * 但它们各自要说的（此刻还在收吗 / 六项还过不过）已经被趋势图与顶栏的自检入口覆盖，
 * 摆在这里只是让主页又变成一屏列表 —— 这个页面的目标始终是**一眼看完**。
 *
 * 顶栏两个入口：扫一扫、自检；设置在最右（最常碰的放最顺手的位置）。
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

                    // 趋势：近 7 天 + 今日逐小时。回答的是「正在变坏吗」——
                    // 设备坏掉多半先少一半，而不是戛然而止，而这件事逐条看列表看不出来。
                    // 数据没回来时摆占位骨架（不是整块不渲染）：后者的表现是进来时那儿空着、
                    // 几百毫秒后两张图凭空冒出来把整页往下弹一截，看着像页面刚才是坏的。
                    TrendCard(trend = state.trend, attempted = state.trendAttempted)

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
