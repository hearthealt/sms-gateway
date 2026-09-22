package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.model.DeviceTrend
import com.smsgateway.app.ui.components.MiniBar
import com.smsgateway.app.ui.components.MiniBarChart
import com.smsgateway.app.ui.components.MiniBarChartPlaceholder
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.util.ServerTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * 主页的两张小图：近 7 天、今日逐小时。
 *
 * ## 它们回答的问题
 *
 * 「最近收到」那三行说的是**此刻**还在收；这两张图说的是**趋势** ——
 * 今天比昨天少一半、从下午三点起就没再响过，这类「正在变坏」是逐条看列表看不出来的。
 * 一台设备坏掉多半不是戛然而止，而是先少一半。
 *
 * ## 为什么是柱状图，以及为什么这么素
 *
 * 单序列的量随时间变化 → 柱状图；只有一种颜色，所以不需要图例（标题已经画的是什么），
 * 也不需要网格线（只标峰值一处，其余靠点按读数）。零基线是硬要求：
 * 截断的柱状图会把 2% 的波动画成翻倍。
 * 取舍都在 [MiniBarChart] 的注释里，这里只负责把数据摆成它要的形状。
 *
 * @param trend 为 null（还没取到）时摆**占位骨架**，而不是整块不渲染 ——
 *   原先后者会让页面进来时那儿是空的、几百毫秒后两张图凭空冒出来并把内容往下弹一截，
 *   看到的人以为「刚才页面是坏的」。
 * @param attempted 是否已经问过一次（成功或失败都算）。只有它和 `trend == null` 同时成立
 *   才说「暂时读不到」—— 否则一次网络失败之后骨架会永远写着「读取中」。
 */
@Composable
fun TrendCard(trend: DeviceTrend?, attempted: Boolean) {
    // 取到了但一条都没有：整块不渲染。空图（七根全 0 的柱子）比没有图更让人怀疑设备坏了。
    if (trend != null && trend.daily.isEmpty() && trend.hourly.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.md
            )
        ) {
            if (trend != null) {
                MiniBarChart(
                    bars = dailyBars(trend),
                    title = "近 7 天",
                    unit = "条",
                    barColor = AppColor.Brand,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                MiniBarChartPlaceholder(
                    title = "近 7 天",
                    barCount = DAYS_IN_TREND,
                    hint = placeholderHint(attempted),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))
            HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)
            Spacer(modifier = Modifier.height(AppSpacing.md))

            if (trend != null) {
                MiniBarChart(
                    bars = hourlyBars(trend),
                    title = "今日分布",
                    unit = "条",
                    barColor = AppColor.Brand,
                    modifier = Modifier.fillMaxWidth(),
                    // 24 根柱，每根只有十来个 dp，标签必须隔几个标一次；
                    // 0/6/12/18 四点足够定位，也不至于糊成一片
                    labelEvery = 6,
                    // 默认落在「现在这一小时」：这张图大多数时候就是看「今天到现在怎么样」
                    defaultSelected = LocalTime.now().hour
                )
            } else {
                MiniBarChartPlaceholder(
                    title = "今日分布",
                    barCount = HOURS_IN_DAY,
                    hint = placeholderHint(attempted),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 占位时那句说明。
 *
 * 「读取中」与「暂时读不到」必须分开：趋势请求失败是**静默**的（只记日志、保留旧值），
 * 一直说「读取中」就等于骗人 —— 用户会一直等一个不会来的结果。
 */
private fun placeholderHint(attempted: Boolean): String =
    if (attempted) "暂时读不到" else "读取中"

/** 与 ViewModel 的 TREND_DAYS 同值。占位骨架的柱数必须和真图一致，否则数据回来时柱宽会变。 */
private const val DAYS_IN_TREND = 7

private const val HOURS_IN_DAY = 24

/**
 * 近 N 天。日期缩成 `9/21`，最后一天写成「今天」——
 * 一周里最常被问的是「今天怎么样」，写日期还要先换算一次。
 */
private fun dailyBars(trend: DeviceTrend): List<MiniBar> {
    val today = LocalDate.now()
    return trend.daily.map { stat ->
        val date = ServerTime.parseDay(stat.day)
        val label = when {
            date == null -> stat.day
            date == today -> "今天"
            else -> "${date.monthValue}/${date.dayOfMonth}"
        }
        MiniBar(label = label, value = stat.value)
    }
}

/** 今日逐小时。标签带「点」，因为读数行会显示成「13 点 · 3 条」。 */
private fun hourlyBars(trend: DeviceTrend): List<MiniBar> =
    trend.hourly.map { stat -> MiniBar(label = "${stat.hour} 点", value = stat.value) }
