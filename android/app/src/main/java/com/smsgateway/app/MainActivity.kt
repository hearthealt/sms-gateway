package com.smsgateway.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smsgateway.app.qr.QrExportScreen
import com.smsgateway.app.qr.QuickConnectScreen
import com.smsgateway.app.ui.lock.LockScreen
import com.smsgateway.app.ui.screens.eventlog.EventLogScreen
import com.smsgateway.app.ui.screens.home.DeviceChecks
import com.smsgateway.app.ui.screens.home.HomeScreen
import com.smsgateway.app.ui.screens.queue.QueueScreen
import com.smsgateway.app.ui.screens.selftest.SelfTestScreen
import com.smsgateway.app.ui.screens.settings.SettingsScreen
import com.smsgateway.app.ui.screens.sms.ServerSmsScreen
import com.smsgateway.app.ui.theme.AppAnimations
import com.smsgateway.app.ui.theme.AppTheme
import com.smsgateway.app.ui.utils.SystemSettings
import com.smsgateway.app.util.AppLock
import com.smsgateway.app.util.DevicePhone
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100

        /** 从后台回来多久之后重新上锁，见 [backgroundedAt]。 */
        private const val LOCK_AFTER_BACKGROUND_MS = 30_000L
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
        //
        // 锁屏现在也有同一条蓝色顶栏（见 LockScreen），所以这条规则对它同样成立 ——
        // 此前锁屏是一整片浅灰底，白图标压在上面等于看不见。
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        // 旋转、切换深色模式、改系统字号都会重建 Activity，而这三件事都不该把用户
        // 重新锁在外面 —— 上锁的唯一时机是**冷启动**（进程被杀后重建时 savedInstanceState
        // 会是 null，仍然会上锁，那是期望的行为）。
        //
        // 权限同理：不加这个判断，用户每转一次屏就会被弹一次权限框。
        if (savedInstanceState == null) {
            // 启动时检查权限，缺的话先问一次。
            // 不在这里无限循环逼用户开权限 —— 主页上有明显的横幅，用户不会看不见。
            checkAndRequestPermissions()

            // 上锁要赶在第一次组合之前：cold start 时若先渲染出主页再弹锁屏，
            // 那一眼正好把验证码露给路过的人 —— 而这一眼就是要防的东西。
            AppLock.lockIfEnabled(this)
        }

        // 轮询跟随界面生命周期（见 DashboardViewModel.runPolling）。
        //
        // 挂在 Activity 上而不是 ViewModel 的 init 里：ViewModel 是 Activity 级的，
        // 按 Home 键、或在 Android 12+ 上按返回退回桌面时它都还活着，而前台服务让进程
        // 常驻 —— 于是一台被放在后台的手机仍在每 5 秒查一次库、每 30 秒拉一次
        // mySmsStats，与心跳完全重复。repeatOnLifecycle(STARTED) 在界面不可见时把它停掉，
        // 回到前台再从头开始跑（顺带立刻刷一次，用户看到的数字不会是离开那一刻的）。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.runPolling()
            }
        }

        setContent {
            AppTheme {
                val locked by AppLock.locked.collectAsState()
                val activity = this@MainActivity

                /*
                 * 导航状态提在**锁屏之上**。
                 *
                 * 放在 GatewayApp 内部的话，上锁时它整个被移出组合，rememberSaveable
                 * 保存的那份状态随之丢弃 —— 解锁后永远回到主页。而扫码连接页有两个入口、
                 * 返回栈是它唯一的去向依据，丢掉之后从设置页进去的那条路也回不到设置页。
                 *
                 * 顺便也说清了两件事的边界：锁只决定「给不给看」，不决定「看哪一页」。
                 */
                var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
                var backStackRaw by rememberSaveable { mutableStateOf("") }

                if (locked) {
                    LockScreen(
                        canUseBiometric = canUseBiometric,
                        onUnlockWithPin = { pin ->
                            val ok = AppLock.verify(this, pin)
                            if (ok) AppLock.unlock()
                            ok
                        },
                        onUnlockWithBiometric = {
                            AppLock.promptBiometric(
                                activity = activity,
                                onSuccess = {
                                    biometricMessage = null
                                    AppLock.unlock()
                                },
                                onFailure = { biometricMessage = it }
                            )
                        },
                        biometricMessage = biometricMessage,
                        onOpenAppSettings = { SystemSettings.openAppDetails(this) }
                    )
                } else {
                    GatewayApp(
                        viewModel = viewModel,
                        screen = screen,
                        backStackRaw = backStackRaw,
                        onNavigate = { target, stack ->
                            screen = target
                            backStackRaw = stack
                        }
                    )
                }
            }
        }
    }

    /**
     * 从后台回来超过这个时长就重新上锁。
     *
     * 用一个宽松的阈值而不是「一离开就锁」：这个应用会被系统对话框、相机权限页、
     * 电池白名单设置页打断，每次都要求重输 PIN 会让人把锁关掉 —— 而关掉的锁等于没有。
     * 30 秒足够挡住「手机放桌上、有人拿起来看」这种场景。
     */
    private var backgroundedAt = 0L

    private val canUseBiometric: Boolean by lazy { AppLock.canUseBiometric(this) }

    private var biometricMessage: String? by mutableStateOf(null)

    override fun onStop() {
        super.onStop()
        // elapsedRealtime 而不是 currentTimeMillis：后者能被改（用户手改、NTP 校时、
        // 换时区后的自动校正），而这里要算的是一个**时长**。往前调一次系统时间，
        // 差值就变成负数，「超过 30 秒」永远不成立 —— 锁从此再也不会上，而界面上
        // 看不出任何异常。关机才归零，正好等于「这次开机以来过了多久」。
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // 应用锁可能刚在设置页里被打开或关掉，截屏策略跟着它走
        applyScreenshotPolicy()

        if (backgroundedAt > 0 &&
            SystemClock.elapsedRealtime() - backgroundedAt > LOCK_AFTER_BACKGROUND_MS
        ) {
            AppLock.lockIfEnabled(this)
        }
    }

    /**
     * 防截屏：把验证码挡在最近任务的缩略图之外。
     *
     * 挡的是**缩略图**，不是用户的截图功能。这台手机常年摆在工位上，界面停在哪一页
     * 就会以缩略图的形式留在最近任务里 —— 停在服务端记录页（满屏验证码）或导出二维码页
     * （带接入口令）时，任何人拿起来按一下「最近任务」就能看到，**不需要解锁**。
     * 那正是应用锁要防的那个场景，锁上界面却没有锁住这张图，等于漏了一条。
     *
     * 只在**启用应用锁**时才打开：没装锁的设备本来就承诺「谁都能看」，这时把用户的
     * 截图功能一起禁掉是多余的限制（FLAG_SECURE 会同时让截图变成一张黑图）。
     * 所以开关跟着锁走，用户关掉锁就恢复。
     *
     * API 33 起用 [setRecentsScreenshotEnabled]：它只影响缩略图，粒度比 FLAG_SECURE 准。
     * 两条都设是因为 33 以下没有第二个口子。
     */
    private fun applyScreenshotPolicy() {
        val guarded = AppLock.isEnabled(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!guarded)
        }
        if (guarded) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            // 它们同时也是「分辨短信来自哪张卡」的依据：缺了不报错，只是号码会留空。
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

