package com.smsgateway.app.ui.screens.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.SelfTestAction
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.EmptyState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.utils.SystemSettings

/**
 * 自检页。
 *
 * 只负责页面框架与三种态（跑着 / 没跑过 / 有结果），结果卡片在 [SelfTestCard]。
 *
 * 「未通过项的去处」在这里收口：卡片只负责画一个按钮并回报「用户点了哪一项」，
 * 具体打开哪个系统页面是这一层的事 —— 卡片里伸手去调 SystemSettings 会让它
 * 绑死在 Android 的跳转细节上（而它本该只关心排版）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenQuickConnect: () -> Unit
) {
    val context = LocalContext.current

    fun handleAction(action: SelfTestAction) {
        when (action) {
            // 权限与通知开关都在应用详情页。跳到那儿之后不能自动「再检查一次」——
            // 用户可能什么都没改就返回，那样自检结果会与屏幕上显示的对不上。
            // 回来时右上角那个按钮就在那里，一眼能看见。
            SelfTestAction.OPEN_APP_SETTINGS -> SystemSettings.openAppDetails(context)

            // 电池白名单在系统设置的另一处，应用详情页里没有这个开关
            SelfTestAction.OPEN_BATTERY_SETTINGS ->
                SystemSettings.openBatteryOptimization(context)

            SelfTestAction.OPEN_QUICK_CONNECT -> onOpenQuickConnect()
        }
    }

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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    .verticalScroll(rememberScrollState())
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                // 再跑一次时，屏幕上已有结果的那几项只会「图标变灰」——
                // 看不出正在跑。顶部一条细进度条把这件事说明白，而且不遮内容、不跳布局
                // （与第一次那个整页转圈是两个不同的态：那时没有内容可遮）。
                if (state.selfTestRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                SelfTestCard(items = state.selfTest, onAction = ::handleAction)

                // 转发链路是**主动**才能测的（会给外部渠道真发消息），所以它不在那些项里
                NotifyTestCard(state = state, onTest = { viewModel.testNotify() })
            }
        }
    }
}
