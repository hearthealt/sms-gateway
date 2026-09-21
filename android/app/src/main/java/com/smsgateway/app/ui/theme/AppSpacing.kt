package com.smsgateway.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 间距系统。
 *
 * 统一全应用的间距值，基于 4dp 网格系统。
 * 提供基础单位和语义化间距，避免散落的字面量。
 */
object AppSpacing {

    // ==================== 基础单位（4dp 网格）====================

    val xxs = 4.dp   // 超小间距
    val xs = 8.dp    // 极小间距
    val sm = 12.dp   // 小间距
    val md = 16.dp   // 中等间距
    val lg = 20.dp   // 大间距
    val xl = 24.dp   // 超大间距
    val xxl = 32.dp  // 极大间距
    val xxxl = 48.dp // 特大间距（如自检项分隔线要缩进到文字起点）

    // ==================== 语义化间距 ====================

    /** 卡片内边距。对应 AppCard 内部的 padding。 */
    val cardPadding: Dp = lg  // 20dp

    /** 卡片之间的间距。对应主页、设置页卡片列的 verticalArrangement.spacedBy。 */
    val cardGap: Dp = md  // 16dp

    /** 页面内大区块之间的间距（如主页顶部状态区和指标区之间）。 */
    val sectionGap: Dp = xl  // 24dp

    /** 页面左右留白。对应各页面内容的 horizontal padding。 */
    val gutter: Dp = md  // 16dp

    /** 列表项内部元素之间的间距（如 Icon 和 Text 之间）。 */
    val itemGap: Dp = xs  // 8dp

    /** 内容行之间的间距（如多行文字之间）。 */
    val contentGap: Dp = sm  // 12dp
}
