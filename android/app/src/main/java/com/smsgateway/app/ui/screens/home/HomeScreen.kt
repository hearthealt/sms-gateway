package com.smsgateway.app.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing

/**
 * 主页。
 *
 * 顶部蓝色渐变头 + 圆角白色内容区，按「先看什么」排序：
 * - HomeBanners（权限/禁用/电池横幅，按需显示）
 * - HeroCard（状态 + 启停开关 + 今日概览）——在跑吗
 * - RecentSmsCard（最近收到几条 + 复制今日验证码）——还在收吗
 * - SelfTestRow（自检入口 + 上次结论）——六项还过不过
 * - IdentityRow（设备信息）——配好就不动，所以放最后
 *
 * 前两块原先是一张状态卡 + 三张指标卡平铺竖排，四块同宽同圆角同投影、只差底色，
 * 页面没有视觉重心；三张指标卡又只表达三个数字却占掉全页最贵的地方。
 * 现在状态与指标并成一张主角卡，中间补上「最近收到」与自检 —— 见各自文件的说明。
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
    onCheckStatus: () -> Unit,
    onCopyTodayCodes: () -> Unit,
    onCopyPayloadConsumed: () -> Unit
) {
    val context = LocalContext.current

    // 「复制今日验证码」：ViewModel 备好文本，这里写剪贴板 —— 剪贴板要 Context，
    // 而一次性内容的通道与 registerMessage / settingsMessage 是同一套。
    //
    // 空文本也要有回执：点了没反应最难查，现场会以为按钮坏了。
    LaunchedEffect(state.copyPayload) {
        val payload = state.copyPayload ?: return@LaunchedEffect
        if (payload.isBlank()) {
            snackbarHostState.showSnackbar("今天还没有提取到验证码")
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("验证码", payload))
            // Android 13 起系统自己会弹一个「已复制」，但只说复制了、不说几条 ——
            // 条数才是这里要交代的（联调时一眼知道拿全了没有）
            snackbarHostState.showSnackbar("已复制 ${payload.lines().size} 条验证码")
        }
        onCopyPayloadConsumed()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                onOpenQuickConnect = onOpenQuickConnect,
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

                    // 最近收到：主页上唯一说「此刻真的收到东西了」的一块。
                    // 状态卡看的是心跳（到服务器通不通），心跳正常但卡停了照样是绿的。
                    RecentSmsCard(
                        state = state,
                        onOpenServerSms = onOpenServerSms,
                        onCopyTodayCodes = onCopyTodayCodes
                    )

                    // 自检入口从顶栏挪到这里：那边只有一个图标，现场不知道那是自检。
                    // 这一行同时显示上次结论，不过的时候自己变红。
                    SelfTestRow(state = state, onOpenSelfTest = onOpenSelfTest)

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
