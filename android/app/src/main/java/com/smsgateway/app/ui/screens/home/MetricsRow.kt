package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 今日概览：一条压在 HeroCard 底部的指标行。
 *
 * ## 为什么从三张卡压成一行
 *
 * 原先三个指标各占一张卡（图标 + 标签 + 提示 + 32sp 大数字 + 箭头），竖排约 200dp ——
 * 全页最贵的地方只说了三件事，而且结构完全一样，读起来像同一件事说三遍。
 * 压成一行之后省下约 130dp，头不再那么重。
 *
 * 代价是**三个数字变成一句话**，所以取舍是：
 * - 保留那个「大于 0 就要处置」的值单独醒目显示 —— 「待上传」；
 * - 「今日短信 / 今日验证码」两个同源的数字合成一段，用「条 / 码」后缀区分；
 * - 原来那几句 hint（「全部已上传」「今天还没收到」「有短信，未提取到」）不再单独占行：
 *   0 本身就说清了「没有」，而两个数字并排时「有短信但没验证码」也看得出来。
 *
 * ## 一段话，两个入口
 *
 * 读起来得是一句话（左对齐、挨着排），所以不做「左右两半各贴一边」——
 * 那样中间会空出一大片，眼睛要跳一下才把两段连起来。
 *
 * 但两段点去的地方不同（队列页 / 服务端记录页），所以是**并列的两个 clickable**，
 * 不是「整行可点 + 内嵌一个」：嵌套 clickable 在读屏上会读成两个按钮套在一起。
 * 「>」紧跟在它指向的那一段后面，不单独飘在行尾。
 */
@Composable
fun MetricsRow(
    state: DashboardState,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit
) {
    val backlog = state.pendingCount

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 待上传 → 队列页。大于 0 才染色 —— 这是唯一一个「要人去处置」的信号，
        // 颜色留给它（原先「今日验证码为 0 但有短信」也染橙，而那是每天的常态：
        // 大多数短信本来就没有验证码，橙卡天天出现就没人当回事了）。
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenQueue)
                .padding(vertical = AppSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
        ) {
            Text(
                text = "待上传",
                style = AppTypography.bodyMedium,
                color = AppColor.InkSecondary
            )
            Text(
                text = backlog.toString(),
                style = AppTypography.h3,
                color = if (backlog > 0) AppColor.Warning else AppColor.Ink
            )
        }

        // 两段之间的点：既不属于左边也不属于右边，所以放在两个 clickable 之外
        Text(
            text = "·",
            style = AppTypography.bodyMedium,
            color = AppColor.Faint,
            modifier = Modifier.padding(horizontal = AppSpacing.sm)
        )

        // 今日 → 服务端记录页
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenServerSms)
                .padding(vertical = AppSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = todaySummary(state),
                style = AppTypography.bodyMedium,
                color = AppColor.InkSecondary
            )
            Spacer(modifier = Modifier.width(AppSpacing.xxs))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "服务端记录",
                tint = AppColor.Faint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 「今日 64 条 · 61 码」。
 *
 * 两个数字单独加重（不是整句一个颜色）：它们才是要读的东西，而左边「待上传」的数字
 * 也是加重的 —— 一行里两个数字的强调得一致，否则读起来像一个是数据、另一个是说明。
 */
@Composable
private fun todaySummary(state: DashboardState) = buildAnnotatedString {
    append("今日 ")
    withStyle(SpanStyle(color = AppColor.Ink, fontWeight = FontWeight.Medium)) {
        append(state.todaySmsCount.toString())
    }
    append(" 条 · ")
    withStyle(SpanStyle(color = AppColor.Ink, fontWeight = FontWeight.Medium)) {
        append(state.todayCodeCount.toString())
    }
    append(" 码")
}
