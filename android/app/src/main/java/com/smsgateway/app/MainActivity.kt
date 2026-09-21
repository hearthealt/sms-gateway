package com.smsgateway.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smsgateway.app.qr.QrExportScreen
import com.smsgateway.app.qr.QuickConnectScreen
import com.smsgateway.app.ui.screens.home.DeviceChecks
import com.smsgateway.app.ui.screens.home.HomeScreen
import com.smsgateway.app.ui.screens.queue.QueueScreen
import com.smsgateway.app.ui.screens.selftest.SelfTestScreen
import com.smsgateway.app.ui.screens.settings.SettingsScreen
import com.smsgateway.app.ui.screens.sms.ServerSmsScreen
import com.smsgateway.app.ui.theme.AppTheme
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
        // 而不是从状态栏下面开始。
        //
        // 状态栏图标固定用浅色：顶栏是一条深蓝渐变，深色图标压在上面几乎看不见，
        // 而 enableEdgeToEdge() 默认按系统明暗挑图标颜色 —— 浅色模式下就会挑成深色。
        // 顶栏的底色两种主题下都是深蓝，所以这里显式钉死，不去跟系统走。
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        // 启动时检查权限，缺的话先问一次。
        // 不在这里无限循环逼用户开权限 —— 主页上有明显的红色横幅，用户不会看不见。
        //
        // 只在首次创建时申请：旋转屏会重建 Activity，不加这个判断就会把权限弹窗
        // 在刚渲染好的界面上再弹一次 —— 用户每转一次屏就被问一次。
        if (savedInstanceState == null) {
            checkAndRequestPermissions()
        }

        setContent {
            AppTheme {
                GatewayApp(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 补读点：权限可能刚在上一轮弹窗里被授予，或用户刚在设置页清掉了号码。
        // 已有号码时该方法会直接返回，不会覆盖手填的值。
        //
        // 必须在 onResume 而不能只靠 ViewModel 的 init：init 跑在权限弹窗**之前**，
        // 首次安装时用户在弹窗里授权的那一刻，init 早就过去了，号码会一直读到
        // 下次冷启动为止。
        viewModel.tryAutoFillPhone()
    }

    /**
     * 申请运行时权限。只申请**真的会用到**的三种。
     *
     * 少申请一个的后果不是「功能弱一点」，而是那条路在界面上整块消失、且不报错：
     * 比如漏了 [DevicePhone.requiredPermissions]，设置页的「读 SIM 卡」按钮不会
     * 提示缺失，它只是永远不出现（那个按钮挂在 `if (sims.isNotEmpty())` 下）。
     */
    private fun checkAndRequestPermissions() {
        val permissions = buildList {
            // 短信只从 SMS_RECEIVED 广播里取，不读系统短信库 —— 没有 READ_SMS，
            // 理由见清单里那条注释（多申请一次危险权限，还会触发应用市场的短信权限政策审查）。
            add(Manifest.permission.RECEIVE_SMS)
            // 读 SIM 卡列号与号码自动预填。两个都已在清单里声明，缺的只是申请这一步。
            addAll(DevicePhone.requiredPermissions)
            // Android 13+ 要单独申请通知权限，否则前台服务的通知会被系统静默掉。
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

// ==================== 路由 ====================

private enum class Screen { HOME, SETTINGS, QUEUE, SERVER_SMS, SELF_TEST, QR_EXPORT, QUICK_CONNECT }

@Composable
private fun GatewayApp(viewModel: DashboardViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    // 所有子页面点返回都回主页，主页点返回退出应用。
    BackHandler(enabled = screen != Screen.HOME) {
        screen = Screen.HOME
    }

    // 主页那条「扫完码自动回来」的通知弹窗，在这里统一处理。
    // 它来自 QuickConnectScreen 保存配置 / 注册设备时的成功/失败消息。
    LaunchedEffect(state.registerMessage) {
        state.registerMessage?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearRegisterMessage()
        }
    }

    when (screen) {
        Screen.HOME -> {
            val checks = rememberDeviceChecks()
            HomeScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                checks = checks,
                onOpenSettings = { screen = Screen.SETTINGS },
                // 队列页和服务端记录页各自在进入时加载自己的数据（含下拉刷新），
                // 这里只管跳转 —— 原先由导航代劳，加一个入口就要多记得调一次。
                onOpenQueue = { screen = Screen.QUEUE },
                onOpenServerSms = { screen = Screen.SERVER_SMS },
                onOpenSelfTest = { viewModel.runSelfTest(); screen = Screen.SELF_TEST },
                onOpenQuickConnect = { screen = Screen.QUICK_CONNECT },
                onToggleService = { viewModel.toggleService() },
                onCheckStatus = { viewModel.checkStatusNow() }
            )
        }

        Screen.SETTINGS -> SettingsScreen(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onBack = { screen = Screen.HOME },
            onOpenQuickConnect = { screen = Screen.QUICK_CONNECT },
            onOpenQrExport = { screen = Screen.QR_EXPORT }
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

        Screen.QR_EXPORT -> QrExportScreen(
            state = state,
            onBack = { screen = Screen.SETTINGS }
        )

        // 返回主页而不是设置页：入口在主页顶部，从哪进来的就回哪去。
        Screen.QUICK_CONNECT -> QuickConnectScreen(
            state = state,
            viewModel = viewModel,
            onBack = { screen = Screen.HOME }
        )
    }
}

// ==================== 设备检查 ====================

/**
 * 设备检查结果。封装所有权限和设置检查，集中判定一次。
 */
@Composable
private fun rememberDeviceChecks(): DeviceChecks {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var checks by remember {
        mutableStateOf(
            DeviceChecks(
                smsPermission = hasSmsPermission(context),
                ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
            )
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checks = DeviceChecks(
                    smsPermission = hasSmsPermission(context),
                    ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return checks
}

private fun hasSmsPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}
