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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextStyle
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

/**
 * 轴标签那一行的高度。**不写死**，按轴标签的字号实际量出来。
 *
 * 原先固定 16dp。系统字体调到 1.3 倍之后，11sp 的标签实际要 20dp 才放得下 ——
 * 写死的高度会把下面几个字符裁掉（Canvas 画文字不会自己撑开容器），
 * 而这个视图的高度又是数据回来前后必须一致的东西（见 MiniBarChartPlaceholder），
 * 所以两边都要用同一个量出来的值。
 */
@Composable
internal fun rememberAxisLabelHeight(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) {
        // 量一个最宽的轴标签（「00 点」），而不是量空串 —— 空串的高度是 0
        val px = measurer.measure(text = "00 点", style = style).size.height
        with(density) { px.toDp() }
    }
}

/**
 * 柱状图的占位骨架。
 *
 * **它存在的理由**：趋势数据没回来之前，主页那块原先**整块不渲染** —— 于是进来时
 * 那儿是空的，几百毫秒后两张图凭空冒出来，整个页面往下弹一截。用户看到的不是
 * 「正在加载」，而是「页面刚才是坏的，现在好了」。
 *
 * 复刻 [MiniBarChart] 的**几何尺寸**（标题行 / 绘图区 / 零基线 / 轴标签行），
 * 所以数据回来时高度不跳。柱子一律是等高的浅灰块 + 「读取中」字样：
 * **刻意不等高错落** —— 那看起来就是一组真实数据，会被人当真读。
 *
 * @param barCount 柱子根数，要与真图一致（近 7 天 7 根、今日 24 根），否则宽度对不上，
 *                 数据回来时柱宽会变。
 */