private enum class Screen {
    HOME, SETTINGS, QUEUE, SERVER_SMS, SELF_TEST, QR_EXPORT, QUICK_CONNECT, EVENT_LOG
}

@Composable
private fun GatewayApp(
    viewModel: DashboardViewModel,
    screen: Screen,
    backStackRaw: String,
    /** 一次写出「去哪」与「新的返回栈」。见 [go] 的说明，两者必须一起变。 */
    onNavigate: (Screen, String) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    /*
     * 返回栈：只记「从哪来的」。
     *
     * **为什么不能按页面类型推「上一层」**：扫码连接页有**两个入口** ——
     * 主页顶部的「扫一扫」和设置页的「扫码连接服务器」。按类型推只能二选一，
     * 从另一个入口进去就会返回错页（这正是上一版的做法，从设置页进去按返回会回主页）。
     * 「回哪去」只取决于怎么来的，所以只能记下来。
     *
     * 用逗号拼的枚举名存，而不是存 List<Screen>：rememberSaveable 走 Bundle，
     * String 一定存得下；存 List 要赌它认不认那个具体实现，赌错的表现是
     * 进程被回收后恢复时崩在状态还原上。
     *
     * 最深的路径是 主页 → 设置 → 扫码连接，栈最多两级。
     */
    fun backStack(): List<Screen> = backStackRaw
        .split(',')
        .mapNotNull { name -> Screen.entries.firstOrNull { it.name == name } }

    /** 跳转。**所有导航都必须走它**，否则返回栈就是断的。 */
    fun go(target: Screen) {
        onNavigate(target, (backStack() + screen).joinToString(",") { it.name })
    }

    /**
     * 返回上一层。系统返回键与页面上方那个返回键**都调它**，两边不可能再分叉。
     * 栈空了（在主页）才退出应用 —— 主页那一支由 BackHandler 的 enabled 挡着。
     */
    fun goBack() {
        val stack = backStack()
        onNavigate(stack.lastOrNull() ?: Screen.HOME, stack.dropLast(1).joinToString(",") { it.name })
    }

    BackHandler(enabled = screen != Screen.HOME) { goBack() }

    // 主页那条「扫完码自动回来」的通知弹窗，在这里统一处理。
    // 它来自 QuickConnectScreen 保存配置 / 注册设备时的成功/失败消息。
    //
    // 这个 host 要传给**每一个**子页面（见各自的 snackbarHostState 参数）：
    // 原先只有主页与设置页挂了它，于是停在队列页/记录页/扫码页时这条消息发出去
    // 却没有接收者 —— 消息被消费掉、屏幕上一闪而过的机会都没有。
    LaunchedEffect(state.registerMessage) {
        state.registerMessage?.let {
            scope.launch { snackbarHostState.showSnackbar(it) }
            viewModel.clearRegisterMessage()
        }
    }

    ScreenTransition(screen) { current ->
        when (current) {
            Screen.HOME -> {
                val checks = rememberDeviceChecks()
                HomeScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    checks = checks,
                    onOpenSettings = { go(Screen.SETTINGS) },
                    // 队列页和服务端记录页各自在进入时加载自己的数据（含下拉刷新），
                    // 这里只管跳转 —— 原先由导航代劳，加一个入口就要多记得调一次。
                    onOpenQueue = { go(Screen.QUEUE) },
                    onOpenServerSms = { go(Screen.SERVER_SMS) },
                    onOpenSelfTest = { viewModel.runSelfTest(); go(Screen.SELF_TEST) },
                    onOpenQuickConnect = { go(Screen.QUICK_CONNECT) },
                    onToggleService = { viewModel.toggleService() },
                    onCheckStatus = { viewModel.checkStatusNow() },
                    // 方法引用（挂起函数）而不是 { viewModel.refreshHomeNow() }：
                    // 后者会让 RefreshableScreen 拿到一个立刻返回的 lambda，
                    // 圈在数据还没回来时就被收掉。
                    onRefresh = viewModel::refreshHomeNow
                )
            }

            Screen.SETTINGS -> SettingsScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() },
                onOpenQuickConnect = { go(Screen.QUICK_CONNECT) },
                onOpenQrExport = { go(Screen.QR_EXPORT) },
                onOpenEventLog = { go(Screen.EVENT_LOG) }
            )

            Screen.QUEUE -> QueueScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() }
            )

            Screen.SERVER_SMS -> ServerSmsScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() }
            )

            Screen.SELF_TEST -> SelfTestScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() },
                // 自检里「设备注册」那一项未通过时，它的下一步就是这个页面
                onOpenQuickConnect = { go(Screen.QUICK_CONNECT) }
            )

            Screen.QR_EXPORT -> QrExportScreen(
                state = state,
                onBack = { goBack() }
            )

            // 「回哪去」由返回栈决定，这里一律 goBack()，不再逐页写去向 ——
            // 逐页写的年代，这里和系统返回键分叉过，而且扫码连接页因为有两个入口，
            // 按页面类型推根本推不对。
            Screen.EVENT_LOG -> EventLogScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() }
            )

            Screen.QUICK_CONNECT -> QuickConnectScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onBack = { goBack() }
            )
        }
    }
}

