package com.smsgateway.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 应用主题。
 *
 * 这里有三件事必须做，缺一件就会出现「自己的部分」和「借来的部分」不像一个应用：
 *
 * 1. **把 Material 各组件的默认取色接到品牌色上**。Material3 默认那套是紫灰调，
 *    本应用自己画的卡片早就覆盖掉了，但输入框、对话框、圆形进度条、Snackbar、
 *    描边按钮这些没人给颜色的组件会落到默认值上，于是界面里混进一块淡紫。
 * 2. **把字体接上去**（[Typography]）。不给的话，对话框标题会用自己的 24sp —— 比页面
 *    标题的 20sp 还大一级，看起来像弹窗在喊话；按钮标签会落到 14sp Medium，
 *    而全应用其余的字都在 AppTypography 里。字号不该有第二套来源。
 * 3. **把形状接上去**（[Shapes]）。默认的对话框圆角是 28dp，比卡片（14dp）大一倍，
 *    像是从别的应用里剪贴过来的。这里压到 20dp，与卡片是同一族的比例。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 以 Material 的两套基线为底再覆盖：这样没点到名的字段仍有一份完整的、
    // 与明暗匹配的默认值，而不是各自缺一块。
    val base = if (dark) darkColorScheme() else lightColorScheme()

    MaterialTheme(
        colorScheme = base.withBrand(if (dark) DarkPalette else LightPalette),
        typography = AppMaterialTypography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * 只做映射，不引入新字号 —— 新字号一律加在 [AppTypography] 里。
 *
 * 映射的是**借来的组件**用得到的那几档：对话框标题/正文、输入框标签、按钮标签、
 * Snackbar 文字。其余字段保持 Material 默认值（它们对应不到本应用画的东西）。
 */
private val AppMaterialTypography = Typography().run {
    copy(
        // 对话框标题：原先 24sp，比页面顶栏的 20sp 还大
        headlineSmall = AppTypography.h2,
        // 对话框正文
        bodyMedium = AppTypography.bodyMedium,
        // 输入框里的字与标签
        bodyLarge = AppTypography.bodyLarge,
        labelLarge = AppTypography.bodyLarge,
        bodySmall = AppTypography.bodySmall,
        labelMedium = AppTypography.label,
        labelSmall = AppTypography.caption,
        // Snackbar
        titleMedium = AppTypography.bodyMedium
    )
}

/**
 * 五档圆角，与 [AppColor.CardShape] / [AppColor.BannerShape] / [AppColor.ButtonShape]
 * 是同一族的比例：卡片 14、横幅与按钮 12，对话框取 20（它比卡片大一圈，但不该是
 * 默认的 28 —— 那是「比整屏还圆」的那一档）。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/** 把品牌配色盖到基线配色上。明暗两套走同一条路径，只有输入不同。 */
private fun ColorScheme.withBrand(p: AppPalette) = copy(
    primary = p.primary,
    onPrimary = p.onPrimary,
    primaryContainer = p.infoBg,
    onPrimaryContainer = p.info,
    secondary = p.inkSecondary,
    // onPrimary 而不是 onBrand：深色的 secondary 是浅灰，压白字只有 2:1
    onSecondary = p.onPrimary,
    background = p.screen,
    onBackground = p.ink,
    surface = p.card,
    onSurface = p.ink,
    surfaceVariant = p.neutralBg,
    onSurfaceVariant = p.inkSecondary,
    outline = p.inkMuted,
    outlineVariant = p.divider,
    error = p.danger,
    onError = p.onPrimary,
    errorContainer = p.dangerBg,
    onErrorContainer = p.danger,
    inverseSurface = p.ink,
    inverseOnSurface = p.card
)
