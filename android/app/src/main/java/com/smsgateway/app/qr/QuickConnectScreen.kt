package com.smsgateway.app.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smsgateway.app.ConnectOutcome
import com.smsgateway.app.ConnectStage
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppColor
import com.smsgateway.app.ui.AppScreen

/**
 * 扫码连接服务器：把一台**全新**手机接进来的入口。
 *
 * <p>与「设置 → 配置二维码」的分工：那个页面两头都做（导出当前配置、扫码导入一份配置），
 * 面向的是**已经配好**的手机之间互相搬配置；这个页面只做导入，而且一口气做完
 * 保存地址 → 测试连接 → 注册设备 —— 它面向的是刚装上应用、什么都还没有的手机。
 *
 * <p>**进来直接开相机。** 从主页点进来的人就是要扫码，原先还得再点一下「打开相机扫码」
 * 才进取景，那一下是白点的。没有相机权限、或权限被拒时，退到右上角的「手动输入」，
 * 那条路一步都不多。
 *
 * <p>导入路径**保留一次确认**，不因为「一键」就把这道关去掉：
 * network_security_config 允许明文访问任意主机，一张二维码等于一条未认证的配置注入
 * 通道，恶意二维码可以把设备指向攻击者的服务器、此后每条短信都被截走。所以扫出来的
 * 地址必须先完整展示给用户核对。（手动输入那条路不用再确认一次 —— 地址是用户自己
 * 敲进去的，他看着输入框就是在核对。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var scanning by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<QrConfig?>(null) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val stage = state.connectStage
    // 提到局部 val：outcome 是委托属性，`when` 分支里做不了智能转换，
    // 不提这一下就得写 `outcome!!`。名字不复用 result —— offerConfig 里
    // 那个解析结果也叫 result，重名会被编译器点名。
    val currentOutcome = outcome

    // 结论从 ViewModel 的持久字段挪到本地：留在那儿的话，下次进这个页面又会冒出来。
    LaunchedEffect(state.connectResult) {
        state.connectResult?.let {
            outcome = it
            scanning = false
            viewModel.clearConnectResult()
        }
    }

    // 离开页面时再清一次。连接是在 ViewModel 里跑的，用户完全可以在它跑完之前退出去 ——
    // 那条结论若留在 state 里，会在**下一次**进这个页面时冒出来，冒充成这一次的结果。
    DisposableEffect(Unit) {
        onDispose { viewModel.clearConnectResult() }
    }

    fun offerConfig(raw: String) {
        scanning = false
        when (val result = QrConfigCodec.parse(raw)) {
            is QrParseResult.Ok -> {
                error = null
                pendingConfig = result.config
            }

            is QrParseResult.Invalid -> {
                error = "扫到的不是配置二维码：${result.reason}"
                // 扫错了码多半是没对准或扫了别的码，不是要放弃 —— 直接接着扫。
                // 错误提示压在取景画面下缘（见 CameraView），否则这一支会被
                // 下面的 when 选走、提示永远看不见。
                scanning = true
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanning = true
        } else {
            // 拒了就直接落到手动输入，不留一条「什么都点不了」的死路。
            error = "没有相机权限，无法扫码。请在下面手动输入服务器地址。"
            manualOpen = true
        }
    }

    // 进来就开相机。放在 LaunchedEffect 里而不是直接写在组合期：申请权限是有副作用的操作，
    // 组合期可能被重复执行。
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) scanning = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AppScreen(
        title = "扫码连接",
        onBack = onBack,
        actions = {
            IconButton(onClick = { manualOpen = true }) {
                Icon(Icons.Default.Edit, "手动输入地址", tint = Color.White)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                // 连接中：只留进度。相机这时已经出了组合（下面的分支没选它），
                // 说清「现在在哪一步」—— 探测最坏要 3 × 8 秒，只转个不说话的圈，
                // 现场会当成死机然后去杀进程。
                stage != null -> ConnectingView(stage = stage)

                currentOutcome != null -> ResultView(
                    result = currentOutcome,
                    onRetry = { outcome = null; error = null; scanning = true }
                )

                scanning -> CameraView(
                    hint = error ?: "把管理后台「快速连接」里的二维码放进画面中",
                    isError = error != null,
                    onDecoded = ::offerConfig
                )

                else -> IdleView(
                    message = error ?: "需要相机权限才能扫码。",
                    onRetry = { error = null; cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    onManual = { manualOpen = true }
                )
            }
        }
    }

    if (manualOpen) {
        ManualInputDialog(
            onDismiss = { manualOpen = false },
            onConnect = { config ->
                manualOpen = false
                outcome = null
                error = null
                viewModel.quickConnect(config.url.orEmpty(), config.enrollToken, config.identity)
            }
        )
    }

    // ---------- 确认框：扫码这条路的安全把关点 ----------
    pendingConfig?.let { config ->
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            title = { Text(if (config.hasIdentity) "确认采用设备身份" else "确认连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "即将把服务器地址改成下面这个，并立即注册本机。请核对无误 —— " +
                            "地址决定了本机所有短信和验证码发送到哪里。",
                        fontSize = 13.sp
                    )
                    // 完整地址以等宽字体展示，不截断：用户必须能看清每一个字符
                    SelectionContainer {
                        Text(
                            text = config.url.orEmpty(),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // 恢复码也允许从这里扫进来（重装过的手机打开的就是这个页面）。
                    // 但它会改变这台设备的身份，所以警示不能省。
                    config.identity?.let { identity ->
                        Text(
                            text = "⚠ 这张码同时携带了设备身份，采用之后本机将以该设备的名义" +
                                "上报短信 —— 请先确认这就是你自己的设备。",
                            fontSize = 13.sp,
                            color = AppColor.Danger
                        )
                        SelectionContainer {
                            Text(
                                text = identity.deviceId,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 只说明「带了口令」，不显示口令本身：它是一串随机值，
                    // 用户没有任何办法核对，显示出来只是徒增噪音。
                    if (config.enrollToken != null) {
                        Text(
                            text = "这张码包含服务器的接入口令。",
                            fontSize = 12.sp,
                            color = AppColor.InkMuted
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    outcome = null
                    viewModel.quickConnect(
                        url = config.url.orEmpty(),
                        enrollToken = config.enrollToken,
                        identity = config.identity
                    )
                    pendingConfig = null
                }) { Text("确认连接") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingConfig = null
                    // 取消不是放弃：多半是码不对，让他接着扫。
                    scanning = true
                }) { Text("取消") }
            }
        )
    }
}

/**
 * 手动输入。这条路不用二次确认 —— 地址是用户自己敲的，看着输入框就是在核对。
 *
 * 两个框：地址 + 接入口令。口令单独一个框是必要的，服务端启用准入口令之后，
 * 只填地址必然被拒（403），而现场看到的是「连接失败：…没有携带它」，未必想得到
 * 是缺了另一个字段。摆一个空框在那里，缺什么一目了然。
 */
