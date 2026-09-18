package com.smsgateway.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全局视觉常量。
 *
 * 抽出来是因为原先两套体系并存：首页是自绘的一套（白卡片、#F4F6FA 底、蓝渐变头），
 * 而五个子页面用的是 Material3 默认值 —— `Card()` 不给颜色就落到淡紫灰的
 * surfaceVariant，`TopAppBar` 不给颜色就是一条纯色条。于是同一个应用里，
 * 首页和设置页看起来像两个产品，而这种不一致恰恰是最扎眼的「没打磨」。
 *
 * 这里的值是**现有首页已经在用的那一套**，只是从散落的字面量收拢到一处：
 * 面的颜色只有「页面底色」和「卡片」两种，文字只有三级灰，状态色只表达状态。
 */
object AppColor {

    /** 品牌蓝。头部渐变的两端，也用于按钮与强调。 */
    val BrandDark = Color(0xFF0D47A1)
    val Brand = Color(0xFF1E88E5)

    // ---- 面：全应用只有这两种 ----
    /** 页面底色。所有页面统一，子页面不再用纯白。 */
    val Screen = Color(0xFFF4F6FA)
    /** 卡片。所有卡片统一为白，不再出现 Material 默认的淡紫灰。 */
    val Card = Color.White

    // ---- 文字：三级就够，不再出现第四种灰 ----
    /** 正文/数字。 */
    val Ink = Color(0xFF263238)
    /** 次要：标签、说明。 */
    val InkSecondary = Color(0xFF546E7A)
    /** 弱化：提示、占位。 */
    val InkMuted = Color(0xFF90A4AE)
    /** 分隔线。比底色深一档即可，不做可见的边框。 */
    val Divider = Color(0xFFECEFF3)

    // ---- 状态色：只表达状态，不做装饰 ----
    val Danger = Color(0xFFC62828)
    val DangerBg = Color(0xFFFFEBEE)
    val Success = Color(0xFF2E7D32)
    val SuccessBg = Color(0xFFE8F5E9)
    val Warning = Color(0xFFE65100)
    val WarningBg = Color(0xFFFFF3E0)
    val Info = Color(0xFF1565C0)
    val InfoBg = Color(0xFFE3F2FD)
    /** 被管理员禁用：与危险区分开，它不是故障而是状态。 */
    val NeutralWarn = Color(0xFF5E35B1)
    val NeutralWarnBg = Color(0xFFEDE7F6)

    // ---- 形状：三档，分别对应卡片、状态横幅、按钮 ----
    val CardShape = RoundedCornerShape(14.dp)
    val BannerShape = RoundedCornerShape(12.dp)
    val ButtonShape = RoundedCornerShape(12.dp)

    /** 卡片内的默认留白。 */
    val CardPadding: Dp = 20.dp
    /** 页面左右留白。 */
    val Gutter: Dp = 16.dp
}

/**
 * 子页面外壳：渐变顶栏 + 统一底色 + 统一留白。
 *
 * 顶栏刻意与首页头部用同一个渐变、同一个字号字重，而不是 M3 的 TopAppBar ——
 * 从首页点进设置时，那条蓝渐变是连续的，看得出还是同一个应用。
 * 返回键与右侧操作图标都留在同一条线上，各页不再各写一遍。
 */
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = { AppTopBar(title = title, onBack = onBack, actions = actions) },
        snackbarHost = snackbarHost,
        containerColor = AppColor.Screen,
        content = content
    )
}

@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AppColor.BrandDark, AppColor.Brand)))
            // 开了沉浸式之后要自己让开状态栏，否则标题会被时间、电量压住
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White
                )
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }

        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )

        actions()
    }
}

/**
 * 内容卡片。白面、无描边、无投影 —— 层级靠底色与留白拉开，不靠阴影。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = AppColor.CardPadding,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}
