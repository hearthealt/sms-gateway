package com.smsgateway.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.smsgateway.app.MainActivity
import com.smsgateway.app.R
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.HeartbeatSender
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GatewayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "sms_gateway_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "GatewayService"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        /** 被禁用时降频：这个状态下没有时间敏感的事，但必须保持轮询才能发现「已恢复」。 */
        private const val DISABLED_HEARTBEAT_INTERVAL_MS = 60_000L

        /**
         * 事件日志剪枝的间隔。心跳循环本身 30 秒一轮，但删 7 天前的记录一小时做一次绰绰有余 ——
         * 多删几次只是白耗电，而少删几次也不会让表涨到哪去。
         */
        private const val EVENT_LOG_PRUNE_INTERVAL_MS = 60 * 60 * 1000L

        /**
         * 当前活着的服务实例，用来把运行态绑定到**实例**而不是某个回调。
         *
         * 只有销毁的正是这个实例时才允许把运行态清成 false：否则一个迟到的
         * onDestroy（旧实例）会把已经在跑的新实例一起抹成「已停止」，
         * 于是服务在跑、界面却显示停止，而「启动网关」按钮此后永远点不动 ——
         * 服务本来就活着，点它只会补发一次 start，onCreate 不会再执行。
         *
         * 服务回调都在主线程，无需同步。
         */
        private var liveInstance: GatewayForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // 立刻落盘「已停止」，不等 onDestroy：服务本来就没起来时 onDestroy 根本不会
            // 触发，那样用户点了停止、运行态还是 true，界面会卡在「停止网关」切不回来，
            // 而采集侧也会继续按「网关在跑」放行上传。落盘之后 onDestroy 里的
            // set(false) 只是幂等重复。
            GatewayState.set(context, false)

            EventLog.write(
                context, EventLog.GATEWAY_STOPPED, EventLog.LEVEL_INFO,
                reason = "用户主动停止"
            )

            val intent = Intent(context, GatewayForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 心跳循环的存活标志。与 companion 的 isRunning 区分开，后者是给界面看的。 */
    private var serviceAlive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceAlive = true
        liveInstance = this
        // markStarted 而不是 set(true)：这里同时是「本次启动时刻」唯一的写入点。
        // 它只在实例创建时执行一次，正好等于一条命的起点；onStartCommand 会重复来，
        // 放那儿就会被每次启动请求刷新（理由见 GatewayState.markStarted）。
        GatewayState.markStarted(this)

        // 放在 onCreate 而不是 onStartCommand：这里才是「这条命从什么时候开始」的唯一写入点，
        // 与 markStarted 同一个理由（onStartCommand 每次启动请求都会重复来）。
        EventLog.write(
            this, EventLog.GATEWAY_STARTED, EventLog.LEVEL_INFO,
            reason = "前台服务启动"
        )

        // 被「停止网关」拦在本地的那批短信，现在该重新排队了 —— 界面承诺的是
        // 「短信会留在本地，不会上报」，恢复上报的时机就是网关重新跑起来。
        SmsUploadWorker.enqueue(this)

        // 通知与心跳都在这里就开始，而不是只放在 onStartCommand 里。
        //
        // 系统重建服务时，onCreate 与 onStartCommand 之间并不保证是连续的：实测
        // MIUI 的「上滑清理」杀掉进程后重建的那次，onCreate 跑了而 onStartCommand
        // **再也没来**（ServiceRecord 里 startRequested/callStart 都是 true，系统认为
        // 已经交付过，不会重发）。结果是服务对象活着、运行态是 true、通知还挂在被杀
        // 之前那条，但没有任何人在发心跳 —— 后端把这台设备判为离线，而界面显示
        // 「已启动，等待心跳」，点「停止/启动」都不会让它自愈。
        //
        // 服务实例存在就必须是前台服务并持续心跳，这是这个类的不变式，
        // 挂在哪个回调上只是实现细节。
        // 先水合再算文案：开机广播/系统重启拉起进程时，这个类往往是全进程第一个碰
        // 心跳状态的地方，不水合就只会显示「等待首次心跳」——哪怕这台设备已经连了三天。
        HeartbeatSender.ensureLoaded(this)
        startForeground(NOTIFICATION_ID, buildNotification(notificationText()))
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = startHeartbeatLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 用 notificationText() 而不是写死一句「已连接」：这个回调不只是「用户点了启动」
        // 会走，应用每次打开的对账补发（见 DashboardViewModel.ensureGatewayServiceRunning）
        // 也会走一遍。写死的话，一台已被管理员禁用、或服务器根本不可达的设备，
        // 会在每次打开应用的那一瞬间把通知刷成「已连接」，几十秒后才被心跳纠正回来。
        startForeground(NOTIFICATION_ID, buildNotification(notificationText()))

        // 每次收到启动命令都重申一次运行态，而不是只在 onCreate 里置位。
        //
        // 走到这里的路径不止「用户点了启动」：系统的 START_STICKY 重启、
        // 覆盖安装后的 MY_PACKAGE_REPLACED 都会走它，而 onCreate 只在实例**首次**
        // 创建时执行一次。运行态万一被清成了 false（迟到的 onDestroy、界面状态被
        // 旧快照覆盖），此后用户再点「启动网关」也只是给一个已在运行的服务补发
        // start，onCreate 不会再跑 —— 没有这行重申，按钮就是永久失效的，
        // 现场只能强行杀掉应用才能恢复。有了它，任何一次启动请求都能把状态纠正回来。
        liveInstance = this
        GatewayState.set(this, true)

        // 幂等：重复 start 时不要叠加第二个心跳循环；onCreate 已经起过一个了，
        // 这里只是兜底（例如服务对象被复用、或循环已因异常退出）。
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = startHeartbeatLoop()
        }

        return START_STICKY
    }

    private var heartbeatJob: Job? = null

    /** 上次剪枝事件日志的时刻（进程内即可 —— 重启后多剪一次没有任何副作用）。 */
    private var lastEventLogPruneAt = 0L

    /**
     * 事件日志的过期清理。
     *
     * 挂在这里是因为「网关在跑」是这个应用唯一无条件成立的周期路径 ——
     * 上传 worker 只在有短信或网关启动时被排一次，而这台设备可能一整天没有短信。
     * 心跳循环则只要服务活着就一定在转。
     *
     * 时段节流在进程内做：事件剪枝一小时一次足够，不必每 30 秒去删一次库。
     */
    private suspend fun pruneEventLogIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastEventLogPruneAt < EVENT_LOG_PRUNE_INTERVAL_MS) return
        lastEventLogPruneAt = now
        val removed = EventLog.prune(this)
        if (removed > 0) {
            Log.i(TAG, "Pruned $removed event log rows older than ${EventLog.RETENTION_DAYS} days")
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name) + "运行中")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startHeartbeatLoop(): Job = serviceScope.launch {
        while (isActive && serviceAlive) {
            // 整轮包一层：心跳与通知更新里的意外异常不能把循环带走。
            // 循环一旦退出，服务还活着、通知还挂着、运行态还是 true，而**再没有任何人
            // 发心跳** —— 后端判离线，界面却显示「已启动」，现场只会以为是对面服务器的问题。
            // 这类「活着的空壳」比服务直接挂掉更难查，所以宁可把异常吞在这一轮里。
            try {
                // 心跳实现与状态判定都在 HeartbeatSender 里，界面上的「检查状态」按钮走同一份逻辑
                HeartbeatSender.send(this@GatewayForegroundService)
                updateNotification(notificationText())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat round failed", e)
            }

            pruneEventLogIfDue()

            delay(
                if (DeviceStatus.isDisabled(this@GatewayForegroundService)) {
                    DISABLED_HEARTBEAT_INTERVAL_MS
                } else {
                    HEARTBEAT_INTERVAL_MS
                }
            )
        }
    }

    /**
     * 通知文案。被禁用的判断放最前面 —— 此时心跳其实是成功的，
     * 若沿用「心跳 xx:xx」会让现场人员以为一切正常，而短信并不在上传。
     */
    private fun notificationText(): String {
        val at = HeartbeatSender.lastSuccessAt.value
        return when {
            DeviceStatus.isDisabled(this) -> "已被管理员禁用，等待恢复"
            // 「从未成功过」不等于「没注册」：注册好了但服务器一直不可达时也是这个状态，
            // 报「未注册」会把人引到重新注册上去，越修越远
            !DevicePrefs.isRegistered(this) -> "设备未注册"
            at == null -> "等待首次心跳"
            else -> "心跳 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))}"
        }
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceAlive = false

        // 只清自己那一次的运行态：销毁的若不是当前存活实例，说明新实例已经接上了，
        // 这一刀下去会把新实例的运行态一并抹掉。
        if (liveInstance === this) {
            liveInstance = null
            GatewayState.set(this, false)
            // 只记「当前实例被销毁」这一次。系统重建服务时旧实例的 onDestroy 可能迟到，
            // 那种情况下 liveInstance 已经指向新实例，不该记成一次销毁。
            EventLog.write(
                this, EventLog.GATEWAY_DESTROYED, EventLog.LEVEL_WARN,
                reason = "服务被系统销毁"
            )
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.app_name) + "后台服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
