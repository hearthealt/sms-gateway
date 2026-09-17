package com.smsgateway.app.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.smsgateway.app.DashboardState
import com.smsgateway.app.DashboardViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * 配置二维码页：导出当前配置，或扫码 / 粘贴导入一份配置。
 *
 * 导入路径刻意**不做自动保存**：本应用允许明文访问任意主机，二维码等于一条未认证的
 * 配置注入通道，所以解出来的地址必须先完整展示给用户确认，再写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrConfigScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    var pendingConfig by remember { mutableStateOf<QrConfig?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pasteInput by remember { mutableStateOf("") }

    // 导出内容跟随当前配置；设备名留空就不写进载荷
    val payload = remember(state.serverUrl, state.deviceName) {
        QrConfigCodec.encode(state.serverUrl, state.deviceName)
    }
    val qrBitmap = remember(payload) { QrEncoder.encode(payload) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scanning = true
        } else {
            error = "没有相机权限，无法扫码。可以改用下方的粘贴导入。"
        }
    }

    fun offerConfig(raw: String) {
        scanning = false
        when (val result = QrConfigCodec.parse(raw)) {
            is QrParseResult.Ok -> {
                error = null
                pendingConfig = result.config
            }

            is QrParseResult.Invalid -> {
                error = "无效的配置：${result.reason}"
            }
        }
    }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置二维码", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ---------- 导出 ----------
            QrCard(title = "导出到另一台设备") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "配置二维码",
                        modifier = Modifier.size(240.dp),
                        // 二维码放大后必须关掉插值，否则边缘被模糊化会扫不动
                        filterQuality = FilterQuality.None
                    )
                }
                Text(
                    text = "在另一台设备上打开「设置 → 配置二维码 → 扫码导入」对准即可。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                SelectionContainer {
                    Text(
                        text = payload,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ---------- 导入 ----------
            QrCard(title = "扫码导入") {
                if (scanning) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        CameraScanner(onDecoded = { raw -> offerConfig(raw) })
                    }
                    Text(
                        text = "把二维码放进画面中…",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    OutlinedButton(
                        onClick = { scanning = false },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("取消") }
                } else {
                    Button(
                        onClick = {
                            error = null
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) scanning = true
                            else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("打开相机扫码")
                    }
                }

                HorizontalDivider()

                Text(
                    text = "或者粘贴配置内容：",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                OutlinedTextField(
                    value = pasteInput,
                    onValueChange = { pasteInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    placeholder = { Text("{\"url\":\"http://192.168.1.100:8080\"}") }
                )
                OutlinedButton(
                    onClick = { offerConfig(pasteInput) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pasteInput.isNotBlank()
                ) { Text("解析并导入") }

                error?.let {
                    Text(text = it, fontSize = 13.sp, color = Color(0xFFC62828))
                }
            }
        }
    }

    // ---------- 确认框：这是安全把关点 ----------
    pendingConfig?.let { config ->
        AlertDialog(
            onDismissRequest = { pendingConfig = null },
            title = { Text("确认导入配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "即将把服务器地址改成下面这个。请核对无误 —— 地址决定了" +
                            "本机所有短信和验证码发送到哪里。",
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
                    config.deviceName?.let {
                        Text("设备名称：$it", fontSize = 13.sp)
                    }
                    Text(
                        text = "导入后需要重新注册设备（令牌与服务器绑定）。",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = config.url.orEmpty()
                    viewModel.importServerUrl(url)
                    config.deviceName?.let { viewModel.updateDeviceName(it) }
                    pendingConfig = null
                    toast("配置已导入，请到设置里重新注册设备")
                }) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfig = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun QrCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

/**
 * CameraX 取景 + 解码。
 *
 * 用 AndroidView + PreviewView 而不是 Compose 原生组件：camera-compose 直到 CameraX 1.5.0
 * 才稳定，而 1.5.0 要求 compileSdk 35，本项目停留在 34（见 build.gradle.kts 的说明）。
 */
@Composable
private fun CameraScanner(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzer = remember { QrAnalyzer(onDecoded) }
    val previewView = remember {
        PreviewView(context).apply {
            // 默认的 FILL_CENTER 会裁切画面，导致「用户对准的区域」与「实际分析的区域」
            // 不一致；FIT_CENTER 保证看到的都能被分析到。
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                analysis = ImageAnalysis.Builder()
                    // 新帧到来时丢弃积压的旧帧。不设这个，队列会无限增长直至 OOM。
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor) { image -> analyzer.analyze(image) } }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("CameraScanner", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysis?.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
