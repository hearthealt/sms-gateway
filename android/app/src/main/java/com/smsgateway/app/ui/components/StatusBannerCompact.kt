package com.smsgateway.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.SystemSettings

/**
 * 简化的警告横幅 - 浮动提示条样式。
 *
 * 从原来的 ~100dp 压缩到 ~48dp，只保留核心信息和操作按钮。
 *
 * @param detail 操作结果一类的补充说明，null 时不占位。放在第二行而不是替换
 *   [message]：核心那句「已被管理员禁用」是这块横幅存在的理由，不能被结果顶掉。
 */
@Composable
private fun CompactWarningBanner(
    icon: ImageVector,
    message: String,
    actionLabel: String,
    backgroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    detail: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.BannerShape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    style = AppTypography.bodySmall,
                    color = contentColor
                )
                // 操作结果留在屏幕上，不用 snackbar 一闪而过：现场要拿它对着排查
                // 「为什么还是不行」，看一眼就走的东西没用
                detail?.let {
                    Text(
                        text = it,
                        style = AppTypography.caption,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                }
            }
            // 按钮取横幅自己的字色，不要落到 colorScheme.primary：
            // 那样橙色横幅上会挂一个蓝按钮，两种主题下都不搭
            TextButton(
                onClick = onClick,
                colors = ButtonDefaults.textButtonColors(contentColor = contentColor)
            ) {
                Text(actionLabel, style = AppTypography.bodySmall)
            }
        }
    }
}

/**
 * 权限缺失横幅 - 简化版。
 */
@Composable
fun PermissionBannerCompact() {
    val context = LocalContext.current
    CompactWarningBanner(
        icon = Icons.Default.Block,
        message = "缺少短信权限，无法接收短信",
        actionLabel = "去开启",
        backgroundColor = AppColor.DangerBg,
        contentColor = AppColor.Danger,
        onClick = { SystemSettings.openAppDetails(context) }
    )
}

/**
 * 禁用状态横幅 - 简化版。
 *
 * @param checkResult 「检查状态」的结论（来自 [com.smsgateway.app.DashboardState.testResult]），
 *   没点过时为 null。
 *
 *   这个按钮是禁用状态下唯一的逃生口，而它的三种结果里只有「已恢复」会带来可见变化
 *   （横幅整块消失、主页状态卡翻绿）。不把结论显示出来，「仍处于禁用状态」和
 *   「心跳发不出去」这两种情况在屏幕上是**完全一样**的 —— 都什么都没发生，
 *   现场只会以为按钮坏了然后反复点。
 */
@Composable
fun DisabledBannerCompact(onCheckStatus: () -> Unit, checkResult: String? = null) {
    CompactWarningBanner(
        icon = Icons.Default.Block,
        message = "已被管理员禁用，短信留在本地",
        actionLabel = "检查状态",
        backgroundColor = AppColor.NeutralWarnBg,
        contentColor = AppColor.NeutralWarn,
        onClick = onCheckStatus,
        detail = checkResult
    )
}

/**
 * 电池优化横幅 - 简化版。
 */
@Composable
fun BatteryBannerCompact() {
    val context = LocalContext.current
    CompactWarningBanner(
        icon = Icons.Default.BatteryAlert,
        message = "未加入电池白名单，可能被系统杀掉",
        actionLabel = "去设置",
        backgroundColor = AppColor.WarningBg,
        contentColor = AppColor.Warning,
        onClick = { SystemSettings.openBatteryOptimization(context) }
    )
}
