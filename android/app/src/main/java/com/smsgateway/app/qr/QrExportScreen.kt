package com.smsgateway.app.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.smsgateway.app.DashboardState
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.AppScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导出配置二维码：把本机当前的服务器配置交给另一台设备。
 *
 * <p>**这个页面只做导出，不做导入。** 导入（扫码 / 粘贴）已经由「扫一扫」完整覆盖，
 * 而且那条路更完整：它扫完直接做完 保存地址 → 测试连接 → 注册设备，失败原因留在
 * 页面上；这里原先那半套只写到地址为止，用户还得自己回设置页点一次注册。
 * 两套导入逻辑并存的结果是同一个动作有两个入口、行为还不一样 —— 留一套。
 *
 * <p>导出这半套没有替代品，所以留在这里：它用于「手上已有一台配好的手机，
 * 再添一台」的场景，而「扫一扫」的码来自管理后台，两者来源不同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrExportScreen(
    state: DashboardState,
    onBack: () -> Unit
) {
    // 导出内容跟随当前配置：服务器地址 + 接入口令（有的话），不含设备名与设备身份。
    // 口令必须带上 —— 服务端启用准入校验时，少了它的码扫到另一台手机注册会被拒，
    // 而这张码的用途正是「让另一台设备接进这台服务器」。见 QrConfigCodec.encode。
    val payload = remember(state.serverUrl, state.enrollToken) {
        QrConfigCodec.encode(state.serverUrl, state.enrollToken)
    }

    // 生成放到 IO 线程，不要用 remember { } 在组合期算。
    //
    // QrEncoder.encode 会构一个 720×720 的 ZXing 矩阵，再逐像素写一张约 2MB 的 Bitmap；
    // 放在组合期就是在主线程上做这件事，进这个页面会掉帧，低端机更明显。
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(payload) {
        qrBitmap = withContext(Dispatchers.Default) { QrEncoder.encode(payload) }
    }

    AppScreen(title = "导出配置", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 不再给这张卡加标题：整页只有它一张卡，页面顶栏已经写着「导出配置」，
            // 再重复一句只是多一行字。
            AppCard {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    // 生成挪到 IO 线程后这里会先有一小段空档，用一个同尺寸的占位撑住，
                    // 否则卡片高度会跳一下
                    val bitmap = qrBitmap
                    if (bitmap == null) {
                        Box(
                            modifier = Modifier.size(240.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                    } else {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "配置二维码",
                            modifier = Modifier.size(240.dp),
                            // 二维码放大后必须关掉插值，否则边缘被模糊化会扫不动
                            filterQuality = FilterQuality.None
                        )
                    }
                }
                Text(
                    text = "在另一台设备上点顶部的「扫一扫」对准即可。",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
                // 口令是这个应用里唯一会被二维码带出去、且能在服务端"起作用"的凭证，
                // 所以拿着这张码的人要清楚自己在给出什么。
                if (state.enrollToken.isNotBlank()) {
                    Text(
                        text = "注意：这张码包含服务器的接入口令，拿到它的人都能把一台设备接入本服务器。",
                        style = AppTypography.mono(AppTypography.caption),
                        color = AppColor.Danger
                    )
                }
                SelectionContainer {
                    Text(
                        text = payload,
                        style = AppTypography.caption,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
