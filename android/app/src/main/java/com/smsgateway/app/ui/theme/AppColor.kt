package com.smsgateway.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全局颜色与形状。
 *
 * ## 为什么是 `@Composable get`
 *
 * 明暗两套值各存一份（见 [LightPalette] / [DarkPalette]），取值这一步推迟到组合期，
 * 于是调用方照旧写 `AppColor.Ink` —— 深色模式没有把 100 多处调用点改成参数传递，
 * 也没有引入第二套 API。代价是这些属性只能在 @Composable 里读，不能提前算好存进
 * 常量或 ViewModel（当前全工程没有这种用法）。
 *
 * ## 面的数量是有意的
 *
 * 面的颜色只有「页面底色」和「卡片」两种，文字只有四级灰，状态色只表达状态。
 * 这不是随手定的：原先首页自绘一套（白卡片、#F4F6FA 底）、五个子页面用 Material3
 * 默认值（不给颜色就落到淡紫灰），同一个应用里首页和设置页看起来像两个产品。
 */
object AppColor {

    /** 品牌蓝。头部渐变的两端，也用于按钮与强调。 */
    val BrandDark: Color @Composable @ReadOnlyComposable get() = palette().brandDark
    val Brand: Color @Composable @ReadOnlyComposable get() = palette().brand

    /**
     * 压在品牌色之上的前景：顶栏标题与返回箭头、顶栏操作图标、logo 块底色。
     *
     * 所有「画在品牌蓝上」的东西都取它，不要各处再写 `Color.White`：两种主题下它
     * 都是白（品牌蓝够深、不发散），但将来若给品牌色换一版更浅的，漏改哪一处，
     * 那一处就会出现白字糊在浅底上。
     */
    val onBrand: Color @Composable @ReadOnlyComposable get() = palette().onBrand

    // ---- 面：全应用只有这两种 ----
    /** 页面底色。 */
    val Screen: Color @Composable @ReadOnlyComposable get() = palette().screen
    /** 卡片。 */
    val Card: Color @Composable @ReadOnlyComposable get() = palette().card

    // ---- 文字：按重要性分四级 ----
    /** 正文/数字。 */
    val Ink: Color @Composable @ReadOnlyComposable get() = palette().ink
    /** 略强于正文的小字（如设备信息行的值）。 */
    val InkStrong: Color @Composable @ReadOnlyComposable get() = palette().inkStrong
    /** 次要：标签、说明。 */
    val InkSecondary: Color @Composable @ReadOnlyComposable get() = palette().inkSecondary
    /** 弱化：提示、占位。 */
    val InkMuted: Color @Composable @ReadOnlyComposable get() = palette().inkMuted
    /** 最弱：空状态的大图标、箭头这类纯装饰。 */
    val Faint: Color @Composable @ReadOnlyComposable get() = palette().faint
    /** 分隔线。比底色深一档即可，不做可见的边框。 */
    val Divider: Color @Composable @ReadOnlyComposable get() = palette().divider

    // ---- 状态色：只表达状态，不做装饰 ----
    val Danger: Color @Composable @ReadOnlyComposable get() = palette().danger
    val DangerBg: Color @Composable @ReadOnlyComposable get() = palette().dangerBg
    val Success: Color @Composable @ReadOnlyComposable get() = palette().success
    val SuccessBg: Color @Composable @ReadOnlyComposable get() = palette().successBg
    val Warning: Color @Composable @ReadOnlyComposable get() = palette().warning
    val WarningBg: Color @Composable @ReadOnlyComposable get() = palette().warningBg
    val Info: Color @Composable @ReadOnlyComposable get() = palette().info
    val InfoBg: Color @Composable @ReadOnlyComposable get() = palette().infoBg
    /** 被管理员禁用：与危险区分开，它不是故障而是状态。 */
    val NeutralWarn: Color @Composable @ReadOnlyComposable get() = palette().neutralWarn
    val NeutralWarnBg: Color @Composable @ReadOnlyComposable get() = palette().neutralWarnBg
    /** 无状态（未知状态、默认徽章）。 */
    val Neutral: Color @Composable @ReadOnlyComposable get() = palette().neutral
    val NeutralBg: Color @Composable @ReadOnlyComposable get() = palette().neutralBg

    // ---- 开关：未选中时的两档灰 ----
    val SwitchOffThumb: Color @Composable @ReadOnlyComposable get() = palette().switchOffThumb
    val SwitchOffTrack: Color @Composable @ReadOnlyComposable get() = palette().switchOffTrack

    // ---- 形状：三档，分别对应卡片、状态横幅、按钮 ----
    // 形状与明暗无关，不需要跟着主题走
    val CardShape = RoundedCornerShape(14.dp)
    val BannerShape = RoundedCornerShape(12.dp)
    val ButtonShape = RoundedCornerShape(12.dp)
    val BadgeShape = RoundedCornerShape(6.dp)
}

