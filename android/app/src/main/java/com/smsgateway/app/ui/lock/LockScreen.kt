package com.smsgateway.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography

/**
 * 锁屏。盖在整个界面之上，**不影响后台服务** —— 网关、心跳、短信接收照跑，
 * 锁的只是「看界面」这件事。
 *
 * ## 为什么是「输入框 + 按钮」而不是数字键盘
 *
 * 数字键盘手感更好，但要自己排版、自己处理删除与错误动画，代码量是这里的四五倍。
 * 这台设备一天最多解锁几次，输入框够用；省下的复杂度留给「忘记 PIN 怎么办」——
 * 那才是真会出事的地方。
 *
 * ## 忘记 PIN
 *
 * 锁屏上直接写明出路，不留一个含糊的「无法解锁」：本应用**没有找回入口**（能绕过 PIN
 * 的入口等于没装锁），只能清应用数据然后用控制台的恢复码重新注册。设备标识取自 SSAID，
 * 清数据不会变，所以后台还是同一台设备。这个说明配上「去系统设置」的直达按钮，
 * 现场不用猜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreen(
    canUseBiometric: Boolean,
    onUnlockWithPin: (String) -> Boolean,
    onUnlockWithBiometric: () -> Unit,
    biometricMessage: String?,
    onOpenAppSettings: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showForgot by remember { mutableStateOf(false) }

    fun tryUnlock() {
        if (onUnlockWithPin(pin)) {
            error = null
        } else {
            error = "PIN 不对"
            pin = ""
        }
    }

    Scaffold(containerColor = AppColor.Screen) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "云驿站已锁定", style = AppTypography.h2, color = AppColor.Ink)
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                text = "输入 PIN 解锁。网关仍在后台运行，锁的只是界面。",
                style = AppTypography.bodySmall,
                color = AppColor.InkMuted
            )

            Spacer(modifier = Modifier.height(AppSpacing.xl))

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { c -> c.isDigit() }.take(8)
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PIN") },
                isError = error != null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )

            error?.let {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(text = it, style = AppTypography.bodySmall, color = AppColor.Danger)
            }

            biometricMessage?.let {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(text = it, style = AppTypography.bodySmall, color = AppColor.InkMuted)
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            Button(
                onClick = { tryUnlock() },
                enabled = pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("解锁") }

            if (canUseBiometric) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                TextButton(onClick = onUnlockWithBiometric) { Text("用指纹解锁") }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))
            TextButton(onClick = { showForgot = true }) { Text("忘记 PIN？") }
        }
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            title = { Text("忘记 PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = "PIN 无法找回，应用里也没有绕过它的入口 —— 有的话这把锁就等于没装。",
                        style = AppTypography.bodySmall
                    )
                    Text(
                        text = "重置的办法：清除本应用的数据（设备身份取自系统，不会变），" +
                            "再用管理员在控制台签发的恢复码重新注册。",
                        style = AppTypography.bodySmall
                    )
                    Text(
                        text = "包名：${BuildConfig.APPLICATION_ID}",
                        style = AppTypography.mono(AppTypography.caption),
                        color = AppColor.InkMuted
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgot = false
                    onOpenAppSettings()
                }) { Text("去系统设置") }
            },
            dismissButton = {
                TextButton(onClick = { showForgot = false }) { Text("知道了") }
            }
        )
    }
}

/** 锁屏上的一个小进度指示，改 PIN 一类耗时操作时用得上。 */
@Composable
fun LockBusy() {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        }
    }
}
