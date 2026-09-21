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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

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
        containerColor = AppColor.Screen,
        content = content
    )
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
            .height(56.dp)
            .padding(start = 4.dp, end = 8.dp),
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

        Text(
            text = title,
            style = AppTypography.h2,
            color = AppColor.onBrand
        )

        subtitle?.let {
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Text(
                text = it,
                style = AppTypography.caption,
                // 压在品牌蓝上，用同色降透明度而不是换一种灰：换灰会在蓝底上发脏
                color = AppColor.onBrand.copy(alpha = 0.75f)
            )
        }

        // 撑开剩余空间，让 actions 贴右 —— 与有没有 subtitle 无关
        Spacer(modifier = Modifier.weight(1f))

        actions()
    }
}

/**
 * 内容卡片。白面、无描边、无投影 —— 层级靠底色与留白拉开，不靠阴影。
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = AppSpacing.cardPadding,
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
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            content = content
        )
    }
}
