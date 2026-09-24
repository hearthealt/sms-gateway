package com.smsgateway.app.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smsgateway.app.ConnectOutcome
import com.smsgateway.app.ConnectStage
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import com.smsgateway.app.ui.AppButton
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppOutlinedButton
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.ui.AppTextButton
import com.smsgateway.app.ui.components.InlineNotice
import com.smsgateway.app.ui.components.NoticeType
import com.smsgateway.app.ui.theme.AppColor
import com.smsgateway.app.ui.theme.AppSize
import com.smsgateway.app.ui.theme.AppSpacing
import com.smsgateway.app.ui.theme.AppTypography
import com.smsgateway.app.ui.utils.SystemSettings

/**
 * 扫码连接服务器：把一台**全新**手机接进来的入口。
 *
 * <p>与「设置 → 导出配置」的分工：那个页面只做导出，面向的是**已经配好**的手机之间
 * 互相搬配置；这个页面只做导入，而且一口气做完
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
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var scanning by remember { mutableStateOf(false) }
    var manualOpen by remember { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<QrConfig?>(null) }
    var outcome by remember { mutableStateOf<ConnectOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 每次「重新开始扫」都 +1，喂给 CameraScanner 去复位解码闩锁。
    //
    // 扫到一张不是配置码的码（微信码、网址码）时，analyzer 会把 finished 永久置为 true，
    // 而这一帧里 scanning 是先 false 再马上 true 的 —— 中间不发生重组，
    // CameraScanner 不会移出组合，停了工的 analyzer 就一直留着：预览照常在动，
    // 页面却再也不认任何码，只能退出页面重进。
    var scanAttempt by remember { mutableStateOf(0) }

    val stage = state.connectStage
    // 提到局部 val：outcome 是委托属性，`when` 分支里做不了智能转换，
    // 不提这一下就得写 `outcome!!`。名字不复用 result —— offerConfig 里
    // 那个解析结果也叫 result，重名会被编译器点名。
    val currentOutcome = outcome

    // 这个页面「参与过」的连接：本页点过确认，或进来时它已经在跑。
    // 连接跑在 viewModelScope 里，用户完全可以在它跑完之前退出去 —— 那条结论
    // 会在**下一次**进这个页面时才到位。光靠 onDispose 清是不够的：
    // onDispose 清的是它离开那一刻的值（那时还是 null），清不掉后到的那一条。
    // 所以判定必须落在「这次页面有没有份」上，而不是「state 里有没有值」。
    var awaiting by remember { mutableStateOf(false) }
    LaunchedEffect(state.connectStage) {
        if (state.connectStage != null) awaiting = true
    }

    // 结论从 ViewModel 的持久字段挪到本地：留在那儿的话，下次进这个页面又会冒出来。
    LaunchedEffect(state.connectResult) {
        val result = state.connectResult ?: return@LaunchedEffect
        if (!awaiting) return@LaunchedEffect
        awaiting = false
        outcome = result
        scanning = false
        viewModel.clearConnectResult()
    }

    // 离开页面时顺手清一次，别把没人认领的结论留在 state 里过夜。
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
                //
                // 这一行是**必须的**：不复位的话那个分析器已经停工了，
                // 页面会看起来像「相机还活着但完全没反应」。
                scanAttempt++
                scanning = true
            }
        }
    }

    /** 回到取景并确保解码闩锁是开的。所有「重新扫一张」的路都走它。 */
    fun rescan() {
        error = null
        outcome = null
        scanAttempt++
        scanning = true
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            IconButton(onClick = { manualOpen = true }) {
                Icon(Icons.Default.Edit, "手动输入地址", tint = AppColor.onBrand)
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
                stage != null -> ConnectingView(stage = stage, attempt = state.connectAttempt)

                currentOutcome != null -> ResultView(
                    result = currentOutcome,
                    onRetry = { rescan() },
                    onRetrySaved = {
                        outcome = null
                        error = null
                        awaiting = true
                        viewModel.retryQuickConnect()
                    },
                    // 成功之后要给一条**明路**。原先只在卡片里写一句
                    // 「返回主页即可看到设备状态」，而这一页填满了整屏、
                    // 左上角那个返回箭头又小 —— 配网的人第一次来，不会知道该退出去。
                    onDone = onBack
                )

                scanning -> CameraView(
                    hint = error ?: "把管理后台「快速连接」里的二维码放进画面中",
                    isError = error != null,
                    resetSignal = scanAttempt,
                    onDecoded = ::offerConfig
                )

                else -> IdleView(
                    message = error ?: "需要相机权限才能扫码。",
                    onRetry = {
                        error = null
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
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
                awaiting = true
                viewModel.quickConnect(config.url.orEmpty(), config.enrollToken, config.identity)
            }
        )
    }

    // ---------- 确认框：扫码这条路的安全把关点 ----------
    pendingConfig?.let { config ->
        AlertDialog(
            // 点框外关掉 = 取消 = 接着扫。原先这里是只清 pendingConfig，
            // 于是页面回到 IdleView 那一支、提示「需要相机权限」—— 而权限其实是有的，
            // 用户只会去反复查权限，查完返回还是同一句话。
            onDismissRequest = { pendingConfig = null; rescan() },
            title = { Text(if (config.hasIdentity) "确认采用设备身份" else "确认连接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        text = "即将把服务器地址改成下面这个，并立即注册本机。请核对无误 —— " +
                            "地址决定了本机所有短信和验证码发送到哪里。",
                        style = AppTypography.bodySmall
                    )
                    // 完整地址以等宽字体展示，不截断：用户必须能看清每一个字符
                    SelectionContainer {
                        Text(
                            text = config.url.orEmpty(),
                            style = AppTypography.mono(AppTypography.bodySmall)
                        )
                    }

                    // 恢复码也允许从这里扫进来（重装过的手机打开的就是这个页面）。
                    // 但它会改变这台设备的身份，所以警示不能省。
                    config.identity?.let { identity ->
                        // 用 InlineNotice 而不是手写「⚠」字符：那个字符在中文系统字体里
                        // 会被渲染成一个彩色 emoji 或一个空心方框，两种都与旁边那一排
                        // 图标对不上；而且这一页其他提示走的是同一套色块。
                        InlineNotice(
                            text = "这张码同时携带了设备身份，采用之后本机将以该设备的名义" +
                                "上报短信 —— 请先确认这就是你自己的设备。",
                            type = NoticeType.Danger
                        )
                        SelectionContainer {
                            Text(
                                text = identity.deviceId,
                                style = AppTypography.mono(AppTypography.bodySmall)
                            )
                        }
                    }

                    // 只说明「带了口令」，不显示口令本身：它是一串随机值，
                    // 用户没有任何办法核对，显示出来只是徒增噪音。
                    if (config.enrollToken != null) {
                        Text(
                            text = "这张码包含服务器的接入口令。",
                            style = AppTypography.caption,
                            color = AppColor.InkMuted
                        )
                    }
                }
            },
            confirmButton = {
                AppTextButton(onClick = {
                    outcome = null
                    awaiting = true
                    viewModel.quickConnect(
                        url = config.url.orEmpty(),
                        enrollToken = config.enrollToken,
                        identity = config.identity
                    )
                    pendingConfig = null
                }) { Text("确认连接") }
            },
            dismissButton = {
                AppTextButton(onClick = {
                    pendingConfig = null
                    // 取消不是放弃：多半是码不对，让他接着扫。
                    rescan()
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
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
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
                    style = AppTypography.caption,
                    color = AppColor.InkMuted
                )
                error?.let {
                    Text(text = it, style = AppTypography.bodySmall, color = AppColor.Danger)
                }
            }
        },
        confirmButton = {
            AppTextButton(
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
        dismissButton = { AppTextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 取景。整屏铺满，细描边框标出「要往这里放」，提示压在下缘。
 *
 * 那个方框不是装饰：二维码放进画面**任何位置**都能解出来，但人不知道这件事 ——
 * 没有框的时候，常见的动作是举着手机来回晃、找「到底该对准哪」。框给出一个明确的落点。
 */
@Composable
private fun CameraView(
    hint: String,
    isError: Boolean,
    resetSignal: Int,
    onDecoded: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraScanner(onDecoded = onDecoded, resetSignal = resetSignal)

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(SCAN_FRAME_SIZE)
                .border(
                    width = 2.dp,
                    color = if (isError) AppColor.DangerBg else AppColor.onBrand,
                    shape = RoundedCornerShape(AppSpacing.md)
                )
        )

        Text(
            text = hint,
            style = AppTypography.bodySmall,
            color = AppColor.onBrand,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = AppSpacing.xxl,
                    start = AppSpacing.xl,
                    end = AppSpacing.xl
                )
                // 出错时染红：连着扫错几次却看到同一句提示，人会以为应用卡住了。
                // 这两个色值不跟主题走：它压在相机取景画面上，底不是页面底色，
                // 深色模式下把黑底调浅反而会让白字糊在画面上。
                .background(
                    if (isError) Color(0xCCB3261E) else Color(0x99000000),
                    RoundedCornerShape(AppSpacing.md)
                )
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
        )
    }
}

/** 取景框边长。够装下一张 240dp 的二维码（导出页那张就是这个尺寸）。 */
private val SCAN_FRAME_SIZE = 240.dp

/**
 * 没在扫码（权限被拒等）。给出三条出路，都不绕。
 *
 * 「重新申请」在用户选过「不再询问」之后是没有作用的（系统会立刻回一个 false，
 * 连弹窗都不弹），所以「去系统设置」必须**常驻**，不能等判断出「被永久拒绝」再显示 ——
 * 那个判断要靠 shouldShowRequestPermissionRationale，而它返回 false 时既可能是
 * 「永久拒绝」也可能是「还没问过」，两种情况分不开。
 */
@Composable
private fun IdleView(message: String, onRetry: () -> Unit, onManual: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = AppTypography.bodyMedium,
            color = AppColor.InkSecondary,
            // 说明折行时要居中。原先没有 textAlign，第二行会左对齐，
            // 夹在一堆居中元素中间像溢出来的。
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(AppSpacing.lg))
        AppButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("重新申请相机权限")
        }
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        AppOutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Text("手动输入地址")
        }
        Spacer(modifier = Modifier.height(AppSpacing.xs))
        AppTextButton(
            onClick = { SystemSettings.openAppDetails(context) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("去系统设置里开权限") }
    }
}