/**
 * 页面之间淡入淡出。
 *
 * 原先是一次**硬切换**：点「设置」的下一帧，主页整块变成设置页，中间没有过渡。
 * 在这台设备上这不只是观感问题 —— 页面进入时会各自发请求、读库、摆骨架，
 * 硬切换让「刚才那一页 → 这一页」之间出现一帧全空的画面（旧页已移除、新页的首帧还没布局），
 * 看起来像闪了一下。淡入把这一帧盖住。
 *
 * 只做透明度、不做位移：这些页面之间的层级关系不是「深入」，而是并列的几个视图
 * （主页 → 队列 / 设置 / 日志），左右滑动会暗示一个并不存在的层级。
 *
 * 一个副作用要知道：过渡期间**新旧两页同时在组合里**（这是 AnimatedContent 的语义）。
 * 各页的 LaunchedEffect(Unit) 因此只在自己那一轮进入时跑一次，不会被对面带动 ——
 * 它们都是按 EnterTransition 进来的新页面，而不是被重新组合的旧页面。
 */
@Composable
private fun ScreenTransition(target: Screen, content: @Composable (Screen) -> Unit) {
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            fadeIn(animationSpec = tween(AppAnimations.DURATION_SHORT)) togetherWith
                fadeOut(animationSpec = tween(AppAnimations.DURATION_SHORT))
        },
        label = "screen"
    ) { screen ->
        content(screen)
    }
}

// ==================== 设备检查 ====================

/**
 * 设备检查结果。封装所有权限和设置检查，集中判定一次。
 *
 * 三项都在这里算、也都在这里重算：它们都依赖运行时权限或系统设置，而权限可能在本页
 * 显示期间才被授予（去系统设置往返一次不会经过 Activity 重建）。
 */
@Composable
private fun rememberDeviceChecks(): DeviceChecks {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun read() = DeviceChecks(
        smsPermission = hasSmsPermission(context),
        phonePermission = DevicePhone.hasPermission(context),
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
    )

    var checks by remember { mutableStateOf(read()) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checks = read()
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
