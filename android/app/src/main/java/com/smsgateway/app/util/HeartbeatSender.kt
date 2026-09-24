package com.smsgateway.app.util

import android.content.Context
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.model.CommandPayload
import com.smsgateway.app.model.HeartbeatRequest
import com.smsgateway.app.model.OutboundPayload
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.outbound.SmsSender
import com.smsgateway.app.service.CommandExecutor
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 心跳的唯一实现。
 *
 * 之所以抽出来而不是只放在前台服务里：被管理员禁用后，服务可能是停止的，
 * 此时界面需要一个「检查状态」按钮发一次性心跳来发现「已恢复」——否则
 * 「不可启动 → 不轮询 → 永远学不到已恢复」是个死锁。
 *
 * 心跳**不携带禁用判断**：后端对禁用的设备放行心跳，设备正是从这条正常响应里
 * 学到自己被禁用了。上传接口才用 403 拒绝。
 */
object HeartbeatSender {

    private const val TAG = "HeartbeatSender"

    /** 最近一次心跳成功的时间（epoch 毫秒）。存时间戳，相对时间由界面自己算。 */
    private val _lastSuccessAt = MutableStateFlow<Long?>(null)
    val lastSuccessAt: StateFlow<Long?> = _lastSuccessAt.asStateFlow()

    @Volatile
    private var hydrated = false

    /**
     * 连续失败次数。
     *
     * 心跳 30 秒一次，逐次记日志会让断网几分钟就把整页刷满，而真正重要的事件被埋掉。
     * 更关键的是「刚断 30 秒」和「已经断了十分钟」在排查时是完全不同的事 ——
     * 所以只在**连续失败跨过阈值**时记一条，恢复时再记一条形成闭环。
     *
     * 「未注册」不算失败：那是稳态，不是故障，记它只会制造噪音。
     */
    private var failStreak = 0

    /**
     * 这一轮连续失败是否已经记过。
     *
     * <p>用它而不是只看计数：{@link #send} 会被服务心跳循环与界面上的「检查状态」
     * 并发调用，而 `failStreak++` 不是原子操作 —— 丢一次自增就会让计数从 2 跳到 4，
     * 于是「恰好等于 3」那种判定永远不成立，事件一条都记不下来。
     * 改成「跨过阈值且还没记过」，丢几次自增也不影响。
     */
    private var failureReported = false

    /** 3 次 ≈ 90 秒。跨过它才说明这不是一次抖动，而是真的连不上。 */
    private const val FAIL_STREAK_THRESHOLD = 3

    private fun recordHeartbeatFailure(context: Context, reason: String) {
        failStreak++
        if (!failureReported && failStreak >= FAIL_STREAK_THRESHOLD) {
            failureReported = true
            EventLog.write(
                context, EventLog.HEARTBEAT_FAILED, EventLog.LEVEL_WARN,
                reason = "连续 $failStreak 次失败（$reason）"
            )
        }
    }

    private fun recordHeartbeatSuccess(context: Context) {
        // 判 failureReported 而不是计数：只有真的记过「中断」才需要一条「恢复」，
        // 否则偶发丢一次自增就会凭空冒出一条恢复事件。
        if (failureReported) {
            failureReported = false
            EventLog.write(
                context, EventLog.HEARTBEAT_RECOVERED, EventLog.LEVEL_INFO,
                reason = "连续失败 $failStreak 次后恢复"
            )
        }
        failStreak = 0
    }

