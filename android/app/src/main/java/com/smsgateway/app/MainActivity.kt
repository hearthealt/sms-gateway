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
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.qr.QrConfigScreen
import com.smsgateway.app.ui.AppCard
import com.smsgateway.app.ui.AppColor
import com.smsgateway.app.ui.AppScreen
import com.smsgateway.app.util.DeviceName
import com.smsgateway.app.util.DevicePhone
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让内容画到系统栏底下：首页那条渐变才能一直顶到状态栏，
        // 而不是被一条深灰的横条截断（那是没开沉浸式时最显旧的一处）。
        // 各页的 Scaffold 会自己处理内边距；首页是自己画的头部，见 HomeHeader 的 statusBarsPadding。
        enableEdgeToEdge()

        // 只在首次创建时申请：旋转屏会重建 Activity，不加这个判断就会把权限弹窗
        // 在刚渲染好的界面上再弹一次 —— 用户每转一次屏就被问一次。
        if (savedInstanceState == null) {
            requestRuntimePermissions()
        }

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
            // 没有 READ_SMS：本应用不读系统短信库，只从 SMS_RECEIVED 广播里取消息
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

    LaunchedEffect(state.registerMessage) {
        state.registerMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearRegisterMessage()
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(onOpenSelfTest = onOpenSelfTest, onOpenSettings = onOpenSettings)

            // 内容做成一张顶部圆角的「纸」，压在渐变头部上。
            // 这是参考图里最值得留下的一笔：成本只是一个 Surface + 圆角，辨识度却上来了。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = AppColor.Screen
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                        // 底部让开手势条/导航栏，否则最后一行会被压在下面
                        .navigationBarsPadding()
                        .padding(top = 20.dp, bottom = 24.dp),
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

                    // 自上而下就是优先级：能不能用 → 怎么操作 → 今天干了多少 → 这台是谁
                    HeroStatusCard(state = state)

                    GatewayActionButton(
                        isRunning = state.isRunning,
                        // 未注册不许启动。**被禁用时仍然允许启动** —— 心跳是设备唯一能
                        // 发现自己被恢复的通道，禁掉它就会造出「不可启动 → 不轮询 →
                        // 永远学不到已恢复」的死锁。
                        enabled = state.isRegistered || state.isRunning,
                        onClick = onToggleService
                    )

                    MetricGrid(
                        state = state,
                        onOpenQueue = onOpenQueue,
                        onOpenServerSms = onOpenServerSms
                    )

                    IdentityRow(state = state, onOpenSettings = onOpenSettings)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/**
 * 顶部渐变头部。
 *
 * 只放品牌与应用名，**不放「安全 · 稳定 · 便捷」那类标语** —— 这是内部工具，
 * 现场一天要开十次，那行字占的高度不如留给状态。操作入口（自检、设置）留在这里，
 * 与内容页分开，滚动时不会跟着跑。
 */
@Composable
private fun HomeHeader(onOpenSelfTest: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF0D47A1), Color(0xFF1E88E5)))
            )
            // 开了沉浸式之后得自己让开状态栏，否则标题会被时间、电量压住
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            // 与启动图标同一套意象：信封 + 信号波。
            // 白底蓝标是一个真正的 logo 块，而不是一个默认的 Material 图标 ——
            // 应用图标长什么样、界面里就是什么样，两者对得上才叫品牌。
            Icon(
                painter = painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                tint = Color(0xFF1E88E5),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 取 @string/app_name 而非再写一遍字面量：这个名字改过一次，
        // 当时只改了清单里的 label，标题栏留了旧名，两处不同步就是这么来的。
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenSelfTest) {
            Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "自检", tint = Color.White)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

/**
 * 主操作：一个大圆钮。
 *
 * 圆钮只表达**动作**（动作名写在圆里），状态由它上面那张卡负责 ——
 * 两者刻意分开。把状态色涂到按钮上，会让「红色＝正在运行（点我停）」和
 * 「红色＝出问题了」混成一件事，而这台设备真出问题时恰恰最需要一眼看出来。
 * 按钮的红色只表示「这一下会停掉它」，是破坏性操作的常规语义。
 */
