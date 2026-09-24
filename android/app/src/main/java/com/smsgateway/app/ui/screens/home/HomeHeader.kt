package com.smsgateway.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smsgateway.app.R
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 顶部渐变头部。
 *
 * 只放品牌与应用名，**不放「安全 · 稳定 · 便捷」那类标语** —— 这是内部工具，
 * 现场一天要开十次，那行字占的高度不如留给状态。操作入口（扫一扫、自检、设置）
 * 留在这里，与内容页分开，滚动时不会跟着跑。
 *
 * 三个入口按「多久用一次」从右往左排：设置最常碰、自检是排障时才用、
 * 扫一扫一台设备一辈子用一次 —— 最不常点的放最不顺手的位置。
 *
 * 扫一扫**不随注册状态显隐**。它只在未注册时有用，但一个会凭空出现/消失的图标
 * 比一个常年在那儿、偶尔才点的图标更难找；而且已注册的设备也可能要换服务器接入。
 */
@Composable
fun HomeHeader(
    onOpenQuickConnect: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(AppColor.BrandDark, AppColor.Brand))
            )
            // 开了沉浸式之后得自己让开状态栏，否则标题会被时间、电量压住
            .statusBarsPadding()
            .padding(horizontal = AppSpacing.gutter, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(AppSize.logoBlock)
                .clip(AppColor.LogoShape)
                .background(AppColor.onBrand),
            contentAlignment = Alignment.Center
        ) {
            // 与启动图标同一套意象：信封 + 信号波。
            // 白底蓝标是一个真正的 logo 块，而不是一个默认的 Material 图标 ——
            // 应用图标长什么样、界面里就是什么样，两者对得上才叫品牌。
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = AppColor.Brand,
                modifier = Modifier.size(AppSize.logoMark)
            )
        }
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        // 取 @string/app_name 而非再写一遍字面量：这个名字改过一次，
        // 当时只改了清单里的 label，标题栏留了旧名，两处不同步就是这么来的。
        Text(
            text = stringResource(R.string.app_name),
            style = AppTypography.h2,
            color = AppColor.onBrand
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenQuickConnect) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = "扫一扫", tint = AppColor.onBrand)
        }
        IconButton(onClick = onOpenSelfTest) {
            Icon(
                Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = "自检",
                tint = AppColor.onBrand
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = AppColor.onBrand)
        }
    }
}
