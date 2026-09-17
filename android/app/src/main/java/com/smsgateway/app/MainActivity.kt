package com.smsgateway.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.qr.QrConfigScreen
import com.smsgateway.app.util.DevicePhone
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1976D2),
                    secondary = Color(0xFF4CAF50),
                    surface = Color(0xFFF5F5F5)
                )
            ) {
                GatewayApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 补读点：权限可能刚在上一轮弹窗里被授予，或用户刚在设置页清掉了号码。
        // 已有号码时该方法会直接返回，不会覆盖手填的值。
        viewModel.tryAutoFillPhone()
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.READ_SMS)
            addAll(DevicePhone.requiredPermissions)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }
}

/**
 * 只有几个页面，用状态切换就够了，不值得为它引入导航库。
 * 层级是树不是栈（每个子页面的父页面固定），所以不需要返回栈。
 */
private enum class Screen {
    HOME, SETTINGS, QUEUE, SERVER_SMS, SELF_TEST, QR_CONFIG
}

@Composable
fun GatewayApp(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()

    // rememberSaveable 而非 remember：后者在旋转屏后会弹回首页
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 没有导航库，系统返回键就得自己接。少了这一句，在子页面按返回会直接退出应用。
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    LaunchedEffect(state.registerError) {
        state.registerError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearRegisterError()
        }
    }

    when (screen) {
        Screen.HOME -> HomeScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenQueue = { viewModel.refreshQueue(); screen = Screen.QUEUE },
            onOpenServerSms = { viewModel.loadServerSms(); screen = Screen.SERVER_SMS },
            onOpenSelfTest = { viewModel.runSelfTest(); screen = Screen.SELF_TEST },
            onRegister = { viewModel.registerDevice() },
            onToggleService = { viewModel.toggleService() },
            onCheckStatus = { viewModel.checkStatusNow() }
        )

        Screen.SETTINGS -> SettingsScreen(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBack = { screen = Screen.HOME },
            onOpenQrConfig = { screen = Screen.QR_CONFIG }
        )

        Screen.QUEUE -> QueueScreen(
            state = state,
            viewModel = viewModel,
            onBack = { screen = Screen.HOME }
        )

        Screen.SERVER_SMS -> ServerSmsScreen(
            state = state,
            viewModel = viewModel,
            onBack = { screen = Screen.HOME }
        )

        Screen.SELF_TEST -> SelfTestScreen(
            state = state,
            viewModel = viewModel,
            onBack = { screen = Screen.HOME }
        )

        Screen.QR_CONFIG -> QrConfigScreen(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBack = { screen = Screen.SETTINGS }
        )
    }
}

// ==================== 主页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: DashboardState,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit,
    onOpenSelfTest: () -> Unit,
    onRegister: () -> Unit,
    onToggleService: () -> Unit,
    onCheckStatus: () -> Unit
) {
    val checks = rememberDeviceChecks()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("短信网关", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSelfTest) {
                        Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "自检", tint = Color.White)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顺序即优先级：最可能让人「什么也没发生」的问题排在最上面
            if (!checks.smsPermission) {
                PermissionBanner()
            }

            if (state.isDisabled) {
                DisabledBanner(onCheckStatus = onCheckStatus, testResult = state.testResult)
            }

            if (!checks.ignoringBatteryOptimizations) {
                BatteryBanner()
            }

            if (!state.isRegistered) {
                RegistrationGuideCard(
                    isRegistering = state.isRegistering,
                    onRegister = onRegister
                )
            }

            StatusCard(
                state = state,
                onOpenQueue = onOpenQueue,
                onOpenServerSms = onOpenServerSms
            )

            Button(
                onClick = onToggleService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                // 未注册不许启动。**被禁用时仍然允许启动** —— 心跳是设备唯一能发现自己
                // 被恢复的通道，禁掉它就会造出「不可启动 → 不轮询 → 永远学不到已恢复」的死锁。
                enabled = state.isRegistered || state.isRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning) Color(0xFFF44336) else Color(0xFF1976D2)
                )
            ) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (state.isRunning) "停止网关" else "启动网关", fontSize = 16.sp)
            }

            if (!state.isRegistered) {
                Text(
                    text = "设备注册成功后才能启动网关。",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PermissionBanner() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, tint = Color(0xFFC62828), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("缺少短信权限", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
            }
            Text(
                text = "没有短信权限，App 收不到任何短信，也不会采集到任何验证码。",
                fontSize = 13.sp,
                color = Color(0xFF7F1D1D)
            )
            OutlinedButton(
                onClick = { openAppDetailsSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("去系统设置开启") }
        }
    }
}

