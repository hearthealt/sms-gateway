package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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

/** 与 [com.smsgateway.app.ui.AppScreen] 同一个上限：平板上不把卡片拉满整屏。 */
private val CONTENT_MAX_WIDTH = 640.dp

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
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 整屏铺一层品牌色打底，头部那条渐变画在它上面。
            //
            // 这一层是「纸」那个圆角效果的**全部前提**：内容区是一张顶部圆角的 Surface，
            // 圆角切掉的那两块露出的是它下面这一层。原先这里什么都没有 ——
            // 露出来的是窗口底色，与 Surface 的页面底色几乎同色，于是圆角等于白切，
            // 那张纸看起来就是个方角矩形。用 Brand 是因为渐变的最下沿正好就是 Brand，
            // 内容区的上边缘又正好接在渐变的末尾 —— 圆角处露出的颜色与它上方无缝接上。
            .background(AppColor.Brand)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                onOpenQuickConnect = onOpenQuickConnect,
                onOpenSelfTest = onOpenSelfTest,
                onOpenSettings = onOpenSettings
            )

            // 内容做成一张顶部圆角的「纸」，压在渐变头部上（见上面那层背景色的说明）。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = AppColor.Screen
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = CONTENT_MAX_WIDTH)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = AppSpacing.gutter)
                            // 底部让开手势条/导航栏，否则最后一行会被压在下面
                            .navigationBarsPadding()
                            .padding(top = AppSpacing.sectionGap, bottom = AppSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.cardGap)
                    ) {
                        HomeBanners(checks = checks)

                        // 状态 + 启停 + 今日概览合成一张主角卡：状态区上色、指标压成一行。
                        // 理由见 HeroCard 的说明（原先四块平铺、没有视觉重心）。
                        HeroCard(
                            state = state,
                            onToggleService = onToggleService,
                            onOpenQueue = onOpenQueue,
                            onOpenServerSms = onOpenServerSms,
                            onOpenQuickConnect = onOpenQuickConnect,
                            // 「被禁用」那条横幅并进了状态卡（见 HomeBanners 的说明），
                            // 所以「检查状态」这个动作也跟着搬到那里
                            onCheckStatus = onCheckStatus
                        )

                        // 趋势：近 7 天 + 今日逐小时。回答的是「正在变坏吗」——
                        // 设备坏掉多半先少一半，而不是戛然而止，而这件事逐条看列表看不出来。
                        // 数据没回来时摆占位骨架（不是整块不渲染）：后者的表现是进来时那儿空着、
                        // 几百毫秒后两张图凭空冒出来把整页往下弹一截，看着像页面刚才是坏的。
                        TrendCard(trend = state.trend, attempted = state.trendAttempted)

                        // 设备身份单独留一口气：它是配好就不再动的只读信息，
                        // 与上面「要盯着的状态」之间要有分界，否则整列读起来一样重。
                        // 分界只靠下面 IdentityRow 自己那张卡片就够 ——
                        // 原先这里还有一个 Spacer(xs)，叠上卡片间距之后实际是 40dp，
                        // 而间距系统里没有任何一档是 40dp。
                        IdentityRow(state = state, onOpenSettings = onOpenSettings)
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Snackbar 默认贴屏幕底，而底部是手势条所在的那一条 ——
                // 提示文字会被它压住一截（手势条是半透明的，字与它在同一处叠着）。
                .navigationBarsPadding()
        )
    }
}

/**
 * 设备检查结果。
 *
 * 三项都在同一个地方算、也都在同一个地方重算（MainActivity 的 rememberDeviceChecks）：
 * 它们都依赖运行时权限或系统设置，而权限可能在**任何**一次弹窗或去系统设置的往返里变化。
 *
 * @param phonePermission 电话权限。缺它的直接后果不是「读不到号码」这么轻 ——
 *   见它的消费者 HomeBanners 里的说明。
 */
data class DeviceChecks(
    val smsPermission: Boolean,
    val phonePermission: Boolean,
    val ignoringBatteryOptimizations: Boolean
)
