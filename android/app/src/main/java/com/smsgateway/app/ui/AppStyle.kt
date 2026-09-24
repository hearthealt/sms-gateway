package com.smsgateway.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 内容区的最大宽度。
 *
 * 手机上一辈子用不到（最宽的直板机也就 480dp 上下），但平板和横屏上会：不设上限时
 * 卡片被拉满整个屏宽，一行说明文字能拉到 200 多字符 —— 那种行长眼睛要来回扫，
 * 是「读不下去」最典型的成因。超过这个宽度就把内容居中。
 */
private val CONTENT_MAX_WIDTH = 640.dp

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
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = { AppTopBar(title = title, onBack = onBack, subtitle = subtitle, actions = actions) },
        snackbarHost = snackbarHost,
        containerColor = AppColor.Screen
    ) { padding ->
        // 宽屏上限。放在这里而不是各页各写一遍：每一个子页面都要有，漏一个
        // 那页就会在平板上被拉满，而「哪页漏了」在手机上是看不出来的。
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = CONTENT_MAX_WIDTH)
                    .fillMaxHeight()
            ) {
                content(padding)
            }
        }
    }
}

@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    /**
     * 标题右侧的小字，如「共 64 条」。
     *
     * 单独一个参数而不是拼进 [title]：标题是 20sp 粗体，把「共 64 条」拼进去
     * 它也跟着变成 20sp 粗体 —— 那是标题的字重，不是一个计数该有的样子。
     */
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(AppColor.BrandDark, AppColor.Brand)))
            // 开了沉浸式之后要自己让开状态栏，否则标题会被时间、电量压住
            .statusBarsPadding()
            // 下限而不是写死高度：1.3 倍系统字体下标题那一行自己会长高，
            // 写死 56dp 的后果是文字被裁掉半截。
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 8.dp, top = AppSpacing.xxs, bottom = AppSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = AppColor.onBrand
                )
            }
        } else {
            Spacer(modifier = Modifier.width(12.dp))
        }

        // 标题 + 副标题合成一个**占满剩余宽度**的组，组内再按自然宽度排、放不下就省略。
        //
        // 这里踩过一次坑，值得写下来：上一版给标题和副标题各加了
        // `weight(1f, fill = false)`，又留着 actions 前面那个 `Spacer(weight(1f))` ——
        // 三个带权重的子项会把剩余宽度**均分**（每个 1/3），于是那个 Spacer 只拿到
        // 三分之一，右侧的刷新/手动输入按钮就停在了离右边缘三分之一的地方，
        // 而标题还被压到只剩三分之一的宽度，长一点就提前省略。
        //
        // 正确的结构是「一个占满剩余宽度的组 + 组内自然流」：
        // 外层这个 Row 带 weight(1f)（fill 默认为 true），所以它拿到 actions 之外的全部宽度；
        // Row 量不带宽度的子项时会依次扣掉已用宽度，所以长标题会挤压副标题（而不是
        // 把 actions 挤出屏幕），副标题放不下时自己省略。
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = AppTypography.h2,
                color = AppColor.onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            subtitle?.let {
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = it,
                    style = AppTypography.caption,
                    // 实色而不是降透明度：压在品牌蓝上降透明度是**降低**对比度，
                    // 而这一行本来就只是个计数，很容易掉到读不清。
                    color = AppColor.onBrand,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 不再需要「撑开剩余空间」的 Spacer：上面那个组已经把剩余空间全占了，
        // actions 自然贴右。
        actions()
    }
}

/**
 * 内容卡片。白面、无描边、无投影 —— 层级靠底色与留白拉开，不靠阴影。
 *
 * [contentPadding] 与 [verticalArrangement] 可调是为了让列表行也能用它：
 * 列表行要 16dp 内边距、行间距 8dp，与设置页那张 20dp/8dp 的卡片不是同一档。
 * 但**外壳**必须是同一个 —— 圆角、底色、0 投影这三样一旦各写各的，
 * 同一个列表里就会混进两种深浅的白。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.cardPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(AppSpacing.itemGap),
    /** 底色。默认是卡片白；失败态那种整块染色的行可以覆盖它。 */
    containerColor: Color = AppColor.Card,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        // 一律 0 投影：层级靠底色与留白拉开（见 AppStyle 的说明）。
        // 曾经有一条「失败的卡片额外加 1dp 阴影」，那在同一个列表里是显眼的异类 ——
        // 失败的卡片本来就是红底，再叠一层阴影只是让它看起来比其他卡片厚一点。
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

// ==================== 按钮 ====================
//
// 三个薄壳，只为把两件事钉死：**形状**与**字**。
//
// 不这么做的话，Material3 的 Button 会用默认的胶囊形（`ButtonDefaults.shape` 是
// 50% 圆角）和 14sp 的字 —— 于是同一个界面里，卡片是 14dp 圆角、按钮是胶囊，
// 而按钮的字又不在 AppTypography 这套字号里。Material3 没有提供全局改按钮形状的口子
// （shape 取自静态 token，不走 MaterialTheme.shapes），所以只能在这里包一层。
//
// 想要的话，调用方仍可自己传 Text 的 style 覆盖字号（队列行那两个按钮就这么做）。

@Composable
fun AppButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppColor.ButtonShape,
        contentPadding = contentPadding
    ) {
        CompositionLocalProvider(LocalTextStyle provides AppTypography.bodyLarge) { content() }
    }
}

@Composable
fun AppOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppColor.ButtonShape,
        contentPadding = contentPadding
    ) {
        CompositionLocalProvider(LocalTextStyle provides AppTypography.bodyLarge) { content() }
    }
}

@Composable
fun AppTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = AppColor.ButtonShape,
        contentPadding = contentPadding
    ) {
        CompositionLocalProvider(LocalTextStyle provides AppTypography.bodyLarge) { content() }
    }
}
