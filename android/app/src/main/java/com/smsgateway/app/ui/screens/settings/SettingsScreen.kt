package com.smsgateway.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.components.StatusRow
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.util.DeviceName
import com.smsgateway.app.util.DevicePhone
import com.smsgateway.app.util.SimSlotsResult
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 包含四张卡片：
 * 1. 服务器地址 - 显示当前地址，提供扫码连接和导出配置功能
 * 2. 设备信息 - 设备 ID、设备名称、手机号输入、重新注册
 * 3. 其他 - 清理本地已上传记录
 * 4. 关于 - 应用版本、包名、开源许可
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenQuickConnect: () -> Unit,
    onOpenQrExport: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var phoneInput by remember(state.phone) { mutableStateOf(state.phone) }
    var showReregisterDialog by remember { mutableStateOf(false) }

    // 设备名称跟随手机本身，不是本应用的配置项：这里只读展示，改要到手机的
    // 「设置 → 关于手机 → 设备名称」。见 DeviceName。
    val deviceName = rememberDeviceName()

    // SIM 卡列表依赖「电话权限有没有授予」，而权限可能在本页显示期间才被授予 ——
    // 所以不能 `remember { }` 缓存：一份缓存的空列表会让「读 SIM 卡」按钮此后再也不
    // 出现，连重新申请的机会都没有（现场「怎么读不到手机号」就是这么来的）。
    var simRefreshKey by remember { mutableIntStateOf(0) }
    val simResult = rememberSimSlots(refreshSignal = simRefreshKey)
    val sims = simResult.slots
    var showSimPicker by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val hasPhonePermission = DevicePhone.hasPermission(context)
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // 授了就重算列表（simRefreshKey 变了，见 rememberSimSlots）
        simRefreshKey++
        if (granted.values.none { it }) {
            toast("没有电话权限就读不到卡里的号码，在下面直接手填即可")
        }
    }

    // 设置页的提示（测试连接结论、清理记录结果）一闪而过，不在页面上留残余。
    // 写法与主页处理 registerMessage 那处一致：展示完立刻清掉状态，否则下次进来还会冒出来。
    LaunchedEffect(state.settingsMessage) {
        state.settingsMessage?.let {
            toast(it)
            viewModel.clearSettingsMessage()
        }
    }

    AppScreen(
        title = "设置",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AppSpacing.md)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            // 这张卡只剩「看一眼现在连的是哪」，改地址一律走扫一扫。
            //
            // 原先这里是 输入框 + [保存] + [测试连接] + [配置二维码]，等于把「扫一扫」
            // 那套流程在设置页又实现了一遍，而且是缺斤少两的一遍：它不认接入口令
            // （服务端启用口令后，在这里改完地址点重新注册必然 403，而这个页面
            // 没有任何地方能填口令），测试结论还只走一闪而过的 snackbar，而扫一扫
            // 那条路的结论是留在页面上的。
            SettingsCard(title = "服务器地址") {
                SelectionContainer {
                    Text(
                        text = state.serverUrl,
                        style = AppTypography.mono(AppTypography.bodySmall),
                        color = AppColor.Ink
                    )
                }

                Button(
                    onClick = onOpenQuickConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("扫码连接服务器")
                }

                OutlinedButton(
                    onClick = onOpenQrExport,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.xs))
                    Text("导出配置给另一台设备")
                }
            }

            SettingsCard(title = "设备信息") {
                // 与下面几行同一种排法（左标签、右等宽值），不要拆成「标签一行、值一行」——
                // 那会在这张卡里突兀地多占一行，而设备 ID 本身放得下。
                StatusRow(
                    label = "设备 ID",
                    // 没有 `ifBlank { "未设置" }` 那种兜底了：设备标识在第一次打开应用时
                    // 就已生成（DevicePrefs.getOrCreateDeviceId），这一行不会是空的。
                    // 留一个永远不会触发的兜底分支，只会让读代码的人以为它可能为空。
                    value = state.deviceId,
                    monospace = true,
                    selectable = true
                )

                // 设备名称只读展示，不给改：它跟随手机本身（见 DeviceName），
                // 摆一个输入框只会让人以为能改，改完还会与手机里的名字打架。
                StatusRow(label = "设备名称", value = deviceName)

                // 号码存在 SIM 卡上，多数运营商不写入，所以自动读取经常为空，
                // 这里的手填值才是权威来源。
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("本机号码") },
                    placeholder = { Text("自动读取不到时可在此填写") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )

                // 并成一行：读卡与保存是同一个字段的两个动作，各占一整行会把这张卡
                // 撑得过长，也和上面「保存 / 测试连接」那对的排法不一致。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 这个按钮**始终显示**，不挂在 `if (sims.isNotEmpty())` 下。
                    //
                    // 挂上去之后，读不到卡时它整块消失，而「读不到」的三种原因
                    // （没授权限 / 权限被拒 / 卡没写号码）在界面上长得一模一样，
                    // 用户既没有入口也没有线索 —— 现场唯一的结论是「这应用读不到号码」。
                    // 现在它按当前状态换成不同的动作，并把原因说出来。
                    OutlinedButton(
                        onClick = {
                            when {
                                !hasPhonePermission -> phonePermissionLauncher.launch(
                                    DevicePhone.requiredPermissions.toTypedArray()
                                )

                                sims.isNotEmpty() -> showSimPicker = true

                                else -> toast(
                                    simResult.problem ?: "没有检测到已激活的 SIM 卡，请手动填写号码"
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.SimCard,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                !hasPhonePermission -> "读取本机号码"
                                sims.isEmpty() -> "读 SIM 卡"
                                else -> "读 SIM 卡（${sims.size}）"
                            },
                            maxLines = 1
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.updatePhone(phoneInput)
                            focusManager.clearFocus()
                            toast("手机号已保存")
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("保存手机号", maxLines = 1) }
                }

                // 说明读不到的原因，并指出手填这条路。
                //
                // 三种「读不到」必须分开说：没授权限、系统不给卡列表、卡里没写号码。
                // 前两种各有各的下一步动作，第三种是常态（多数现代运营商不往卡里写号码），
                // 而它们在界面上长得一模一样时，用户只能得出「这应用读不到号码」。
                Text(
                    text = when {
                        !hasPhonePermission ->
                            "未授予电话权限，读不到 SIM 卡里存的号码。点上面的按钮申请，" +
                                "或直接在输入框里手填 —— 手填的值一样会被用上。"

                        simResult.problem != null ->
                            "${simResult.problem}。可直接在输入框里手填号码。"

                        else -> "号码存在 SIM 卡上，多数运营商不写入，读不到时手填即可。"
                    },
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )

                // 注册状态、禁用状态都不在这里重复一遍：主页的 HeroStatusCard 已经
                // 用一整张卡说这件事，被禁用还有专门的横幅。用户来设置页是为了改东西，
                // 不是为了看状态 —— 同一件事在两个地方各说一遍，改了其中一处的样式
                // 另一处就跟着不一致。

                OutlinedButton(
                    onClick = { showReregisterDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isRegistering
                ) {
                    if (state.isRegistering) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (state.isRegistering) "注册中…" else "重新注册")
                }
            }

            // 应用锁放在设备信息之后、其他之前：它改的是「这台设备怎么被使用」，
            // 与设备身份同属一类，而「其他」里是清理记录这类维护动作
            AppLockCard()

            SettingsCard(title = "其他") {
                OutlinedButton(
                    onClick = { viewModel.clearUploadedRecords() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清理本地已上传记录") }
                Text(
                    text = "只删本地已上传成功的历史，不影响服务端数据。",
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
            }

            // 版本号、包名、开源许可归到「关于」。它们的共同点是「只在排查时看一眼」，
            // 和上面那个会动手的按钮不是一类东西。
            AboutCard()
        }
    }

    if (showSimPicker) {
        AlertDialog(
            onDismissRequest = { showSimPicker = false },
            title = { Text("选择 SIM 卡") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sims.forEach { sim ->
                        TextButton(
                            onClick = {
                                showSimPicker = false
                                val number = sim.number
                                if (number != null) {
                                    phoneInput = number
                                    // 带上 subId：多卡时要靠它判断某条短信来自哪张卡
                                    viewModel.updatePhone(number, sim.subscriptionId)
                                    toast("已读取：$number")
                                } else {
                                    toast("${sim.label} 没有写入号码，请手动填写")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(sim.label, style = AppTypography.bodyMedium)
                                Text(
                                    text = sim.number ?: "读不到号码",
                                    style = AppTypography.caption,
                                    color = AppColor.InkMuted
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSimPicker = false }) { Text("取消") }
            }
        )
    }

    if (showReregisterDialog) {
        AlertDialog(
            onDismissRequest = { showReregisterDialog = false },
            title = { Text("重新注册设备？") },
            text = {
                Text("会用当前的服务器地址重新向后台登记这台设备。设备 ID 保持不变，后台不会新增设备。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showReregisterDialog = false
                    viewModel.registerDevice()
                }) { Text("重新注册") }
            },
            dismissButton = {
                TextButton(onClick = { showReregisterDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun rememberDeviceName(): String {
    val context = LocalContext.current
    return remember { DeviceName.read(context) }
}

/**
 * SIM 卡列表。首次组合时算一次，之后每次回到前台、以及 [refreshSignal] 变化时重算。
 *
 * 重算的时机都不能省：
 * - **回到前台**：用户可能是去系统设置里手动开的电话权限。
 * - **[refreshSignal]**：用户在**本页**点了申请并把权限授了下来 —— 这不会经过一次
 *   ON_RESUME（同一个 Activity 没离开过前台），不重算的话列表还是空的，
 *   按钮会停在「读取本机号码」上，看着像没生效。
 *
 * 写法与主页的 `rememberDeviceChecks` 一致：都是「依赖权限、要随生命周期重算」的值。
 */
@Composable
private fun rememberSimSlots(refreshSignal: Int): SimSlotsResult {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var result by remember { mutableStateOf(DevicePhone.querySlots(context)) }

    DisposableEffect(lifecycleOwner, context, refreshSignal) {
        result = DevicePhone.querySlots(context)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                result = DevicePhone.querySlots(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return result
}
