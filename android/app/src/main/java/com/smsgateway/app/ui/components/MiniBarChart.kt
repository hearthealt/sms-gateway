package com.smsgateway.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/** 一根柱子：x 轴标签 + 值。标签可以为空（那一格就不标）。 */
data class MiniBar(val label: String, val value: Long)

/** 相邻柱之间留出的底色缝。靠留白分隔，不描边。 */
private val BAR_GAP = 2.dp

/** 轴标签那一行的高度，够放下一行 hint 字号。 */
private val LABEL_HEIGHT = 16.dp

/**
 * 极简柱状图（单序列）。
 *
 * 只做一件事：让「今天比昨天少了一半」这种形状一眼看得出来。
 * 因此刻意没有的东西和刻意有的东西一样重要：
 *
 * **没有**图例（单序列，标题已经说了画的是什么）、**没有**网格线、
 * **没有**每根柱子上都标数字、**没有**纵轴刻度。
 *
 * **有**的几条，都是为了让图能读而不是好看：
 * - **零基线**：柱子从 0 起算，绝不截断 —— 截断的柱状图会把 2% 的差异画成翻倍。
 * - **只标一处**：峰值那根直接标数值，其余靠点按读数（下方那行）。把数字铺满每根柱子
 *   是「没人读」的典型，而只标峰值刚好回答「最多的一天是多少」。
 * - **2dp 缝**：相邻柱之间留出底色，靠留白分隔而不是描边（描边会加进不属于数据的墨）。
 * - **柱顶 4dp 圆角、柱底方角**：数据端圆、基线端方，视觉上钉在基线上。
 * - **选中态**：点一根柱子，上方读数换成它（默认最后一根 = 今天 / 当前小时）。
 *   这是触屏上的 tooltip —— 但数值**不依赖它**：峰值已直接标注，
 *   不点也能读出个大概，这与「tooltip 不能是读数的唯一途径」是同一条。
 *
 * @param labelEvery 每几根标一个 x 标签。24 小时的图必须隔几个标，
 *   不然标签会挤成一团（挤在一起的标签比没有标签更难读）。
 */
@Composable
fun MiniBarChart(
    bars: List<MiniBar>,
    title: String,
    /** 读数后缀，如「条」。 */
    unit: String,
    barColor: Color,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 52.dp,
    labelEvery: Int = 1,
    /** 默认选中哪一根：趋势图给最后一根（今天），小时图给当前小时。 */
    defaultSelected: Int = bars.lastIndex
) {
    if (bars.isEmpty()) return

    val maxValue = bars.maxOf { it.value }.coerceAtLeast(1L)
    val peakIndex = bars.indexOfFirst { it.value == maxValue }
    var selected by remember(bars, defaultSelected) {
        mutableIntStateOf(defaultSelected.coerceIn(0, bars.lastIndex))
    }
    val current = bars[selected]

    Column(modifier = modifier) {
        // 标题与读数一行：读数就是 tooltip，常驻在这里而不是浮在图上 ——
        // 触屏没有 hover，浮层要么挡图要么一闪而过
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = AppTypography.hint, color = AppColor.InkMuted)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${current.label} · ${current.value} $unit",
                style = AppTypography.caption,
                color = AppColor.InkSecondary
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEachIndexed { index, bar ->
                // 点按区域是**整格**（含柱子上方那段空白），不是只有柱身那几像素 ——
                // 否则 24 小时图里要精准点到一根 8dp 宽的柱子
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = index },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (bar.value > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(plotHeight * (bar.value.toFloat() / maxValue))
                                // 未选中的压暗一档：选中的那根要看得出来是哪根，
                                // 但同一序列仍是同一个颜色（不靠色相区分）
                                .background(
                                    color = if (index == selected) barColor else barColor.copy(alpha = 0.45f),
                                    shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                                )
                        )
                    } else {
                        // 值为 0 也留一条 2dp 的「底座」：那一格点得到，
                        // 而且「这几天都是 0」与「这几天压根没有数据」看得出区别
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(
                                    color = AppColor.Divider,
                                    shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                                )
                        )
                    }
                }
            }
        }

        // 零基线：发丝线、一档灰，不用虚线（虚线读起来像阈值或预测）
        HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

        Spacer(modifier = Modifier.height(AppSpacing.xxs))

        // 轴标签用 Canvas 自己摆，不用「每根柱一个格子 + 居中」：
        // 24 根柱时每格只有十来个 dp，「12 点」会被裁成「1」—— 真机上就是这么翻车的
        // （Compose 的 Text 在自己格子里放不下就裁，不会溢出到邻格）。
        // Canvas 里按与柱子**完全相同**的宽度公式摆位置，既压得准也不会被裁。
        // 样式与量尺都要在**组合期**取好：AppTypography / AppColor 的取值是 @Composable 的，
        // 而下面 Canvas 的绘制 lambda 不是组合作用域，里面读它们编译不过
        val labelStyle = AppTypography.hint.copy(color = AppColor.InkMuted)
        val measurer = rememberTextMeasurer()
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(LABEL_HEIGHT)
        ) {
            val gapPx = BAR_GAP.toPx()
            val barWidth = (size.width - gapPx * (bars.size - 1)) / bars.size
            bars.forEachIndexed { index, bar ->
                if (index % labelEvery != 0) return@forEachIndexed
                val layout = measurer.measure(text = bar.label, style = labelStyle)
                // 单根一组的（近 7 天）让标签居中压在这根柱上；
                // 一组多根的贴左，标签左边缘正好是这一组的起点 —— 小时轴上即整点边界
                val offsetX = index * (barWidth + gapPx) +
                    if (labelEvery == 1) (barWidth - layout.size.width) / 2f else 0f
                drawText(layout, topLeft = Offset(offsetX, 0f))
            }
        }

        // 峰值直接标注：不点也能看出「最多的一天是多少」。
        // 峰值恰好是被选中那根时就不重复说了 —— 上方读数已经在说同一件事。
        if (peakIndex != selected && bars[peakIndex].value > 0) {
            Spacer(modifier = Modifier.height(AppSpacing.xxs))
            Text(
                text = "最多 ${bars[peakIndex].label} · ${bars[peakIndex].value} $unit",
                style = AppTypography.hint,
                color = AppColor.InkMuted
            )
        }
    }
}
