package com.smsgateway.app.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.R
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.AppTopBar
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
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
 *
 * ## 顶栏是必须的，不是装饰
 *
 * 状态栏图标被钉成浅色（见 MainActivity.enableEdgeToEdge），而原先这一页是一整片浅灰底 ——
 * 白图标压在浅灰上等于看不见：时间、电量、信号全糊掉，而这一页恰恰是「拿起来先看一眼」
 * 的那一页。给它同一条品牌蓝顶栏，图标就有了对比度，顺便也说清了「现在是哪个应用锁着」。
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
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    fun tryUnlock() {
        if (onUnlockWithPin(pin)) {
            error = null
        } else {
            error = "PIN 不对"
            pin = ""
        }
    }

    // 进页面就把光标放进 PIN 框：这一页只有一件事可做，让用户先点一下输入框是白点的一下。
    // 放在 LaunchedEffect 里而不是 remember 里：请求焦点是有副作用的操作，
    // 组合期可能被重复执行。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 录了指纹就直接拉一次系统框。
    //
    // 原先指纹只是一个「用指纹解锁」的次要文字按钮，摆在解锁按钮下面 ——
    // 而它是这条路上最快的一条，设备录了指纹的人每次都要多找一下。
    // 失败（用户取消、指纹不匹配）会回到这一页并给出提示，文字按钮仍然在那里，
    // 所以「自动拉起」不会变成一条死路。
    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric) onUnlockWithBiometric()
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.app_name)) },
        containerColor = AppColor.Screen
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 可滚动 + imePadding：键盘弹出来时「解锁」按钮与「忘记 PIN」都会被盖住，
                // 而这一页原先既不能滚、也没有让开键盘 —— 短屏或横屏上按钮直接点不到。
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(AppSpacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 与首页头部同一个 logo 块：锁屏是第一眼看到的画面，
            // 它得让人确认「这就是那个应用」，而不是一个泛泛的 PIN 输入页。
            // 尺寸与圆角都取同一组 token —— 两处写死过（56/28 与 38/24），差得还挺明显。
            Box(
                modifier = Modifier
                    .size(AppSize.logoBlock)
                    .clip(AppColor.LogoShape)
                    .background(AppColor.onBrand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_logo_mark),
                    contentDescription = null,
                    tint = AppColor.Brand,
                    modifier = Modifier.size(AppSize.logoMark)
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AppColor.InkMuted,
                modifier = Modifier.size(AppSize.iconMd)
            )

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // 应用名取 @string/app_name：原先这里写死「云驿站已锁定」，
            // 而这个名字改过一次 —— 当时只改了清单里的 label，锁屏留了旧名。
            Text(
                text = stringResource(R.string.app_name) + "已锁定",
                style = AppTypography.h2,
                color = AppColor.Ink
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Text(
                text = "输入 PIN 解锁。网关仍在后台运行，锁的只是界面。",
                style = AppTypography.bodySmall,
                color = AppColor.InkMuted,
                // 折成两行时要居中。原先没有 textAlign，第二行会左对齐 ——
                // 在一堆居中元素中间，那一行看起来像溢出来的。
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(AppSpacing.xl))

            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { c -> c.isDigit() }.take(8)
                    error = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                label = { Text("PIN") },
                isError = error != null,
                supportingText = error?.let { { Text(it, color = AppColor.Danger) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    // 回车就是解锁：这一页只有一个输入框，让用户输完再把手挪到按钮上是多一步
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        tryUnlock()
                    }
                )
            )

            biometricMessage?.let {
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = it,
                    style = AppTypography.bodySmall,
                    color = AppColor.InkMuted,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.lg))

            AppButton(
                onClick = { tryUnlock() },
                enabled = pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("解锁") }

            if (canUseBiometric) {
                Spacer(modifier = Modifier.height(AppSpacing.sm))
                AppTextButton(onClick = onUnlockWithBiometric) { Text("用指纹解锁") }
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))
            AppTextButton(onClick = { showForgot = true }) { Text("忘记 PIN？") }
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
                AppTextButton(onClick = {
                    showForgot = false
                    onOpenAppSettings()
                }) { Text("去系统设置") }
            },
            dismissButton = {
                AppTextButton(onClick = { showForgot = false }) { Text("知道了") }
            }
        )
    }
}