    /**
     * 首次访问时从 prefs 水合上一次的心跳时间。幂等，可从任意线程调用。
     *
     * 与 [GatewayState.ensureLoaded] 同一套写法、同一个理由：这个时间戳原本只活在
     * 内存里，进程被杀就归零，界面于是在重开 App 时显示「服务刚起来，正在连服务器」——
     * 设备到底是刚启动、还是已经断了十分钟，从这句话里看不出来。
     */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (hydrated) return
        _lastSuccessAt.value = DevicePrefs.lastHeartbeatAt(context.applicationContext)
        hydrated = true
    }

    /**
     * @param probe 这是一次「网关已停止」时的低频探测心跳，不是常规心跳。
     *
     *   **它只做两件事：取回指令、学一下自己是否被禁用。** 在线状态、心跳时间、
     *   遥测字段全部不动 —— 服务端那边同样跳过写入（见 DeviceService.heartbeat）。
     *   两个理由：一来用户停了网关，后台就不该再显示在线；二来「上次心跳时间」
     *   是界面上判断设备活没活着的依据，一个不存在的探测不该把它顶成「刚刚」。
     *
     *   失败也不计入 [failStreak]：网关停着时连不上服务器是常态（他可能就是为此停的），
     *   每 15 分钟记一条「心跳中断」只会把真事件埋掉。
     *
     * @return 是否成功（HTTP 2xx）。未注册或网络失败返回 false。
     */
    suspend fun send(context: Context, probe: Boolean = false): Boolean {
        val app = context.applicationContext

        val deviceId = DevicePrefs.deviceId(app)
        if (deviceId.isBlank() || DevicePrefs.deviceToken(app).isBlank()) {
            Log.w(TAG, "Skip heartbeat: device not registered yet")
            return false
        }

        // 每次都按已保存配置做一次幂等装配：这样在设置里改了服务器地址后，
        // 下一轮心跳就会打到新地址，不必等服务重启。
        RetrofitClient.ensureConfigured(app)

        // 探测心跳不读待上传数：服务端压根不会用它（那时的遥测不可信），
        // 而这是一次多余的库查询 —— 探测每 15 分钟就跑一次，没必要。
        val pendingCount = if (probe) {
            null
        } else {
            AppDatabase.getInstance(app).smsQueueDao().getOutstandingCountSync()
        }

        val request = HeartbeatRequest(
            deviceId = deviceId,
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            // 为空时 Gson 省略该字段，后端只在字段存在时才更新，不会把已存值覆盖成空。
            phone = DevicePrefs.phone(app).ifBlank { null },
            // 名字跟随手机本身，读不到也有厂商+机型兜底，不会是空值 ——
            // 空值会被 Gson 省略掉，服务端只会保留旧名字，那是两台重名的来源之一
            deviceName = DeviceName.read(app),
            battery = if (probe) null else DeviceTelemetry.batteryLevel(app),
            network = if (probe) null else DeviceTelemetry.networkType(app),
            charging = if (probe) null else DeviceTelemetry.isCharging(app),
            pendingCount = pendingCount,
            commandProbe = if (probe) true else null,
            // 发出去了但还没上报的结果。**每一次心跳都带上** —— 设备不知道服务端
            // 收没收到，而那条结果的送达窗口只有一次（服务端只下发一次）。
            // 空时不传（Gson 省略字段），服务端照常处理。
            outboundResults = OutboundResultStore.pending(app).ifEmpty { null }
        )

        // 发出去之前把当前令牌记下来：401 时要用它去比对「被拒的是不是现在这一份」。
        // 收到 401 之后再读是不行的 —— 那中间可能已经有别的路径（重新注册）换过令牌，
        // 于是这条迟到的 401 会把刚存下的新令牌删掉。
        val authToken = RetrofitClient.getDeviceToken()

        return try {
            val response = RetrofitClient.getApiService().heartbeat(request)
            if (!response.isSuccessful) {
                if (response.code() == 401) {
                    // 令牌被拒 = 服务端不认这台设备了（设备记录被删、或换了主密钥）。
                    // 必须清掉本地令牌，否则 isRegistered 永远是 true，界面一直显示
                    // 「已注册」而实际一条也传不上去，现场根本想不到要重新注册。
                    //
                    // 注：AuthInterceptor 其实已经先一步报过一次了（401 是它先看到的），
                    // 这里是第二条路径，两者走同一个判据，重复调用是幂等的。
                    //
                    // 探测心跳也要照做：一台已被服务端删掉的设备，探测同样拿不到指令，
                    // 而不清令牌它永远停在「已注册」。
                    AuthState.markTokenRejected(app, authToken)
                }
                Log.w(TAG, "Heartbeat rejected: HTTP ${response.code()}")
                if (!probe) {
                    recordHeartbeatFailure(app, "HTTP ${response.code()}")
                }
                return false
            }

            val data = response.body()?.data

            // 状态照常应用：被禁用的设备靠它发现自己被恢复，而探测心跳是网关停着时
            // 唯一还在跑的通道 —— 少了这一步，一台被禁用又被恢复的设备在停机期间
            // 学不到任何变化。
            applyServerStatus(app, data?.status)

            // 指令交给执行器，**不在这里等它跑完**。
            //
            // 这是一条硬性约束，不是风格问题：STOP_GATEWAY 会 stopSelf() 把这个服务
            // 连同它的协程一起结束。同步等待等于让心跳循环卡在一条正在停掉自己的指令上，
            // 而那条指令永远收不到回执（服务没了），服务端于是无限重发。
            dispatchCommands(app, data?.commands, probe)

            // 外发短信同理，也是 fire-and-forget：发一条要等无线电，不能让心跳循环
            // 挂着等它（下一步还要发心跳）。结果由 SmsSentReceiver 收齐后落本地，
            // 下一次心跳带上去。
            dispatchOutbound(app, data?.outbound, probe)

            if (!probe) {
                val at = System.currentTimeMillis()
                DevicePrefs.setLastHeartbeatAt(app, at)
                _lastSuccessAt.value = at
                recordHeartbeatSuccess(app)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed", e)
            if (!probe) {
                recordHeartbeatFailure(app, e.javaClass.simpleName)
            }
            false
        }
    }

    /**
     * 把下发的指令交给执行器。
     *
     * 用自己那个作用域而不是调用方的作用域：调用方可能是前台服务的 `serviceScope`，
     * 而一条 `STOP_GATEWAY` 会把那个作用域整个取消（服务 stopSelf）——
     * 于是指令执行到一半就被掐掉，连回执都发不出去。
     */
    private fun dispatchCommands(context: Context, commands: List<CommandPayload>?, probe: Boolean) {
        if (commands.isNullOrEmpty()) return

        val app = context.applicationContext
        sideEffectScope.launch {
            try {
                CommandExecutor.handle(app, commands, probeOnly = probe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 指令处理是旁路：它出问题不该让心跳这条唯一的连接也断掉。
                Log.w(TAG, "Command handling failed", e)
            }
        }
    }

    /**
     * 把服务端交给本机发的短信真的发出去。
     *
     * 与指令一样 fire-and-forget（见 [dispatchCommands] 里那段理由），但**多一条约束**：
     * 发一条短信要等无线电、可能几秒，而心跳循环下一步还要发心跳 —— 同步等它会把
     * 心跳周期拖长，而心跳是设备与服务器之间唯一还活着的连接。
     *
     * 逐条串行发：几条同时交给无线电只会互相排队，而串行能让本地事件表的顺序
     * 与真实顺序一致（排查时用得上）。
     */
    private fun dispatchOutbound(context: Context, outbound: List<OutboundPayload>?, probe: Boolean) {
        if (outbound.isNullOrEmpty()) {
            return
        }
        // 第二道闸：探测心跳（网关已停止）不该发短信 —— 用户停网关的意图就是
        // 「这台机器冻结住」，那时让它发短信（要计费、对方会收到）是违背这个意图的。
        // 服务端不会带，但这里再挡一次：这条约束的代价不对称。
        if (probe) {
            Log.w(TAG, "探测心跳带着外发短信，已忽略")
            return
        }

        val app = context.applicationContext
        sideEffectScope.launch {
            for (payload in outbound) {
                try {
                    // 立刻就知道的失败（没权限、没卡）要落本地，等下一次心跳上报；
                    // 成功交给无线电的返回 null，结果由 SmsSentReceiver 收。
                    val immediate = SmsSender.send(app, payload)
                    if (immediate != null) {
                        OutboundResultStore.record(
                            app,
                            OutboundResultStore.Entry(
                                key = immediate.key,
                                status = immediate.status,
                                errorReason = immediate.errorReason,
                                segments = immediate.segments
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单条失败不影响后面几条
                    Log.w(TAG, "发送外发短信失败：key=${payload.key}", e)
                }
            }
        }
    }

    /** 与 [EventLog] 的 writeScope 同一套写法：调用方不阻塞、异常不外溢。 */
    private val sideEffectScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 服务端是设备状态的唯一权威：它说 DISABLED 就置为禁用，说别的就恢复。
     *
     * 从禁用恢复的那一刻把积压的短信重新排进上传队列 —— 这就是重新启用的补传机制，
     * 不需要额外定时器，那些行一直是 pending。
     */
    private fun applyServerStatus(context: Context, status: String?) {
        if (status.isNullOrBlank()) return

        val disabled = status.equals("DISABLED", ignoreCase = true)
        val wasDisabled = DeviceStatus.isDisabled(context)

        DeviceStatus.set(context, disabled)

        if (wasDisabled && !disabled) {
            Log.i(TAG, "Device re-enabled, flushing parked queue")
            SmsUploadWorker.enqueue(context)
        }
    }
}