@Composable
private fun GatewayActionButton(isRunning: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // 用动画过渡而不是硬切：开关网关是个有后果的动作，颜色突变会让人觉得"跳了一下"
    val actionColor by animateColorAsState(
        targetValue = if (isRunning) Color(0xFFE53935) else Color(0xFF1E88E5),
        label = "actionColor"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                // 用描边环而不是半透明色块：半透明填充会和页面底色混在一起、边缘发糊，
                // 描边是有明确边界的一圈，投影之下更像一个真正的按钮。
                .border(
                    width = 10.dp,
                    color = if (enabled) actionColor.copy(alpha = 0.14f) else Color(0xFFEEEEEE),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .clip(CircleShape)
                    .background(if (enabled) actionColor else Color(0xFFBDBDBD))
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRunning) "停止网关" else "启动网关",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }

        if (!enabled) {
            Text(
                text = "设备注册成功后才能启动网关",
                fontSize = 12.sp,
                color = Color(0xFF9E9E9E)
            )
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

// ==================== 主页状态区 ====================

/**
 * 最上方那张「一眼判断」的卡：一句话说清现在到底能不能用。
 *
 * 它是整个界面的视觉重心 —— 现场打开这个应用，十次里有九次只想知道这一件事。
 */
@Composable
private fun HeroStatusCard(state: DashboardState) {
    // 只取一次 now：同一帧里分两处算相对时间，会算出两个不同的值
    val now = System.currentTimeMillis()
    val hero = heroStatus(state, now)

    // 状态会变（比如刚启动 → 已连接），颜色跟着渐变过去，比整块突然换色舒服
    val accent by animateColorAsState(targetValue = hero.accent, label = "heroAccent")
    val background by animateColorAsState(targetValue = hero.background, label = "heroBackground")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = background)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = hero.icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = hero.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = hero.subtitle,
                    fontSize = 13.sp,
                    color = accent.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * 三个指标块。数字下面那行小字不是装饰 —— 它回答的是「这个数字意味着什么」。
 *
 * 「今日验证码 0」本身看不出好坏：是真没短信，还是收到了却没识别出来？
 * 后者要去查采集规则，前者什么都不用做 —— 差别全在那行小字里。
 */
/**
 * 三个指标一格排开，中间细分隔线。
 *
 * 数字下面那行小字不是装饰 —— 它回答的是「这个数字意味着什么」：
 * 「今日验证码 0」本身看不出好坏，配上「有短信，未提取到」才知道要去查采集规则，
 * 而「今天还没有收到」说明什么都不用做。差别全在那行小字里。
 */
@Composable
private fun MetricGrid(
    state: DashboardState,
    onOpenQueue: () -> Unit,
    onOpenServerSms: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetricCell(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.CloudUpload,
                value = state.pendingCount,
                label = "待上传",
                // 那行小字只在「这个数字需要解释」时才出现。数字本身已经说清的事
                // （有 6 条就是 6 条）再补一句「点开看明细」，一行里出现两次，纯是噪音 ——
                // 空串仍占一行，三格的对齐不受影响。
                hint = if (state.pendingCount == 0) "全部已上传" else "正在重试",
                // 颜色**只用来报警**，不做身份装饰。
                // 三个数字各染一色（蓝/绿/灰）看着热闹，但橙色的"出事了"就被淹没了；
                // 现在常态一律用同一个墨色，只有真需要处理的那一项才跳出来。
                alert = state.pendingCount > 0,
                onClick = onOpenQueue
            )
            MetricDivider()
            MetricCell(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Forum,
                value = state.todaySmsCount,
                label = "今日短信",
                hint = if (state.todaySmsCount == 0) "今天还没有收到" else "",
                alert = false,
                onClick = onOpenServerSms
            )
            MetricDivider()
            MetricCell(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.VerifiedUser,
                value = state.todayCodeCount,
                label = "今日验证码",
                hint = when {
                    state.todayCodeCount > 0 -> ""
                    // 有短信却一条验证码都没提取出来，这才是真该去查规则的情况
                    state.todaySmsCount > 0 -> "有短信，未提取到"
                    else -> "今天还没有收到"
                },
                alert = state.todayCodeCount == 0 && state.todaySmsCount > 0,
                onClick = onOpenServerSms
            )
        }
    }
}

@Composable
private fun MetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(46.dp)
            .background(AppColor.Divider)
    )
}

@Composable
private fun MetricCell(
    modifier: Modifier,
    icon: ImageVector,
    value: Int,
    label: String,
    hint: String,
    /** 这一项是否需要人去处理。只有它为真时颜色才跳出来。 */
    alert: Boolean,
    onClick: () -> Unit
) {
    val alertColor = Color(0xFFE65100)
    // 数字和提示一起变 —— 只把数字染橙的话，一个橙色的「0」反而更让人费解
    val valueColor = if (alert) alertColor else Color(0xFF263238)
    val iconColor = if (alert) alertColor else Color(0xFF78909C)

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF546E7A)
            )
        }
        Text(
            text = value.toString(),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
        Text(
            text = hint,
            fontSize = 11.sp,
            color = if (alert) alertColor.copy(alpha = 0.75f) else AppColor.InkMuted,
            textAlign = TextAlign.Center,
            lineHeight = 13.sp,
            // minLines：空串也要占住这一行，否则三格的数字会因高度不同而错位
            minLines = 1,
            maxLines = 2
        )
    }
}

