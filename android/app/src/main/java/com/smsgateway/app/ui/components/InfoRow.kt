package com.smsgateway.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 「标签 — 值」一行。设置页的设备信息、首页的身份卡，用的是同一种东西。
 *
 * 原先有两份实现（设置页的 `StatusRow` 与首页的 `IdentityLine`），字段名、字号、
 * 颜色都是靠人肉对齐的 —— 它们的标签都是 bodyMedium / InkSecondary，值都是
 * bodySmall（首页那份）或 bodyMedium（设置页那份）。这份差异不是设计，是两次各写一遍。
 *
 * ## 值的宽度是这里唯一的难点
 *
 * 值必须能被压缩并省略。原先首页那份写成「标签、`Spacer(weight(1f))`、值、箭头」——
 * Compose 的 Row 先量**没有 weight** 的子项，再把它剩下的分给带 weight 的。
 * 于是值的固有宽度一旦超过可用空间，它就会被超量分配，把右边的箭头整个挤出屏幕；
 * 而设备标识是 24 个等宽字符，在 1.3 倍系统字体下正好会走到这一步。
 *
 * 现在的结构是「标签（不参与分配）+ 一个带 weight 的内层 Row」，值在内层里
 * 再 `weight(1f, fill = false)` —— 值最多只能用到内层的全部宽度，够短就只占自己需要的宽度，
 * 而箭头永远拿得到它的 18dp。
 *
 * @param leadingIcon 行首图标，可选。给了就会给标签留出对齐的缩进。
 * @param monospace 等宽显示。给设备标识这类要逐位核对的値用：比例字体里 0/O、1/l 分不清。
 * @param selectable 允许长按选中复制。给「要拿去别处使用」的值用（设备标识得报给管理员）。
 * @param onClick 给了整行就可点，并在行尾带一个箭头。
 * @param minHeight 行的最小高度。可点的行应传 [AppSpacing.touchTarget]。
 * @param modifier 内边距由调用方给（各处的父容器不一样：设置页在卡片里已经留了 20dp，
 *   首页那两行要自己留）。这里不内置，否则两处会叠成 32dp 或 20+12dp。
 */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    monospace: Boolean = false,
    selectable: Boolean = false,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 0.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = AppColor.InkMuted,
                modifier = Modifier.size(AppSize.iconSm)
            )
            Spacer(modifier = Modifier.width(AppSpacing.sm))
        }

        Text(text = label, style = AppTypography.bodyMedium, color = AppColor.InkSecondary)

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.sm),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val valueText: @Composable () -> Unit = {
                Text(
                    text = value,
                    style = AppTypography.bodyMedium.copy(
                        fontFamily = if (monospace) FontFamily.Monospace else null
                    ),
                    color = valueColor ?: AppColor.InkStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (selectable) SelectionContainer { valueText() } else valueText()

            if (onClick != null) {
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                ChevronIcon()
            }
        }
    }
}
