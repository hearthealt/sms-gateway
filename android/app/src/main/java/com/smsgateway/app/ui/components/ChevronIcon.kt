package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize

/**
 * 「这一行/这一格可以点进去」的那个箭头。
 *
 * 抽出来只有两个理由，都不是洁癖：
 * - 原先它在 IdentityRow 与 MetricsRow 里各写了一份，尺寸（18dp）、颜色、
 *   `contentDescription = null` 三件事都靠人肉保持一致。改一处漏一处，
 *   结果就是同一屏里两个箭头长得不一样。
 * - `contentDescription` 必须是 null。它是**装饰**：箭头本身没有信息，
 *   说「这个可点」的是行/格的语义；给它一个 contentDescription 只会让 TalkBack
 *   在真正的内容后面多读一句。
 *
 * 用 AutoMirrored 那个变体：RTL 语言下它会自动镜像，而写死 `KeyboardArrowRight`
 * 在阿拉伯语环境里正好指向反方向。
 *
 * @param tint 默认是 [AppColor.Faint]（装饰用的最弱一档，非文字门槛 3:1）。
 *   压在有色底上时由调用方换成与底色配对的色值。
 */
@Composable
fun ChevronIcon(modifier: Modifier = Modifier, tint: Color = AppColor.Faint) {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(AppSize.iconSm)
    )
}
