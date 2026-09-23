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
            // 完整展示，不截断。原先取前 8 位再补省略号，而设备标识的形态是
            // 「android- + 16 位十六进制」，前 8 位恰好就是 `android-` ——
            // 每台设备都长这样，截完等于什么都没显示。
            // 这一行也放得下：24 字符等宽在 12sp 下约 173dp，行内留给值的有 270dp 上下。
            IdentityLine(
                icon = Icons.Default.Smartphone,
                label = "设备",
                value = state.deviceId.ifBlank { "未设置" },
                mono = true,
                onClick = onOpenSettings
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 44.dp, end = 14.dp),
                thickness = 1.dp,
                color = AppColor.Divider
            )
            // 空态不能只写「未设置」：号码读不到时短信照样传得上去，服务端却会跳过写
            // 按号码的验证码缓存 —— 于是「传上去了但调用方等不到码」，而界面上一切正常。
            // 这里是现场唯一能不靠查库就发现这件事的地方。
            IdentityLine(
                icon = Icons.Default.Phone,
                label = "手机号",
                value = state.phone.ifBlank { "未设置（收不到验证码）" },
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun IdentityLine(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    /** 等宽显示。给设备标识这类要逐位核对的値用：比例字体里 0/O、1/l 分不清。 */
    mono: Boolean = false
) {
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
        Text(
            text = value,
            style = if (mono) AppTypography.mono(AppTypography.bodySmall) else AppTypography.bodySmall,
            color = AppColor.InkStrong
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = AppColor.Faint,
            modifier = Modifier.size(18.dp)
        )
    }
}