/**
 * 一套配色。
 *
 * 用 data class 而不是两个 object：明暗两套必须字段一一对应，
 * 少一个字段编译器就会报出来，不会变成「深色模式下这块忘了改」。
 */
@Immutable
data class AppPalette(
    val brandDark: Color,
    /**
     * 压在品牌色之上的前景：顶栏标题与返回箭头、顶栏上的操作图标、logo 块底色。
     *
     * 两种主题下都是白 —— 品牌蓝本身够深、不发散，不需要跟着明暗反转。
     * 所有「画在品牌蓝上」的东西都取它，不要各处再写一遍 `Color.White`：
     * 将来若给品牌色换一版更浅的（或加深色模式专用的品牌色），漏掉哪一处
     * 就会在那一处出现白字糊在浅底上。
     */
    val onBrand: Color,
    val brand: Color,
    val screen: Color,
    val card: Color,
    val ink: Color,
    val inkStrong: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val faint: Color,
    val divider: Color,
    val danger: Color,
    val dangerBg: Color,
    val success: Color,
    val successBg: Color,
    val warning: Color,
    val warningBg: Color,
    val info: Color,
    val infoBg: Color,
    val neutralWarn: Color,
    val neutralWarnBg: Color,
    val neutral: Color,
    val neutralBg: Color,
    val switchOffThumb: Color,
    val switchOffTrack: Color
)

/**
 * 浅色：品牌蓝 + 浅灰底 + 白卡片。
 *
 * 状态色用「深字 + 浅底」：底色只负责把这块圈出来，字色才是要读的那个，
 * 所以浅底要浅到不抢字的对比度。
 */
internal val LightPalette = AppPalette(
    brandDark = Color(0xFF0D47A1),
    onBrand = Color.White,
    brand = Color(0xFF1E88E5),
    screen = Color(0xFFF4F6FA),
    card = Color.White,
    ink = Color(0xFF263238),
    inkStrong = Color(0xFF37474F),
    inkSecondary = Color(0xFF546E7A),
    inkMuted = Color(0xFF90A4AE),
    faint = Color(0xFFBDBDBD),
    divider = Color(0xFFECEFF3),
    danger = Color(0xFFC62828),
    dangerBg = Color(0xFFFFEBEE),
    success = Color(0xFF2E7D32),
    successBg = Color(0xFFE8F5E9),
    warning = Color(0xFFE65100),
    warningBg = Color(0xFFFFF3E0),
    info = Color(0xFF1565C0),
    infoBg = Color(0xFFE3F2FD),
    neutralWarn = Color(0xFF5E35B1),
    neutralWarnBg = Color(0xFFEDE7F6),
    neutral = Color(0xFF616161),
    neutralBg = Color(0xFFF5F5F5),
    switchOffThumb = Color(0xFF9E9E9E),
    switchOffTrack = Color(0xFFE0E0E0)
)

/**
 * 深色：面比纯黑浅一档（#000 上的白字会发糊，且 OLED 上边界糊成一片），
 * 状态色整体提亮 —— 浅色那套的红/绿直接搬到深底上对比度不够，读起来是「暗红」。
 * 品牌蓝两端保持不变：它本来就是深蓝，放在深色里不需要改，改了反而不像同一个应用。
 */
internal val DarkPalette = AppPalette(
    brandDark = Color(0xFF0D47A1),
    onBrand = Color.White,
    brand = Color(0xFF1E88E5),
    screen = Color(0xFF0E1116),
    card = Color(0xFF1F252D),
    ink = Color(0xFFE8EAED),
    inkStrong = Color(0xFFECEFF1),
    inkSecondary = Color(0xFFB0BEC5),
    inkMuted = Color(0xFF8A9AA5),
    faint = Color(0xFF5A6672),
    divider = Color(0xFF262C34),
    danger = Color(0xFFEF5350),
    dangerBg = Color(0xFF3A1F1F),
    success = Color(0xFF81C784),
    successBg = Color(0xFF1B2E1F),
    warning = Color(0xFFFFB74D),
    warningBg = Color(0xFF3A2A16),
    info = Color(0xFF64B5F6),
    infoBg = Color(0xFF17293D),
    neutralWarn = Color(0xFFB39DDB),
    neutralWarnBg = Color(0xFF2A2440),
    neutral = Color(0xFFB0BEC5),
    neutralBg = Color(0xFF262C34),
    switchOffThumb = Color(0xFF78909C),
    switchOffTrack = Color(0xFF37474F)
)

@Composable
@ReadOnlyComposable
internal fun palette(): AppPalette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
