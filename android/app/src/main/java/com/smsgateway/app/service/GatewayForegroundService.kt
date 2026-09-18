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
import androidx.core.app.NotificationCompat
import com.smsgateway.app.MainActivity
import com.smsgateway.app.R
import com.smsgateway.app.util.DeviceStatus
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
        GatewayState.set(this, true)

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
        startForeground(NOTIFICATION_ID, buildNotification("已连接"))
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = startHeartbeatLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("已连接"))

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
            // 心跳实现与状态判定都在 HeartbeatSender 里，界面上的「检查状态」按钮走同一份逻辑
            HeartbeatSender.send(this@GatewayForegroundService)
            updateNotification(notificationText())

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
            at == null -> "设备未注册"
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