@Composable
fun MiniBarChartPlaceholder(
    title: String,
    barCount: Int,
    modifier: Modifier = Modifier,
    /** 占位时那句说明。加载中与读不到要说不同的话，见调用方。 */
    hint: String,
    plotHeight: Dp = 52.dp
) {
    val labelHeight = rememberAxisLabelHeight(chartTitleStyle)
    val peakLineHeight = rememberPeakLineHeight()

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = chartTitleStyle, color = AppColor.Ink)
            Spacer(modifier = Modifier.weight(1f))
            Text(text = hint, style = AppTypography.caption, color = AppColor.InkMuted)
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight),
            horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
            verticalAlignment = Alignment.Bottom
        ) {
            repeat(barCount) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(plotHeight * 0.45f)
                            .background(
                                color = AppColor.Divider,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            )
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

        // 轴标签那一行也占住：真图的高度包含它，不占的话数据回来仍会往下弹。
        Spacer(modifier = Modifier.height(labelHeight))

        // 峰值标注那一行同理 —— 真图里它**始终**占一行（见 MiniBarChart 的说明），
        // 骨架不占的话，数据回来的那一刻整页会往上跳一截。
        Spacer(modifier = Modifier.height(AppSpacing.xxs))
        Spacer(modifier = Modifier.height(peakLineHeight))
    }
}

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
 * ## 无障碍
 *
 * 24 根柱子原先各自是一个**没有标签、没有说明**的 clickable，TalkBack 会一口气读出
 * 24 个「按钮」—— 既听不出画的是什么，也走不出去。现在整块绘图区是**一个**节点：
 * 读出结论（最多的是哪天、多少），再给一个「下一根柱子」的自定义动作让人逐根听。
 * 触摸行为不变（点哪根选哪根），`clearAndSetSemantics` 只影响读屏那一路。
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

    // 峰值索引要按**真实**最大值找；柱子高度的分母才需要防 0。
    //
    // 这两件事不能合成一个值 —— 早先图省事写的是
    //     val maxValue = bars.maxOf { it.value }.coerceAtLeast(1L)
    //     val peakIndex = bars.indexOfFirst { it.value == maxValue }
    // 于是全 0 的时候（新设备、或近 7 天一条短信都没有）分母被抬成了 1，而没有任何一根
    // 的值是 1 —— indexOfFirst 返回 -1，下面 bars[peakIndex] 直接 IndexOutOfBoundsException，
    // 主页一进来就闪退（真机上就是这么崩的，而且天天崩、躲不掉）。
    val peakValue = bars.maxOf { it.value }
    val scale = peakValue.coerceAtLeast(1L)
    val peakIndex = bars.indexOfFirst { it.value == peakValue }
    var selected by remember(bars, defaultSelected) {
        mutableIntStateOf(defaultSelected.coerceIn(0, bars.lastIndex))
    }
    val current = bars[selected]
    val labelHeight = rememberAxisLabelHeight(chartTitleStyle)
    val peakLineHeight = rememberPeakLineHeight()

    Column(modifier = modifier) {
        // 标题与读数一行：读数就是 tooltip，常驻在这里而不是浮在图上 ——
        // 触屏没有 hover，浮层要么挡图要么一闪而过。
        //
        // 标题用 [chartTitleStyle]（正文级），读数用 caption：原先标题是 hint(11sp)、
        // 读数是 caption(12sp)，**读数比标题还大** —— 于是一眼看到的是那根柱子的值，
        // 而这张图叫什么得凑近看，层级整个是反的。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = chartTitleStyle, color = AppColor.Ink)
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
                .height(plotHeight)
                // 整块合成一个节点：24 个无标签的 clickable 对读屏是不可用的
                // （它只会连读 24 次「按钮」，既没有内容也没有出路）。
                .clearAndSetSemantics {
                    contentDescription = "${title}：最多 ${bars[peakIndex].label}，" +
                        "${bars[peakIndex].value} $unit；当前 ${current.label}，${current.value} $unit"
                    customActions = listOf(
                        CustomAccessibilityAction("下一根柱子") {
                            selected = (selected + 1) % bars.size
                            true
                        }
                    )
                },
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
                                .height(plotHeight * (bar.value.toFloat() / scale))
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
                .height(labelHeight)
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
        //
        // 这一行**始终占位**，即使当前选中的正好是峰值（那时它内容为空）。
        // 原先它是条件渲染的：点中峰值那根柱子，这一行消失、上面几块整体往上跳 ——
        // 手指正停在屏幕上，页面却动了，看起来像点坏了什么。
        // 空字符串的高度是 0，所以这里用一个不换行空格占住那一行。
        Spacer(modifier = Modifier.height(AppSpacing.xxs))
        Text(
            text = if (peakIndex != selected && bars[peakIndex].value > 0) {
                "最多 ${bars[peakIndex].label} · ${bars[peakIndex].value} $unit"
            } else {
                " "
            },
            style = AppTypography.hint,
            color = AppColor.InkMuted,
            maxLines = 1,
            // 高度钉成与占位骨架同一个量出来的值。写法上有点绕（这个 Text 自己也能量），
            // 但这样两边的高度是**同一个数**，而不是「两个恰好相等的数」——
            // 后者在有人改了这里的 style 之后就会悄悄错开，而错开的表现是
            // 数据回来的那一刻整页往上跳一下。
            modifier = Modifier.height(peakLineHeight)
        )
    }
}

/**
 * 图表的标题样式。用 bodyMedium（正文级）而不是 caption/hint：
 * 它是一张图的**名字**，必须比图里的读数大，否则一眼看到的是数据而不是「这是什么数据」。
 */
private val chartTitleStyle = AppTypography.bodyMedium

/**
 * 峰值标注那一行的高度。
 *
 * 同样按字号**实际量**，不用 `AppTypography.hint.lineHeight.value.dp` 那种写法：
 * lineHeight 的单位是 sp，把它的数值当 dp 用在 1.3 倍字体下会少算约 5dp，
 * 而那 5dp 就是数据回来时整页往上跳的高度。
 *
 * 单独拎出来是给占位骨架用的：骨架必须把这一行也占住，否则数据回来的那一刻整页会跳。
 */
@Composable
private fun rememberPeakLineHeight(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = AppTypography.hint
    return remember(style, density) {
        // 量一句有代表性的文案：单行文本的高度只取决于字体，与内容无关
        val px = measurer.measure(text = "最多 9/21 · 12 条", style = style).size.height
        with(density) { px.toDp() }
    }
}
