package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.components.InfoRow
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing

/**
 * 设备身份。刻意放在最下面、且不加卡片标题 —— 这两项配好之后就不会再变，
 * 只需要「需要时找得到」，不需要「每次打开都看见」。点进去是设置页。
 *
 * 两行都是 [InfoRow]（与设置页的设备信息是同一套东西），差别只在首页这两行可点、
 * 带图标 —— 那是「首页的身份卡是一个入口」这个设计决定的，不是两种排版。
 */
@Composable
fun IdentityRow(state: DashboardState, onOpenSettings: () -> Unit) {
    // 装进卡片而不是直接摊在灰底上：这两行是「一块内容」而不是两行飘着的字，
    // 有边界之后它才和上面几块读起来是一套东西。
    AppCard(
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // 完整展示，不截断。原先取前 8 位再补省略号，而设备标识的形态是
        // 「android- + 16 位十六进制」，前 8 位恰好就是 `android-` ——
        // 每台设备都长这样，截完等于什么都没显示。
        // 放不下时由 InfoRow 负责省略，而不是在这里预先截断。
        InfoRow(
            label = "设备",
            value = state.deviceId.ifBlank { "未设置" },
            leadingIcon = Icons.Default.Smartphone,
            monospace = true,
            onClick = onOpenSettings,
            // 可点的行给足 48dp：这台手机常挂在工位上，点它的人多半是一只手、一眼扫。
            minHeight = AppSpacing.touchTarget,
            modifier = Modifier.padding(horizontal = AppSpacing.xs)
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = AppSpacing.sm),
            thickness = 1.dp,
            color = AppColor.Divider
        )

        // 空态不能只写「未设置」：号码读不到时短信照样传得上去，服务端却会跳过写
        // 按号码的验证码缓存 —— 于是「传上去了但调用方等不到码」，而界面上一切正常。
        // 这里是现场唯一能不靠查库就发现这件事的地方。
        //
        // 文案不再说「收不到验证码」：那是把后果说重了。短信照收照传，
        // 缺的只是「按号码等码」那条路（见 DevicePhone.isSingleSim 与
        // SmsReceiver.resolveSmsPhone）。
        InfoRow(
            label = "手机号",
            value = state.phone.ifBlank { "未设置（按号码等码的调用方会超时）" },
            leadingIcon = Icons.Default.Phone,
            onClick = onOpenSettings,
            minHeight = AppSpacing.touchTarget,
            modifier = Modifier.padding(horizontal = AppSpacing.xs)
        )
    }
}