@Composable
private fun DisabledBanner(onCheckStatus: () -> Unit, testResult: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEDE7F6))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, tint = Color(0xFF5E35B1), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("已被管理员禁用", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4527A0))
            }
            Text(
                text = "服务端拒绝接收本机的短信，短信会留在本地等待恢复。",
                fontSize = 13.sp,
                color = Color(0xFF4A148C)
            )
            // 这个按钮是必需的逃生口：禁用状态下若服务也是停的，就没人轮询，
            // 设备永远学不到自己已被恢复。
            OutlinedButton(onClick = onCheckStatus, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("检查状态")
            }
            testResult?.let {
                Text(text = it, fontSize = 12.sp, color = Color(0xFF4A148C))
            }
        }
    }
}

@Composable
private fun BatteryBanner() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BatteryAlert, null, tint = Color(0xFFF57C00), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("未加入电池优化白名单", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
            }
            Text(
                text = "系统随时可能杀掉后台服务，届时短信将不再上报，且不会有任何提示。",
                fontSize = 13.sp,
                color = Color(0xFF6D4C41)
            )
            OutlinedButton(
                onClick = { openBatterySettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("加入白名单") }
        }
    }
}

@Composable
private fun RegistrationGuideCard(isRegistering: Boolean, onRegister: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = Color(0xFF1976D2), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("设备未注册", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
            }
            Text("请先注册设备才能连接服务器。", fontSize = 14.sp, color = Color(0xFF1565C0))

            Button(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRegistering,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.AppRegistration, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRegistering) "注册中…" else "立即注册设备")
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: DashboardState,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit
) {
    // 后端判离线的阈值是 90 秒，超过就标红，免得盯着一个旧时间戳以为一切正常
    val now = System.currentTimeMillis()
    val heartbeatStale = state.lastHeartbeatAt?.let { now - it > HEARTBEAT_STALE_MS } ?: false
    val server = serverStatus(state, now)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.isRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (state.isRunning) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (state.isRunning) Color(0xFF4CAF50) else Color(0xFFF44336),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.isRunning) "● 网关运行中" else "○ 网关已停止",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.isRunning) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
            }

            HorizontalDivider()

            // 「未设置」这类展示文案留在界面层，state 里存的是原始空串
            StatusRow(label = "设备", value = state.deviceId.abbreviateId())
            StatusRow(label = "手机号", value = state.phone.ifBlank { "未设置" })
            StatusRow(
                label = "服务器",
                value = server.text,
                valueColor = if (server.problem) Color(0xFFC62828) else null
            )
            StatusRow(
                label = "最后心跳",
                value = formatRelative(state.lastHeartbeatAt, now),
                valueColor = if (heartbeatStale) Color(0xFFC62828) else null
            )

            // 这两行可点进详情页：一个看还没传上去的（本地队列），一个看已经传上去的（服务端判定）
            StatusRow(
                label = "待上传",
                value = "${state.pendingCount}",
                valueColor = if (state.pendingCount > 0) Color(0xFFF57C00) else null,
                onClick = onOpenQueue
            )
            StatusRow(
                label = "今日短信",
                value = "${state.todaySmsCount}",
                onClick = onOpenServerSms
            )
            StatusRow(
                label = "今日验证码",
                value = "${state.todayCodeCount}",
                onClick = onOpenServerSms
            )
        }
    }
}

