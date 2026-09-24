package com.smsgateway.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.ui.AppOutlinedButton
import com.smsgateway.app.ui.components.InfoRow
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 「关于」卡片：版本、包名、开源许可。
 *
 * 包名要写出来而不是省略：这个 APK 的包名（com.yunyi.smshub）和源码 namespace
 * （com.smsgateway.app）不是同一个东西，现场排查「装的是哪一版」时，
 * 后台看到的、`adb shell pm list packages` 看到的都是包名 —— 只写版本号对不上。
 */
@Composable
fun AboutCard() {
    var showLicenses by remember { mutableStateOf(false) }

    SettingsCard(title = "关于") {
        InfoRow(
            label = "应用版本",
            value = BuildConfig.VERSION_NAME,
            modifier = Modifier.padding(vertical = AppSpacing.xxs)
        )
        InfoRow(
            label = "包名",
            value = BuildConfig.APPLICATION_ID,
            monospace = true,
            selectable = true,
            modifier = Modifier.padding(vertical = AppSpacing.xxs)
        )

        AppOutlinedButton(
            onClick = { showLicenses = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("开源许可") }
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

/**
 * 开源许可。
 *
 * 这里列的是**实际打进 APK 的库**（见 app/build.gradle.kts），不是一份通用模板 ——
 * 列一堆用不到的库，等于没有这份清单。全部为 Apache-2.0。
 */
@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开源许可") },
        text = {
            Column(
                modifier = Modifier
                    // 给个上限再滚动：清单比屏幕长，不给上限会顶到一个几乎全屏的对话框
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Text(
                    text = "本应用使用了以下开源库，均为 Apache License 2.0。",
                    style = AppTypography.bodySmall,
                    color = AppColor.InkSecondary
                )
                HorizontalDivider(color = AppColor.Divider)

                LICENSES.forEach { (name, note) ->
                    Column(modifier = Modifier.padding(vertical = AppSpacing.xxs)) {
                        Text(name, style = AppTypography.bodyMedium, color = AppColor.Ink)
                        Text(
                            text = note,
                            style = AppTypography.caption,
                            color = AppColor.InkMuted,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = AppColor.Divider)
                Text(
                    text = "Apache License 2.0 · http://www.apache.org/licenses/LICENSE-2.0",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 库名 → 用途。用途这一列是给现场排障用的：知道哪个库管哪件事。 */
private val LICENSES = listOf(
    "AndroidX Core / Lifecycle / Activity" to "基础框架、生命周期、Compose 宿主",
    "Jetpack Compose (UI / Foundation / Material 3)" to "全部界面与设计系统",
    "AndroidX Room" to "本地短信队列数据库",
    "AndroidX WorkManager" to "短信后台上传与失败重试",
    "Kotlin Coroutines" to "异步与并发",
    "Retrofit / OkHttp / Gson" to "与服务端通信、报文解析",
    "ZXing Core" to "二维码的生成与识别",
    "AndroidX CameraX" to "扫码页的相机取景与对焦"
)
