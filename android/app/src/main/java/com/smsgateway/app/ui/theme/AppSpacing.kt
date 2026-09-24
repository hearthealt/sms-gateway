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

    /**
     * 可点的行/块的最小高度。
     *
     * 44dp 是「能用」，48dp 是 Material 的下限也是拇指舒服的那一档 ——
     * 这台手机常年挂在工位上，点它的人往往是一只手、一眼扫，小目标等于点不中。
     */
    val touchTarget: Dp = 48.dp

    // 曾经还有一个 contentGap（12dp，「多行文字之间」）。删掉是因为它全项目没有
    // 一个调用点，而 12dp 这个值本身已经在用 `sm` 表达 —— 留着两个名字指同一个数
    // 只会让下一个人纠结该写哪个。语义化的名字只有在它真的收紧了某个约定时才有价值
    // （cardPadding / gutter / touchTarget 都是这样）；只是换个说法的，等于没定义。
}

/**
 * 图标与进度圈的尺寸。
 *
 * 原先这些值是散写的：图标有 16/18/20/22/24/28dp，进度圈有 16/18/28/32/36dp。
 * 「次要图标」在一个文件里是 18、在另一个文件里是 20，看起来就是没对齐。
 * 收成几档之后，剩下的问题只有一个：「这个图标该用哪一档」。
 */
object AppSize {

    /** 16dp：压在 caption 里、紧跟文字的小图标。 */
    val iconXs = 16.dp

    /** 18dp：行尾箭头、按钮内图标、标签-值行左端的图标。 */
    val iconSm = 18.dp

    /** 20dp：与正文同行、需要看得清形状的图标（横幅、自检项）。 */
    val iconMd = 20.dp

    /** 22dp：卡片内的主图标（连接结论）。 */
    val iconLg = 22.dp

    /** 28dp：状态卡的主角图标。 */
    val iconXl = 28.dp

    /** 按钮里那个转圈：与按钮内的图标同尺寸。 */
    val spinnerInline = 16.dp

    /** 卡片里的转圈（二维码生成中、整页列表的首次加载）。 */
    val spinnerCard = 28.dp

    /** 整页等待的转圈。 */
    val spinnerPage = 36.dp

    /** 空状态的大图标与它的圆形底座。 */
    val emptyIcon = 64.dp
    val emptyBadge = 120.dp

    /**
     * 品牌 logo 块与其中的图标。
     *
     * 首页头部与锁屏两处都用它 —— 那两块必须长得一模一样：锁屏是第一眼看到的画面，
     * 它和主页头部对不上，人会觉得刚才看到的是另一个应用。原先两处各写了
     * （38/24 与 56/28），尺寸还不一样。
     */
    val logoBlock = 38.dp
    val logoMark = 24.dp
}
