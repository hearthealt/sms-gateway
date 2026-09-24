package com.smsgateway.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/** 行内提示的语气。四档与状态色一一对应。 */
enum class NoticeType { Danger, Warning, Info, Success }

/**
 * 行内提示：一句要**留在页面上**的说明。
 *
 * 原先这类句子有五处各写各的 —— 有的是一行红字（`ServerSmsScreen` 的刷新失败）、
 * 有的是原样输出的 `⚠` 字符（扫码页）、有的只是把字染成橙色（转发渠道为空）。
 * 同一个应用里三种表现，读起来像三个不同的人随手加的，而它们的共同点是
 * 「这句话必须被看到」—— 那就该有同一个样子。
 *
 * 与 Snackbar 的分工：**这个不消失**。一闪而过的消息适合「你刚做的动作成功了」，
 * 而要照着处置的东西（刷新失败的原因、没有启用转发渠道、这张码带了口令）
 * 必须留到人处理完为止。
 *
 * @param type 语气，决定图标与配色。默认图标按语气走，要表达别的意思时用 [icon] 覆盖。
 * @param action 右侧的可选操作（通常是一个 TextButton）。
 */
@Composable
fun InlineNotice(
    text: String,
    type: NoticeType = NoticeType.Info,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    val (contentColor, backgroundColor) = noticeColors(type)
    val resolvedIcon = icon ?: defaultIcon(type)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = backgroundColor, shape = AppColor.BannerShape)
            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
        // Top 而不是 CenterVertically：文字折成两行时图标要留在第一行旁边，
        // 跟着居中会让它飘到两行中间，看起来像在指向空白处。
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = resolvedIcon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(AppSize.iconMd)
        )
        Spacer(modifier = Modifier.width(AppSpacing.xs))
        Text(
            text = text,
            style = AppTypography.bodySmall,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        action?.let {
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            it()
        }
    }
}

/**
 * 语气 → 配色。
 *
 * 返回的每一对都在 [AppColor] 里按对比度配过（正文门槛 4.5:1）——
 * 这里只是搬运，不要临时拼「红色字压在黄色底上」那种组合。
 */
@Composable
private fun noticeColors(type: NoticeType): Pair<Color, Color> = when (type) {
    NoticeType.Danger -> AppColor.Danger to AppColor.DangerBg
    NoticeType.Warning -> AppColor.Warning to AppColor.WarningBg
    NoticeType.Info -> AppColor.Info to AppColor.InfoBg
    NoticeType.Success -> AppColor.Success to AppColor.SuccessBg
}

private fun defaultIcon(type: NoticeType): ImageVector = when (type) {
    NoticeType.Danger -> Icons.Default.Block
    NoticeType.Warning -> Icons.Default.Warning
    NoticeType.Info -> Icons.Default.Info
    NoticeType.Success -> Icons.Default.CheckCircle
}