@Composable
private fun ConnectingView(stage: ConnectStage, attempt: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(AppSize.spinnerPage),
            strokeWidth = 3.dp
        )
        Spacer(modifier = Modifier.height(AppSpacing.md))
        Text(
            text = when (stage) {
                // 重试时把次数说出来：刚连上 WiFi 时链路要十几秒才就绪，探测会重试几轮、
                // 每轮最长 8 秒。只转一个圈的话，这十几秒看起来就是死机。
                ConnectStage.PROBING ->
                    if (attempt > 1) "正在测试连接（第 $attempt 次）…" else "正在测试连接…"

                ConnectStage.REGISTERING -> "正在注册设备…"
            },
            style = AppTypography.bodyLarge,
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
private fun ResultView(
    result: ConnectOutcome,
    onRetry: () -> Unit,
    onRetrySaved: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.md),
        verticalArrangement = Arrangement.Center
    ) {
        AppCard {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = if (result.ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (result.ok) AppColor.Success else AppColor.Danger,
                    modifier = Modifier.size(AppSize.iconLg)
                )
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xxs)) {
                    Text(
                        text = if (result.ok) "连接成功" else "连接失败",
                        style = AppTypography.h3,
                        color = if (result.ok) AppColor.Success else AppColor.Danger
                    )
                    Text(
                        text = result.message,
                        style = AppTypography.bodySmall,
                        color = AppColor.Ink
                    )
                }
            }
        }

        if (result.ok) {
            // 成功之后的主按钮是「返回主页」，不再是「再扫一次」。
            // 此前这一支什么按钮都没有，只靠卡片里一行小字指路 —— 而配网的人
            // 第一次来，屏幕上唯一像出口的东西是左上角那个小箭头。
            Spacer(modifier = Modifier.height(AppSpacing.md))
            AppButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("返回主页") }
        } else {
            Spacer(modifier = Modifier.height(AppSpacing.md))

            // 地址已经存下了，失败多半是「刚连上 WiFi、局域网还没就绪」——
            // 那种情况重扫一遍二维码纯属白费功夫，原地重试即可。所以把它放在主按钮位，
            // 「重新扫码」降到次要 —— 只有二维码本身扫错了（地址不对）才需要它。
            if (result.retryable) {
                AppButton(onClick = onRetrySaved, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                AppOutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("换一张码扫")
                }
            } else {
                AppButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("重新扫码") }
            }
        }
    }
}
