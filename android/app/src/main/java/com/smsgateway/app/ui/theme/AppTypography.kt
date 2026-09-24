package com.smsgateway.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体系统。
 *
 * 统一全应用的字号、字重、行高，避免散落的字面量。
 * 分三类：标题（h2/h3）、正文（bodyLarge/bodyMedium/bodySmall）、辅助（caption/label/hint）。
 */
object AppTypography {

    // ==================== 标题 ====================

    /** 顶栏标题。对应 AppTopBar 和 HomeHeader 的应用名。 */
    val h2 = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp
    )

    /**
     * 卡片/区块标题。对应 SettingsCard 标题等。
     *
     * 16sp 而不是 15sp：15sp 与 [bodyLarge] 同号，卡片标题于是压不住它下面那行正文 ——
     * 两者只差一个字重，在 1.3 倍系统字体下更是完全分不出来。抬高一档之后，
     * 「标题 → 正文」这条层级才由**字号**承担，字重只做补充。
     */
    val h3 = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    )

    // ==================== 正文 ====================

    /** 强调正文。用于发送方名称、主要数据行。 */
    val bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp
    )

    /**
     * 常规正文。用于大段说明、短信内容。
     *
     * Normal 而不是 Medium：原先正文比 [bodyLarge] 还粗，短信正文于是比它上面那行
     * 发送方更重 —— 层级整个是反的。正文就该是最轻的一级，强调留给字号与颜色。
     */
    val bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp
    )

    /** 次级正文。用于简短说明、输入框提示。 */
    val bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp
    )

    // ==================== 辅助 ====================

    /** 辅助文字。用于时间戳、状态说明、提示文案。 */
    val caption = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    )

    /** 标签文字。用于指标名称、状态标签、按钮文字。 */
    val label = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp
    )

    /**
     * 超小提示文字。用于指标块的提示行（11sp）。
     *
     * 行高 16sp 而不是 13sp：中文没有 x-height 那种「上下留白靠字形本身」的余量，
     * 13sp 行高在 11sp 字上只留 2sp，折成两行时上下行会挤在一起。
     */
    val hint = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    )

    // ==================== 特殊 ====================

    // 曾经还有一个 metricLarge（32sp，「指标块的大数字」）。删掉是因为它全项目没有
    // 一个调用点：主页的三张指标卡早被压成了 HeroCard 底下的一条三格统计，
    // 那里的数字用的是 h3。32sp 留在这里只会诱使下一次把某个数字放大到那一档。

    /**
     * 等宽变体：设备 ID、服务器地址、验证码这类要逐位核对的内容。
     *
     * 做成函数而不是一个 TextStyle 常量 —— 单独一个只带 fontFamily 的样式没法定字号，
     * 用它的地方总得再叠一层 `.copy(fontFamily = ...)`，等于把「等宽」这件事写散在各处。
     */
    fun mono(base: TextStyle) = base.copy(fontFamily = FontFamily.Monospace)
}
