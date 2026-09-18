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
import com.smsgateway.app.util.HeartbeatSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
         * 服务的真实运行态，供界面展示。
         * 此前界面用的是启动时乐观置位的值且不持久化，旋转屏或进程重启后
         * 会显示「已停止」而服务其实在跑。
         */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, GatewayForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
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
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("已连接"))

        // 幂等：重复 start 时不要叠加第二个心跳循环
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
        _isRunning.value = false
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