/**
 * 设备身份。刻意放在最下面、且不加卡片 —— 这两项配好之后就不会再变，
 * 只需要「需要时找得到」，不需要「每次打开都看见」。点进去是设置页。
 */
@Composable
private fun IdentityRow(state: DashboardState, onOpenSettings: () -> Unit) {
    // 装进卡片而不是直接摊在灰底上：这两行是「一块内容」而不是两行飘着的字，
    // 有边界之后它才和上面几块读起来是一套东西。
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            IdentityLine(
                icon = Icons.Default.Smartphone,
                label = "设备",
                value = state.deviceId.abbreviateId(),
                onClick = onOpenSettings
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 44.dp, end = 14.dp),
                thickness = 1.dp,
                color = AppColor.Divider
            )
            IdentityLine(
                icon = Icons.Default.Phone,
                label = "手机号",
                value = state.phone.ifBlank { "未设置" },
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun IdentityLine(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF90A4AE),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, fontSize = 14.sp, color = Color(0xFF546E7A))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 13.sp, color = Color(0xFF37474F))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Color(0xFFBDBDBD),
            modifier = Modifier.size(18.dp)
        )
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
    var showReregisterDialog by remember { mutableStateOf(false) }

    // 设备名称跟随手机本身，不是本应用的配置项：这里只读展示，改要到手机的
    // 「设置 → 关于手机 → 设备名称」。见 DeviceName。
    val deviceName = rememberDeviceName()

    val sims = remember { DevicePhone.listSlots(context) }
    var showSimPicker by remember { mutableStateOf(false) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
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
                            focusManager.clearFocus()
                            // 只在校验通过、真的存进去之后才说「已保存」。
                            // 原来不看结果一律弹成功，地址敲错也照样报「已保存」。
                            toast(
                                if (viewModel.updateServerUrl(serverUrlInput)) {
                                    "服务器地址已保存"
                                } else {
                                    "地址格式不合法，未保存"
                                }
                            )
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
                        // 探测进行中禁用：连点会起出多个并发探测，谁先回来谁把状态置为结束
                        enabled = serverUrlInput.isNotBlank() && !state.isTestingConnection
                    ) {
                        // 进行中状态就长在按钮自己身上，不另起一行 —— 另起一行会把它下面的
                        // 「配置二维码」按钮顶来顶去。写法同下面「注册中… / 重新注册」那处。
                        if (state.isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (state.isTestingConnection) "测试中…" else "测试连接")
                    }
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
                    if (sims.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showSimPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SimCard, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("读 SIM 卡（${sims.size}）", maxLines = 1)
                        }
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

    AppScreen(
        title = "待上传（${state.queue.size}）",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.refreshQueue() }) {
                Icon(Icons.Default.Refresh, "刷新", tint = Color.White)
            }
        }
    ) { padding ->
        if (state.queue.isEmpty()) {
            EmptyState("没有待上传的短信", Modifier.padding(padding))
            return@AppScreen
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
            // 正常行必须用白色：页面底色已是 #F4F6FA，原先的 #F5F5F5 会糊在底色里看不见边界
            containerColor = if (failed) AppColor.DangerBg else AppColor.Card
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = row.sender,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (failed) "已被服务端拒绝" else "待重试",
                    fontSize = 12.sp,
                    color = if (failed) AppColor.Danger else AppColor.Warning
                )
            }

            Text(
                text = row.content,
                fontSize = 13.sp,
                color = AppColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            row.code.takeIf { it.isNotBlank() }?.let {
                Text("验证码 $it", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColor.Ink)
            }

            // 重试信息与两个操作并成一行。原先两个按钮各占一整行，一条记录要 200dp 上下，
            // 一屏只看得到四五条；现在信息留在左、动作收进右，同屏多看几条。
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    color = AppColor.InkMuted,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(if (failed) "重新排队" else "重试", fontSize = 13.sp)
                }
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("删除", fontSize = 13.sp, color = AppColor.InkSecondary)
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
    AppScreen(
        title = "服务端记录",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.loadServerSms() }) {
                Icon(Icons.Default.Refresh, "刷新", tint = Color.White)
            }
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
        shape = AppColor.BannerShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.sender ?: "未知发送方",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusLabel, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
            }
            Text(
                text = record.content ?: "",
                fontSize = 13.sp,
                color = AppColor.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // 验证码与收到时间并成一行：一行记录省下一行高度
            Row(verticalAlignment = Alignment.CenterVertically) {
                record.code?.takeIf { it.isNotBlank() }?.let {
                    Text("验证码 $it", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AppColor.Ink)
                }
                Spacer(modifier = Modifier.weight(1f))
                record.receiveTime?.let {
                    Text(it.replace('T', ' ').take(19), fontSize = 12.sp, color = AppColor.InkMuted)
                }
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
    AppScreen(
        title = "自检",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { viewModel.runSelfTest() },
                enabled = !state.selfTestRunning
            ) {
                Icon(Icons.Default.Refresh, "重新自检", tint = Color.White)
            }
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

            if (state.selfTest.isNotEmpty()) {
                SelfTestCard(state.selfTest)
            }
        }
    }
}

