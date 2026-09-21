package com.smsgateway.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.util.AppLock

/**
 * 设置页的「应用锁」：PIN + 指纹。
 *
 * ## 开/关/改三个动作，合成两个步骤
 *
 * 关锁要**先验旧 PIN**，开锁只是设新 PIN，改 PIN 则是「关掉再打开」——
 * 所以这个开关本身就是状态机：往上拨走「设新 PIN」，往下拨走「验当前 PIN」。
 * 不需要第三个弹窗，也就不会出现「关锁时也在校验新 PIN」这种串味。
 *
 * ## 关锁必须先验 PIN
 *
 * 不是多此一举：手机摆在工位上，路过的人如果能直接把锁关掉，这把锁就没意义了。
 */
@Composable
fun AppLockCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var step by remember { mutableStateOf<LockStep?>(null) }

    SettingsCard(title = "应用锁") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "锁定界面", style = AppTypography.bodyMedium, color = AppColor.Ink)
                Text(
                    text = if (enabled) {
                        "打开应用需要 PIN 或指纹；网关在后台照常运行"
                    } else {
                        "这台设备摆在外面，界面上有可以拿去登录的验证码"
                    },
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { wantOn ->
                    step = if (wantOn) {
                        LockStep.SetNew
                    } else {
                        // 关锁也要先验：能直接关掉的锁等于没装
                        LockStep.VerifyCurrent
                    }
                }
            )
        }

        if (enabled) {
            Text(
                text = "改 PIN：先把开关关掉（要输一次当前 PIN），再打开设新的。",
                style = AppTypography.caption,
                color = AppColor.InkMuted
            )
        }
    }

    when (step) {
        LockStep.SetNew -> PinDialog(
            title = "设置 PIN",
            confirmLabel = "启用",
            onDismiss = { step = null },
            onSubmit = { pin ->
                AppLock.setPin(context, pin)
                enabled = true
                step = null
            }
        )

        LockStep.VerifyCurrent -> PinDialog(
            title = "输入当前 PIN",
            confirmLabel = "确认",
            verifyAgainst = context,
            onDismiss = { step = null },
            onSubmit = {
                // 这一步只可能是「关锁」：拨到关的位置才走 VerifyCurrent，
                // 而 PinDialog 已经替我们验过旧 PIN 了，这里不再看 pin 的值
                AppLock.disable(context)
                enabled = false
                step = null
            }
        )

        null -> Unit
    }
}

/** 应用锁卡里的三个步骤。 */
private enum class LockStep { SetNew, VerifyCurrent }

/**
 * 一个只问 PIN 的弹窗。
 *
 * [verifyAgainst] 非空时先校验旧 PIN（错了不关弹窗、直接提示），为空则表示这是「设新 PIN」。
 */
@Composable
private fun PinDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    verifyAgainst: android.content.Context? = null
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val context = verifyAgainst
        if (context != null) {
            if (AppLock.verify(context, pin)) onSubmit(pin) else error = "PIN 不对"
            return
        }
        when {
            pin.length < 4 -> error = "至少 4 位数字"
            pin != confirm -> error = "两次输入不一致"
            else -> onSubmit(pin)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                // 「设新 PIN」才需要确认框；验旧 PIN 时问两遍纯属添乱
                if (verifyAgainst == null) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(8); error = null },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("再输一次") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                error?.let {
                    Text(text = it, style = AppTypography.bodySmall, color = AppColor.Danger)
                }
                if (verifyAgainst == null) {
                    Text(
                        text = "忘掉之后就找不回来了（没有找回入口 —— 有的话这把锁等于没装）。" +
                            "真忘了只能清应用数据，再用控制台的恢复码重新注册。",
                        style = AppTypography.caption,
                        color = AppColor.InkMuted
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
