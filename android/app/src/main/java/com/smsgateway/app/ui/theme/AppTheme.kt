package com.smsgateway.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 应用主题。
 *
 * 除了把明暗两套配色装进去，这里还有一件事必须做：**把 Material 各组件的默认取色
 * 接到品牌色上**。Material3 默认那套是紫灰调，本应用自己画的卡片早就覆盖掉了，
 * 但输入框、对话框、圆形进度条、Snackbar、描边按钮这些没人给颜色的组件会落到
 * 默认值上 —— 于是「自己的部分」是蓝白灰，「借来的部分」是淡紫，不像一个应用。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 以 Material 的两套基线为底再覆盖：这样没点到名的字段仍有一份完整的、
    // 与明暗匹配的默认值，而不是各自缺一块。
    val base = if (dark) darkColorScheme() else lightColorScheme()

    MaterialTheme(
        colorScheme = base.withBrand(if (dark) DarkPalette else LightPalette),
        content = content
    )
}

/** 把品牌配色盖到基线配色上。明暗两套走同一条路径，只有输入不同。 */
private fun ColorScheme.withBrand(p: AppPalette) = copy(
    primary = p.brand,
    onPrimary = p.onBrand,
    primaryContainer = p.infoBg,
    onPrimaryContainer = p.info,
    secondary = p.inkSecondary,
    onSecondary = p.onBrand,
    background = p.screen,
    onBackground = p.ink,
    surface = p.card,
    onSurface = p.ink,
    surfaceVariant = p.neutralBg,
    onSurfaceVariant = p.inkSecondary,
    outline = p.inkMuted,
    outlineVariant = p.divider,
    error = p.danger,
    onError = p.onBrand,
    errorContainer = p.dangerBg,
    onErrorContainer = p.danger,
    inverseSurface = p.ink,
    inverseOnSurface = p.card
)
