package com.smsgateway.app.ui.screens.selftest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.SelfTestAction
import com.smsgateway.app.SelfTestItem
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.components.CheckResultRow
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 自检结果：**一张卡 + 细分隔线**，不再是每项一张色块卡。
 *
 * 原先六张绿卡竖排视觉很重，而且整块铺色时反而看不出「哪一项不对劲」——
 * 全部同色，眼睛没有落点。现在状态收在两处：卡片右上角一句总结（几项通过 / 几项未通过），
 * 以及每一项自己的图标与说明文字 —— 有问题的才染红，正常的保持中性。
 *
 * 内边距与 [NotifyTestCard] 用的是同一套（标题 16dp、条目 16dp），
 * 原先两张卡一个 16/12、一个 20，标题左边缘差 4dp，上下两张卡看起来没对齐。
 *
 * @param onAction 未通过项右侧那个「去开启」。**没有它的话，自检只是一份诊断书**：
 *   用户知道哪一项没过，但下一步要自己去找那个开关（有的在应用详情页、有的在电池设置里）。
 */
@Composable
fun SelfTestCard(items: List<SelfTestItem>, onAction: (SelfTestAction) -> Unit) {
    val failed = items.count { !it.ok }

    AppCard(
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("自检结果", style = AppTypography.h3, color = AppColor.Ink)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (failed == 0) "${items.size} 项全部通过" else "$failed 项未通过",
                style = AppTypography.bodySmall,
                color = if (failed == 0) AppColor.Success else AppColor.Danger
            )
        }

        HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = AppSpacing.xxxl),
                    thickness = 1.dp,
                    color = AppColor.Divider
                )
            }
            CheckResultRow(
                ok = item.ok,
                title = item.label,
                detail = item.detail,
                modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                // 过了的项不给按钮：没有任何要做的动作，摆一个按钮只会让人以为还得点它
                trailing = item.action
                    ?.takeIf { !item.ok }
                    ?.let { action ->
                        {
                            AppTextButton(
                                onClick = { onAction(action) },
                                contentPadding = PaddingValues(
                                    horizontal = AppSpacing.sm,
                                    vertical = AppSpacing.xxs
                                )
                            ) {
                                Text(
                                    text = action.label,
                                    style = AppTypography.bodySmall,
                                    color = AppColor.Info
                                )
                            }
                        }
                    }
            )
        }
    }
}