// ==================== 设置页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onOpenQrConfig: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var serverUrlInput by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var phoneInput by remember(state.phone) { mutableStateOf(state.phone) }
    var deviceNameInput by remember(state.deviceName) { mutableStateOf(state.deviceName) }
    var showReregisterDialog by remember { mutableStateOf(false) }

    val sims = remember { DevicePhone.listSlots(context) }
    var showSimPicker by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard(title = "服务器地址") {
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("http://192.168.1.100:8080") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.updateServerUrl(serverUrlInput)
                            focusManager.clearFocus()
                            toast("服务器地址已保存")
                        },
                        modifier = Modifier.weight(1f),
                        enabled = serverUrlInput.isNotBlank()
                    ) { Text("保存") }

                    OutlinedButton(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.testConnection(serverUrlInput)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = serverUrlInput.isNotBlank()
                    ) { Text("测试连接") }
                }
                state.testResult?.let {
                    Text(text = it, fontSize = 13.sp, color = Color.Gray)
                }

                OutlinedButton(
                    onClick = onOpenQrConfig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("配置二维码")
                }
            }

            SettingsCard(title = "设备信息") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(text = "设备 ID", fontSize = 14.sp, color = Color.Gray)
                    SelectionContainer {
                        Text(
                            text = state.deviceId.ifBlank { "未设置" },
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                OutlinedTextField(
                    value = deviceNameInput,
                    onValueChange = { deviceNameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("设备名称") },
                    placeholder = { Text("留空则用机型名") }
                )
                OutlinedButton(
                    onClick = {
                        viewModel.updateDeviceName(deviceNameInput)
                        focusManager.clearFocus()
                        toast("设备名称已保存")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存设备名称") }

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

                if (sims.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showSimPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("从 SIM 卡读取（共 ${sims.size} 张）") }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.updatePhone(phoneInput)
                        focusManager.clearFocus()
                        toast("手机号已保存")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存手机号") }

                StatusRow(label = "注册状态", value = if (state.isRegistered) "已注册" else "未注册")
                if (state.isDisabled) {
                    StatusRow(label = "设备状态", value = "已被管理员禁用")
                }

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

            SettingsCard(title = "数据") {
                OutlinedButton(
                    onClick = {
                        viewModel.clearUploadedRecords()
                        toast("已清理本地已上传记录")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清理本地已上传记录") }
                Text(
                    text = "只删本地已上传成功的历史，不影响服务端数据。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            SettingsCard(title = "关于") {
                StatusRow(label = "应用版本", value = BuildConfig.VERSION_NAME)
            }
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
                                Text(sim.label, fontSize = 14.sp)
                                Text(
                                    text = sim.number ?: "读不到号码",
                                    fontSize = 12.sp,
                                    color = Color.Gray
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

// ==================== 队列页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.refreshQueue() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待上传（${state.queue.size}）", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshQueue() }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        if (state.queue.isEmpty()) {
            EmptyState("没有待上传的短信", Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.queue.forEach { row ->
                QueueRow(
                    row = row,
                    onRetry = { viewModel.retrySms(row.id) },
                    onDelete = { viewModel.deleteSms(row.id) }
                )
            }
        }
    }
}

@Composable
private fun QueueRow(row: SmsQueueEntity, onRetry: () -> Unit, onDelete: () -> Unit) {
    val failed = row.status == "failed"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) Color(0xFFFFEBEE) else Color(0xFFF5F5F5)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(row.sender, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = if (failed) "已被服务端拒绝" else "待重试",
                    fontSize = 12.sp,
                    color = if (failed) Color(0xFFC62828) else Color(0xFFF57C00)
                )
            }

            Text(row.content, fontSize = 13.sp, maxLines = 3)
            row.code.takeIf { it.isNotBlank() }?.let {
                Text("验证码：$it", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            Text(
                text = buildString {
                    append("第 ${row.retryCount} 次重试")
                    val remaining = row.nextRetryAt - System.currentTimeMillis()
                    if (!failed && remaining > 0) {
                        append("，")
                        append(formatCountdown(remaining))
                    }
                },
                fontSize = 12.sp,
                color = Color.Gray
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Text(if (failed) "重新排队" else "立即重试")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("删除")
                }
            }
        }
    }
}

// ==================== 服务端记录页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSmsScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务端记录", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadServerSms() }) {
                        Icon(Icons.Default.Refresh, "刷新", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        when {
            state.smsLoading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            state.smsError != null -> EmptyState(state.smsError, Modifier.padding(padding))

            state.smsRecords.isEmpty() -> EmptyState("服务端还没有记录", Modifier.padding(padding))

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                state.smsRecords.forEach { ServerSmsRow(it) }
            }
        }
    }
}

@Composable
private fun ServerSmsRow(record: SmsRecord) {
    // 服务端的判定：这是本地数据看不到的信息
    val (statusLabel, statusColor) = when (record.status?.uppercase()) {
        "RECEIVED" -> "已收下" to Color(0xFF2E7D32)
        "DUPLICATE" -> "内容重复" to Color(0xFFF57C00)
        "IGNORED" -> "被规则忽略" to Color(0xFF757575)
        else -> (record.status ?: "未知") to Color.Gray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(record.sender ?: "未知发送方", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(statusLabel, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
            }
            Text(record.content ?: "", fontSize = 13.sp, maxLines = 3)
            record.code?.takeIf { it.isNotBlank() }?.let {
                Text("验证码：$it", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            record.receiveTime?.let {
                Text(it.replace('T', ' ').take(19), fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

// ==================== 自检页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfTestScreen(
    state: DashboardState,
    viewModel: DashboardViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自检", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.runSelfTest() },
                        enabled = !state.selfTestRunning
                    ) {
                        Icon(Icons.Default.Refresh, "重新自检", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.selfTestRunning && state.selfTest.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.selfTest.forEach { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (item.ok) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (item.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (item.ok) Color(0xFF4CAF50) else Color(0xFFF44336),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(item.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(item.detail, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

// ==================== 复用组件与工具 ====================

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 14.sp, color = Color.Gray)
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = Color.Gray)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = valueColor ?: Color.Unspecified
            )
            // 有下一级页面的行才显示箭头，让「可点」这件事看得出来
            if (onClick != null) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ==================== 设备状态检查 ====================

private data class DeviceChecks(
    val smsPermission: Boolean,
    val ignoringBatteryOptimizations: Boolean
) {
    companion object {
        fun read(context: Context) = DeviceChecks(
            smsPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED,
            ignoringBatteryOptimizations = (context.getSystemService(Context.POWER_SERVICE)
                as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        )
    }
}

/**
 * 权限与电池白名单只能在系统设置里改，改完回到 App 时需要重算。
 * 用生命周期观察者在 ON_RESUME 时重新读取，而不是把这些状态塞进 ViewModel ——
 * 它们是系统状态，不是应用状态，没有可订阅的变更源。
 */
@Composable
private fun rememberDeviceChecks(): DeviceChecks {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var checks by remember { mutableStateOf(DeviceChecks.read(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checks = DeviceChecks.read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return checks
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    // 该 action 需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限，部分 ROM 会拒绝；
    // 失败就退回应用详情页，那里同样能手动关掉电池优化。
    runCatching { context.startActivity(intent) }
        .onFailure { openAppDetailsSettings(context) }
}

private fun openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
    runCatching { context.startActivity(intent) }
}

/** 后端以 90 秒为判离线阈值，这里跟着它走。 */
private const val HEARTBEAT_STALE_MS = 90_000L

private data class ServerStatus(val text: String, val problem: Boolean)

/**
 * 服务器连接状态，**由实时证据推导**。
 *
 * 早先它是 DashboardState 里一个存储字段，只在注册请求里被写过、也不持久化，
 * 结果每次冷启动都退回「未连接」——哪怕设备已注册、服务在跑、心跳正常，
 * 而它下面一行的「最后心跳」是实时的，两行自相矛盾。
 *
 * 真正能说明连接状况的是：有没有注册、服务在不在跑、最近一次心跳距今多久。
 */
private fun serverStatus(state: DashboardState, now: Long): ServerStatus = when {
    state.isDisabled -> ServerStatus("已被禁用", true)
    !state.isRegistered -> ServerStatus("未注册", false)
    !state.isRunning -> ServerStatus("未运行", false)
    state.lastHeartbeatAt == null -> ServerStatus("等待首次心跳", false)
    now - state.lastHeartbeatAt <= HEARTBEAT_STALE_MS -> ServerStatus("已连接", false)
    else -> ServerStatus("连接中断", true)
}

/** 状态卡片一行放不下 36 字符的 UUID，显示前 8 位即可辨认；完整值在设置页。 */
private fun String.abbreviateId(): String =
    if (isBlank()) "未设置" else if (length <= 12) this else "${take(8)}…"

/**
 * 相对时间。后端以 90 秒为判离线阈值，所以这里到分钟级就够，
 * 不必引入 android.text.format.DateUtils。
 */
private fun formatRelative(at: Long?, now: Long): String {
    if (at == null || at <= 0L) return "无"
    val deltaSeconds = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        deltaSeconds < 5 -> "刚刚"
        deltaSeconds < 60 -> "$deltaSeconds 秒前"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} 分钟前"
        else -> "${deltaSeconds / 3600} 小时前"
    }
}

/** 队列页显示「还有多久重试」，与「多久之前」方向相反，所以单独一个函数。 */
private fun formatCountdown(remainingMs: Long): String {
    val seconds = (remainingMs / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds 秒后"
        seconds < 3600 -> "${seconds / 60} 分钟后"
        else -> "${seconds / 3600} 小时后"
    }
}