/**
 * 自检结果：**一张卡 + 细分隔线**，不再是每项一张色块卡。
 *
 * 原先六张绿卡竖排视觉很重，而且整块铺色时反而看不出「哪一项不对劲」——
 * 全部同色，眼睛没有落点。现在状态收在两处：卡片右上角一句总结（几项通过 / 几项未通过），
 * 以及每一项自己的图标与说明文字 —— 有问题的才染红，正常的保持中性。
 */
@Composable
private fun SelfTestCard(items: List<SelfTestItem>) {
    val failed = items.count { !it.ok }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppColor.CardShape,
        colors = CardDefaults.cardColors(containerColor = AppColor.Card)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("自检结果", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AppColor.Ink)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (failed == 0) "${items.size} 项全部通过" else "$failed 项未通过",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (failed == 0) AppColor.Success else AppColor.Danger
                )
            }

            HorizontalDivider(thickness = 1.dp, color = AppColor.Divider)

            items.forEachIndexed { index, item ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 48.dp),
                        thickness = 1.dp,
                        color = AppColor.Divider
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.ok) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (item.ok) AppColor.Success else AppColor.Danger,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(item.label, fontSize = 15.sp, color = AppColor.Ink)
                        Text(
                            text = item.detail,
                            fontSize = 12.sp,
                            color = if (item.ok) AppColor.InkMuted else AppColor.Danger
                        )
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
    AppCard {
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = AppColor.Ink)
        content()
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
 * 设备名称同理，也是系统状态：用户在「设置 → 关于手机 → 设备名称」里改完回到本页，
 * 要看到的是新值。所以和权限、电池白名单一样在 ON_RESUME 时重读，
 * 而不是把它塞进 ViewModel 存一份会过期的副本。
 */
@Composable
private fun rememberDeviceName(): String {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var name by remember { mutableStateOf(DeviceName.read(context)) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                name = DeviceName.read(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return name
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

/**
 * 主界面顶部那句话的判定。
 *
 * 分支顺序就是「谁更该被先说」：被禁用 > 未注册 > 没在跑 > 心跳有问题 > 正常。
 * 前四种都意味着「现在收不到验证码」，只是原因不同 ——
 * 标题给结论，副标题给原因，颜色给严重程度。现场不需要逐行读表格就能判断。
 */
private fun heroStatus(state: DashboardState, now: Long): HeroStatus = when {
    state.isDisabled -> HeroStatus(
        title = "已被管理员禁用",
        subtitle = "心跳仍在跑，管理员恢复后会自动接上",
        accent = Color(0xFF5E35B1),
        background = Color(0xFFEDE7F6),
        icon = Icons.Default.Block
    )

    !state.isRegistered -> HeroStatus(
        title = "未注册",
        subtitle = "先注册设备，否则一条短信也传不上去",
        accent = Color(0xFF1565C0),
        background = Color(0xFFE3F2FD),
        icon = Icons.Default.AppRegistration
    )

    !state.isRunning -> HeroStatus(
        title = "网关已停止",
        subtitle = "短信会留在本地，不会上报",
        accent = Color(0xFFC62828),
        background = Color(0xFFFFEBEE),
        icon = Icons.Default.Cancel
    )

    state.lastHeartbeatAt == null -> HeroStatus(
        title = "已启动，等待心跳",
        subtitle = "服务刚起来，正在连服务器",
        accent = Color(0xFFEF6C00),
        background = Color(0xFFFFF3E0),
        icon = Icons.Default.Sync
    )

    // 阈值与后端判离线的那条对齐（90 秒），否则会出现这边显示正常、
    // 管理后台已标红的两套说法
    now - state.lastHeartbeatAt > HEARTBEAT_STALE_MS -> HeroStatus(
        title = "连接中断",
        subtitle = "最后心跳 ${formatRelative(state.lastHeartbeatAt, now)}",
        accent = Color(0xFFE65100),
        background = Color(0xFFFFF3E0),
        icon = Icons.Default.Warning
    )

    else -> HeroStatus(
        title = "网关运行中",
        subtitle = "已连接 · ${formatRelative(state.lastHeartbeatAt, now)}",
        accent = Color(0xFF2E7D32),
        background = Color(0xFFE8F5E9),
        icon = Icons.Default.CheckCircle
    )
}

private data class HeroStatus(
    val title: String,
    val subtitle: String,
    val accent: Color,
    val background: Color,
    val icon: ImageVector
)

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
