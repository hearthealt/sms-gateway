package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.components.ChevronIcon
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 今日概览：一条压在 [HeroCard] 底部的三格统计。
 *
 * ## 为什么是三格
 *
 * 原先这条是「左右各半」：左边「待上传 N ›」，右边「今日 N 条 · M 码 ›」。
 * 两个问题在 1.3 倍系统字体下都会露出来 ——
 * 一是右边那半是**一整句**（三个词、两个数字），折行之后两半的高度不再一致；
 * 二是两边的数字样式不一样（左边 h3 加粗、右边只是加重），读起来像一个是数据、
 * 另一个是说明。
 *
 * 改成三格「标签在上、数字在下」之后：每格的宽度固定为三分之一，标签最多三个汉字，
 * 数字统一 [AppTypography.h3] —— 三格各自独立，任何一格折行都不牵连另外两格，
 * 而三个数字的第一眼权重自然是一样的（它们本来就是同一层级的三个量）。
 *
 * ## 为什么只有两格可点
 *
 * 「待上传」通往队列页、「今日短信」通往服务端记录页 —— 这两格各有去处。
 * 「今日验证码」没有独立页面（它就在服务端记录页里，是那些记录的**子集**），
 * 所以它不可点：摆一个点了没反应的东西比不摆更糟。
 * 可点的两格各带一个箭头 —— 没有箭头就看不出可点。
 */
@Composable
fun MetricsRow(
    state: DashboardState,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
    ) {
        // 第一格：本地积压。大于 0 才染色 —— 这是唯一一个「要人去处置」的信号，
        // 颜色留给它（原先「今日验证码为 0 但有短信」也染橙，而那是每天的常态：
        // 大多数短信本来就没有验证码，橙字天天出现就没人当回事了）。
        MetricCell(
            label = "待上传",
            value = state.pendingCount.toString(),
            valueColor = if (state.pendingCount > 0) AppColor.Warning else AppColor.Ink,
            onClick = onOpenQueue
        )

        // 「今日短信」这一格只在**已注册**时可点。
        // 未注册时服务端记录页什么都读不到，而那个接口要鉴权 —— 点进去只会拿到
        // 一条「服务端已不认这台设备」的误报（见 DashboardViewModel.loadServerSmsNow）。
        // 与其让人点进去看一个假的故障，不如这格先不可点。
        MetricCell(
            label = "今日短信",
            value = state.todaySmsCount.toString(),
            onClick = onOpenServerSms.takeIf { state.isRegistered }
        )

        MetricCell(
            label = "今日验证码",
            value = state.todayCodeCount.toString()
        )
    }
}

/**
 * 一格统计。
 *
 * @param onClick 给了才可点，并自动带一个箭头；不给就是纯展示。
 */
@Composable
private fun RowScope.MetricCell(
    label: String,
    value: String,
    valueColor: Color = AppColor.Ink,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .weight(1f)
            // 最小高度统一：只有带箭头的那两格比纯展示的那一格高（箭头 18dp），
            // 不给下限的话三格高度会不一样，数字的基线就对不齐。
            .heightIn(min = AppSpacing.touchTarget)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = AppSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
    ) {
        Text(
            text = label,
            style = AppTypography.label,
            color = AppColor.InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = AppTypography.h3,
                color = valueColor,
                maxLines = 1
            )
            if (onClick != null) {
                Spacer(modifier = Modifier.width(AppSpacing.xxs))
                ChevronIcon(tint = AppColor.Faint)
            }
        }
    }
}
