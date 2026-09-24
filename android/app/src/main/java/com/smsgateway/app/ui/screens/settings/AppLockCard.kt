package com.smsgateway.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.smsgateway.app.ui.AppTextButton
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
 *
 * ## 这一块没有第二个标题
 *
 * 卡片标题已经是「应用锁」，里面那行原先又叫「锁定界面」—— 同一个东西两个名字，
 * 读起来像它下面还藏着另一样东西。现在那行直接说**当前是什么状态**，
 * 一句话回答「现在开着吗、开着会怎样」，比一个名词标签有用。
 */
@Composable
fun AppLockCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AppLock.isEnabled(context)) }
    var step by remember { mutableStateOf<LockStep?>(null) }

    SettingsCard(title = "应用锁") {
        Row(
            // 整行合成一个读屏节点：与主页那个开关是同一条理由（见 HeroCard）——
            // 分开读会先念一句说明、再单独念「开关，已开启」，听不出两者是一回事。
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "已开启：打开应用要 PIN 或指纹" else "已关闭：界面上的验证码谁都能看",
                    style = AppTypography.bodyMedium,
                    color = AppColor.Ink
                )
                Text(
                    text = if (enabled) {
                        "网关在后台照常运行，锁的只是界面"
                    } else {
                        "这台设备摆在外面，最近任务里也留着验证码的缩略图"
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
                },
                // 与主页那个开关用同一组颜色。原先这里不传 colors，落到 Material3 的
                // 默认值上 —— 同一个应用里两个开关的未选中态是两种灰。
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColor.Success,
                    checkedTrackColor = AppColor.Success.copy(alpha = 0.5f),
                    uncheckedThumbColor = AppColor.SwitchOffThumb,
                    uncheckedTrackColor = AppColor.SwitchOffTrack
                )
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

/** 应用锁卡里的两个步骤。 */
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
    var pinError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    fun submit() {
        val context = verifyAgainst
        if (context != null) {
            if (AppLock.verify(context, pin)) onSubmit(pin) else pinError = "PIN 不对"
            return
        }
        pinError = if (pin.length < 4) "至少 4 位数字" else null
        confirmError = if (pinError == null && pin != confirm) "两次输入不一致" else null
        if (pinError == null && confirmError == null) onSubmit(pin)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                // 错误走 supportingText/isError，不再单独摆一行红字。
                //
                // 单独一行的问题不是难看，是**位置**：红字出现在两个输入框下面，
                // 而错的可能只是上面那个（「两次输入不一致」甚至两个都指）——
                // 现在红框和红字都挂在出错的那个框自己身上。
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter { c -> c.isDigit() }.take(8)
                        pinError = null
                        confirmError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("PIN") },
                    isError = pinError != null,
                    supportingText = pinError?.let { { Text(it, color = AppColor.Danger) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        // 验旧 PIN 时只有一个框，回车就是提交 —— 少一次伸手指
                        imeAction = if (verifyAgainst != null) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus(); submit() }
                    )
                )
                // 「设新 PIN」才需要确认框；验旧 PIN 时问两遍纯属添乱
                if (verifyAgainst == null) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = {
                            confirm = it.filter { c -> c.isDigit() }.take(8)
                            confirmError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("再输一次") },
                        isError = confirmError != null,
                        supportingText = confirmError?.let { { Text(it, color = AppColor.Danger) } },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus(); submit() }
                        )
                    )
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
            AppTextButton(onClick = { submit() }) { Text(confirmLabel) }
        },
        dismissButton = {
            AppTextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
