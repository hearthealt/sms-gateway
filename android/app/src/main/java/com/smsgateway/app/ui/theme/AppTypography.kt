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

    /** 卡片/区块标题。对应 SettingsCard 标题等。 */
    val h3 = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 22.sp
    )

    // ==================== 正文 ====================

    /** 强调正文。用于发送方名称、主要数据行。 */
    val bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp
    )

    /** 常规正文。用于大段说明、短信内容。 */
    val bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
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

    /** 超小提示文字。用于指标块的提示行（11sp）。 */
    val hint = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 13.sp
    )

    // ==================== 特殊 ====================

    /** 指标块的大数字（32sp）。 */
    val metricLarge = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 38.sp
    )

    /**
     * 等宽变体：设备 ID、服务器地址、验证码这类要逐位核对的内容。
     *
     * 做成函数而不是一个 TextStyle 常量 —— 单独一个只带 fontFamily 的样式没法定字号，
     * 用它的地方总得再叠一层 `.copy(fontFamily = ...)`，等于把「等宽」这件事写散在各处。
     */
    fun mono(base: TextStyle) = base.copy(fontFamily = FontFamily.Monospace)
}
