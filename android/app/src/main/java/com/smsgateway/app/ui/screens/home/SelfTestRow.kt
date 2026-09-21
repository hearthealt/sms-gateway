package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.formatRelative
import com.smsgateway.app.ui.utils.rememberNow

/**
 * 主页的「自检」入口，带上次结论。
 *
 * 原先只有顶栏那个勾选图标 —— 现场反馈是「藏在图标里」，没人知道那是自检。
 * 现在挪到主页单独一行、写上「自检」两个字，并把**上次的结论**一起显示
 * （结论落盘在 DevicePrefs，见 setLastSelfTest）。
 *
 * 之所以要显示上次结论而不是只放一个按钮：自检要跑六项、其中两项是网络请求，
 * 一处小毛病就要等好几秒。「六项全部通过 · 2 小时前」让现场不点进去就知道还过不过，
 * 不过的时候那一行会变红 —— 这才是摆这一行的意义。
 *
 * 顶栏那个图标同时撤掉了：同一页摆两个入口只会让人以为它们不一样。
 */
@Composable
fun SelfTestRow(state: DashboardState, onOpenSelfTest: () -> Unit) {
    // 相对时间要自己走，否则「2 小时前」会一直停在打开 App 那一刻
    val now = rememberNow()

    val neverRun = state.lastSelfTest.isBlank()
    // 摘要里带「未通过」就是有项没过。用文字判断而不是另存一个布尔：
    // 反正这句话本来就要存下来显示，多一个字段就多一处可能与它不一致的状态。
    val failed = state.lastSelfTest.contains("未通过")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSelfTest)
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                tint = if (failed) AppColor.Danger else AppColor.InkMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
            ) {
                Text(
                    text = "自检",
                    style = AppTypography.bodyMedium,
                    color = AppColor.Ink
                )
                Text(
                    text = when {
                        neverRun -> "点一下检查权限、电池、服务器连通等六项"
                        failed -> state.lastSelfTest
                        else -> "${state.lastSelfTest} · ${formatRelative(state.lastSelfTestAt, now)}"
                    },
                    style = AppTypography.caption,
                    color = if (failed) AppColor.Danger else AppColor.InkMuted
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AppColor.Faint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