@Composable
private fun ManualInputDialog(
    onDismiss: () -> Unit,
    onConnect: (QrConfig) -> Unit
) {
    var input by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { text ->
                        error = null
                        // 整段「连接信息」（后台那个含口令的复制按钮给的 JSON）粘进来时，
                        // 顺手把口令拆到下面那个框里，地址框只留地址。
                        //
                        // 拆出来而不是整段留着：两个值分别摆在两个框里，用户能一眼看出
                        // 到底识别到了什么 —— 整段 JSON 留在框里的话，谁也说不清它有没有
                        // 被读懂，只能靠点下去试。
                        val ok = QrConfigCodec.parse(text) as? QrParseResult.Ok
                        val token = ok?.config?.enrollToken
                        if (ok != null && !token.isNullOrBlank()) {
                            input = ok.config.url.orEmpty()
                            tokenInput = token
                        } else {
                            input = text
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("服务器地址") },
                    placeholder = { Text("192.168.1.100:8080") }
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("接入口令") },
                    placeholder = { Text("服务端没启用就留空") }
                )
                Text(
                    text = "地址不必写 http://。把「复制连接信息」整段粘进上面会自动拆出口令。",
                    fontSize = 12.sp,
                    color = AppColor.InkMuted
                )
                error?.let {
                    Text(text = it, fontSize = 13.sp, color = AppColor.Danger)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = QrConfigCodec.parse(input)) {
                        is QrParseResult.Ok -> {
                            // 口令框里的值优先：用户可能手改过它，也可能把整段 JSON 粘进
                            // 来之后在口令框里做了修正，以眼见的那份为准。
                            onConnect(
                                result.config.copy(
                                    enrollToken = tokenInput.trim().ifBlank { result.config.enrollToken }
                                )
                            )
                        }

                        is QrParseResult.Invalid -> error = result.reason
                    }
                },
                enabled = input.isNotBlank()
            ) { Text("连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 取景。整屏铺满，提示压在下缘 —— 二维码就在取景框里，不需要别的元素抢地方。 */
@Composable
private fun CameraView(hint: String, isError: Boolean, onDecoded: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraScanner(onDecoded = onDecoded)

        Text(
            text = hint,
            fontSize = 13.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
                // 出错时染红：连着扫错几次却看到同一句提示，人会以为应用卡住了
                .background(
                    if (isError) Color(0xCCB3261E) else Color(0x99000000),
                    RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** 没在扫码（权限被拒等）。给出两条出路，都不绕。 */
@Composable
private fun IdleView(message: String, onRetry: () -> Unit, onManual: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, fontSize = 14.sp, color = AppColor.InkSecondary)
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新申请相机权限") }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("手动输入地址") }
    }
}

@Composable
private fun ConnectingView(stage: ConnectStage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (stage) {
                ConnectStage.PROBING -> "正在测试连接…"
                ConnectStage.REGISTERING -> "正在注册设备…"
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppColor.Ink
        )
    }
}

/**
 * 结论。**留在页面上**，不走 snackbar —— 失败原因（地址不对 / 对面不是本服务 /
 * 口令被拒）是现场要照着排查的东西，不该自己消失。
 */
@Composable
private fun ResultView(result: ConnectOutcome, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        AppCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (result.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (result.ok) AppColor.Success else AppColor.Danger,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (result.ok) "连接成功" else "连接失败",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (result.ok) AppColor.Success else AppColor.Danger
                    )
                    Text(
                        text = result.message,
                        fontSize = 13.sp,
                        color = if (result.ok) AppColor.Success else AppColor.Danger
                    )
                    if (result.ok) {
                        Text(
                            text = "返回主页即可看到设备状态。",
                            fontSize = 12.sp,
                            color = AppColor.InkMuted
                        )
                    }
                }
            }
        }

        // 失败才给「重试」：成功之后再摆一个扫码按钮，只会让人以为还得再扫一次。
        if (!result.ok) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新扫码") }
        }
    }
}
