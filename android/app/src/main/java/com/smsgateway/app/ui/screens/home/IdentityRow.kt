package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 设备身份。刻意放在最下面、且不加卡片 —— 这两项配好之后就不会再变，
 * 只需要「需要时找得到」，不需要「每次打开都看见」。点进去是设置页。
 */
@Composable
fun IdentityRow(state: DashboardState, onOpenSettings: () -> Unit) {
    // 装进卡片而不是直接摊在灰底上：这两行是「一块内容」而不是两行飘着的字，
    // 有边界之后它才和上面几块读起来是一套东西。
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            IdentityLine(
                icon = Icons.Default.Smartphone,
                label = "设备",
                value = state.deviceId.abbreviateId(),
                onClick = onOpenSettings
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 44.dp, end = 14.dp),
                thickness = 1.dp,
                color = AppColor.Divider
            )
            IdentityLine(
                icon = Icons.Default.Phone,
                label = "手机号",
                value = state.phone.ifBlank { "未设置" },
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun IdentityLine(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColor.InkMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Text(text = label, style = AppTypography.bodyMedium, color = AppColor.InkSecondary)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = AppTypography.bodySmall, color = AppColor.InkStrong)
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AppColor.Faint,
            modifier = Modifier.size(18.dp)
        )
    }
}

private fun String.abbreviateId(): String =
    if (isBlank()) "未设置" else if (length <= 12) this else "${take(8)}…"
