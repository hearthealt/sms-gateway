package com.smsgateway.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 「这一项过没过」的一行：图标 + 标题 + 说明（+ 可选的操作）。
 *
 * 自检页的六项与转发测试的每个渠道原先各写了一份：自检那份是「图标 + 两行文字」，
 * 转发那份是「图标 + 两行文字 + 右侧一个『已送达 / 失败』」—— 同一页上下两块，
 * 同样的东西两种排法。合到一处之后，第 7 项、第 8 个渠道都不会再各自长出新样式。
 *
 * @param trailing 行尾的补充信息或操作。自检用它放「去开启」，转发测试用它放渠道状态。
 */
@Composable
fun CheckResultRow(
    ok: Boolean,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 未通过的行往往带一个按钮，给足高度免得挤在一起
            .defaultMinSize(minHeight = AppSpacing.touchTarget),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
            // 图标本身没有信息：过没过由 title 与颜色表达。给了 contentDescription
            // 只会让读屏在每一项后面多念一句「图片」。
            contentDescription = null,
            tint = if (ok) AppColor.Success else AppColor.Danger,
            modifier = Modifier.size(AppSize.iconMd)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = AppTypography.bodyMedium, color = AppColor.Ink)
            detail?.let {
                Text(
                    text = it,
                    style = AppTypography.caption,
                    // 通过的那一项说明是中性信息，未通过的才染红 ——
                    // 全部染红会把「哪一项真的不对」也一起淹掉。
                    color = if (ok) AppColor.InkMuted else AppColor.Danger
                )
            }
        }
        trailing?.let {
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            it()
        }
    }
}
