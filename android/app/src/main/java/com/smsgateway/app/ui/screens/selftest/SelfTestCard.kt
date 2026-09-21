package com.smsgateway.app.ui.screens.selftest

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.SelfTestItem
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 自检结果：**一张卡 + 细分隔线**，不再是每项一张色块卡。
 *
 * 原先六张绿卡竖排视觉很重，而且整块铺色时反而看不出「哪一项不对劲」——
 * 全部同色，眼睛没有落点。现在状态收在两处：卡片右上角一句总结（几项通过 / 几项未通过），
 * 以及每一项自己的图标与说明文字 —— 有问题的才染红，正常的保持中性。
 */
@Composable
fun SelfTestCard(items: List<SelfTestItem>) {
    val failed = items.count { !it.ok }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = AppSpacing.md,
                        end = AppSpacing.md,
                        top = AppSpacing.sm,
                        bottom = AppSpacing.sm
                    ),
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (item.ok) AppColor.Success else AppColor.Danger,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Column {
                        Text(item.label, style = AppTypography.bodyMedium, color = AppColor.Ink)
                        Text(
                            text = item.detail,
                            style = AppTypography.caption,
                            color = if (item.ok) AppColor.InkMuted else AppColor.Danger
                        )
                    }
                }
            }
        }
    }
}
