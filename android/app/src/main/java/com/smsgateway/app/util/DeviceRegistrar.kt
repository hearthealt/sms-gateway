package com.smsgateway.app.util

import android.content.Context
import android.util.Log
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.model.DeviceInfo
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * 注册与重新注册。**与界面无关**，因为有两个调用方：
 *
 * 1. 设置页 /「快速连接」页的用户操作（经 [com.smsgateway.app.DashboardViewModel]）；
 * 2. 远程指令 `RE_REGISTER`（经 [com.smsgateway.app.service.CommandExecutor]）。
 *
 * 抽出来的理由不是「好看」：CommandExecutor 跑在前台服务或 WorkManager 的协程里，
 * **手上没有 ViewModel**。让它自己再实现一遍注册，等于把「第一次注册」与「重新注册」
 * 的差别、错误分类、落盘顺序这些细节维护两份 —— 而注册是设备身份的唯一入口，
 * 两份实现分叉的后果是「从某条路注册会悄悄多出一台设备」。
 *
 * 边界很清楚：**这里只做「与界面无关的事」** —— 发请求、落盘令牌、补队列身份、
 * 触发补传、记事件。界面状态（`DashboardState`）与服务启动留在 ViewModel 那侧，
 * 因为「注册成功后要不要把网关跑起来」是界面流程的决定，不是注册本身的语义。
 */
object DeviceRegistrar {

    private const val TAG = "DeviceRegistrar"

    /**
     * @param success   是否注册成功
     * @param message   一句给现场看的话（成功也有一句 —— 之前成功时置 null，
     *                  于是点了「重新注册」之后界面什么都不变，现场无从判断成没成）
     * @param deviceId  本次使用的设备标识（先落盘再发请求，所以失败时它也已经持久化了）
     * @param phone     注册时上报的号码，可能为空
     * @param retryable 值不值得用**已保存的地址**再试一次。只有「服务端回了非 2xx」
     *                  那条路是 true —— 与本次变更之前的行为一致，没有顺手改。
     * @param detail    **给服务端看的**一句话，严格受控（`HTTP 403` / `连不上服务器` /
     *                  异常类名）。与 [message] 分开：那个是给现场的人看的，可能带上
     *                  服务端返回的说明；而 detail 会经远程指令的回执写进服务端一张
     *                  长期留存的表，只允许固定短语。
     */
    data class Result(
        val success: Boolean,
        val message: String,
        val deviceId: String,
        val phone: String?,
        val retryable: Boolean = false,
        val detail: String = ""
    )

    /**
     * 发一次注册请求并落盘结果。**不抛异常**（除协程取消）：一切失败都翻成
     * [Result] 里的一句话，调用方只剩「把这句话放上自己的通道」一件事。
     */
    suspend fun register(context: Context): Result {
        val app = context.applicationContext

        // 「首次注册」与「重新注册」在日志里长得一模一样，而设备身份被重置过一次
        // 恰恰是排查「服务端怎么多出一台设备」的关键线索。必须在下面 updateToken
        // 之前读，那之后令牌已经换成新的了。
        val isReRegister = DevicePrefs.deviceToken(app).isNotBlank()

        // 先落盘再发请求。UUID 一旦生成就是持久的，即使这次请求失败或进程中途被杀，
        // 重试复用的也是同一个标识，不会再注册出第二台设备。
        val deviceId = DevicePrefs.getOrCreateDeviceId(app)
        val phone = DevicePrefs.phone(app).ifBlank { null }

        return try {
            val response = RetrofitClient.getApiService().registerDevice(
                DeviceInfo(
                    deviceId = deviceId,
                    // 名字跟随手机本身，读不到也有厂商+机型兜底，不会是空值
                    deviceName = DeviceName.read(app),
                    platform = "android",
                    phone = phone,
                    appVersion = BuildConfig.VERSION_NAME,
                    enrollSecret = DevicePrefs.getOrCreateEnrollSecret(app),
                    // 服务端只在**首次注册**时看它，已注册的设备带着也无妨。
                    // 为空表示这台服务器没启用准入校验（或是老用户没扫过码），服务端会放行。
                    enrollToken = DevicePrefs.enrollToken(app).ifBlank { null }
                )
            )

            val data = response.body()?.data
            if (!response.isSuccessful || data == null) {
                // Retrofit 在非 2xx 时 body() 恒为 null，载荷其实在 errorBody() 里，
                // 不解析就永远只能显示一个光秃秃的状态码。
                val detail = ApiError.parseMessage(response.errorBody()?.string())
                EventLog.write(
                    app, EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR,
                    reason = "HTTP ${response.code()}"
                )
                Result(
                    success = false,
                    message = "注册失败（HTTP ${response.code()}）" + (detail?.let { "：$it" } ?: ""),
                    deviceId = deviceId,
                    phone = phone,
                    retryable = true,
                    detail = "HTTP ${response.code()}"
                )
            } else {
                applySuccess(app, deviceId, phone, data.deviceToken, data.status, isReRegister)
                Result(true, "注册成功", deviceId, phone, detail = "HTTP ${response.code()}")
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Registration failed: server unreachable", e)
            EventLog.write(app, EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR, reason = "连不上服务器")
            Result(false, "连不上服务器，请到设置里检查服务器地址", deviceId, phone, detail = "连不上服务器")
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed", e)
            // 受控文案：只写异常类名，不拼 message。注册路径的异常 message 可能带上
            // 服务器地址或响应片段，而这张表要留 7 天。
            EventLog.write(
                app, EventLog.DEVICE_REGISTER_FAILED, EventLog.LEVEL_ERROR,
                reason = e.javaClass.simpleName
            )
            Result(false, "注册失败：${e.message ?: e.javaClass.simpleName}", deviceId, phone,
                detail = e.javaClass.simpleName)
        }
    }

    /** 注册成功之后要落的所有盘、要触发的所有补传。顺序是有讲究的，别调换。 */
    private suspend fun applySuccess(
        app: Context,
        deviceId: String,
        phone: String?,
        token: String,
        status: String?,
        isReRegister: Boolean
    ) {
        RetrofitClient.updateToken(token)
        RetrofitClient.updateDeviceId(deviceId)

        DevicePrefs.get(app).edit().apply {
            putString(DevicePrefs.KEY_DEVICE_TOKEN, token)
            if (phone != null) putString(DevicePrefs.KEY_PHONE, phone)
        }.apply()

        // 注册响应带着设备状态，这里同步一次：被禁用的设备重新注册后仍是禁用，
        // 不该因为「注册成功了」就显示成可用。
        DeviceStatus.set(app, status.equals("DISABLED", ignoreCase = true))

        // 注册前收到的短信是以空 deviceId/phone 入库的，先把身份补上再触发上传。
        try {
            AppDatabase.getInstance(app).smsQueueDao().backfillIdentity(deviceId, phone.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "Backfill queue identity failed", e)
        }

        SmsUploadWorker.enqueue(app)

        EventLog.write(
            app, EventLog.DEVICE_REGISTERED, EventLog.LEVEL_INFO,
            reason = if (isReRegister) "重新注册" else "首次注册"
        )
    }
}
