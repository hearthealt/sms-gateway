package com.smsgateway.app.util

import android.content.Context
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.model.HeartbeatRequest
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** @return 是否成功（HTTP 2xx）。未注册或网络失败返回 false。 */
    suspend fun send(context: Context): Boolean {
        val app = context.applicationContext

        val deviceId = DevicePrefs.deviceId(app)
        if (deviceId.isBlank() || DevicePrefs.deviceToken(app).isBlank()) {
            Log.w(TAG, "Skip heartbeat: device not registered yet")
            return false
        }

        // 每次都按已保存配置做一次幂等装配：这样在设置里改了服务器地址后，
        // 下一轮心跳就会打到新地址，不必等服务重启。
        RetrofitClient.ensureConfigured(app)

        val pendingCount = AppDatabase.getInstance(app).smsQueueDao().getOutstandingCountSync()

        val request = HeartbeatRequest(
            deviceId = deviceId,
            timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            // 为空时 Gson 省略该字段，后端只在字段存在时才更新，不会把已存值覆盖成空。
            phone = DevicePrefs.phone(app).ifBlank { null },
            // 名字跟随手机本身，读不到也有厂商+机型兜底，不会是空值 ——
            // 空值会被 Gson 省略掉，服务端只会保留旧名字，那是两台重名的来源之一
            deviceName = DeviceName.read(app),
            battery = DeviceTelemetry.batteryLevel(app),
            network = DeviceTelemetry.networkType(app),
            charging = DeviceTelemetry.isCharging(app),
            pendingCount = pendingCount
        )

        return try {
            val response = RetrofitClient.getApiService().heartbeat(request)
            if (!response.isSuccessful) {
                if (response.code() == 401) {
                    // 令牌被拒 = 服务端不认这台设备了（设备记录被删、或换了主密钥）。
                    // 必须清掉本地令牌，否则 isRegistered 永远是 true，界面一直显示
                    // 「已注册」而实际一条也传不上去，现场根本想不到要重新注册。
                    AuthState.markTokenRejected(app)
                }
                Log.w(TAG, "Heartbeat rejected: HTTP ${response.code()}")
                return false
            }

            applyServerStatus(app, response.body()?.data?.status)
            _lastSuccessAt.value = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat failed", e)
            false
        }
    }

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
