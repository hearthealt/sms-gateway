package com.smsgateway.app.network

import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuthInterceptor(
    private val deviceTokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    /** 服务端回 401 时回调一次。由 RetrofitClient 接到 AuthState 上。 */
    private val onUnauthorized: () -> Unit = {}
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        val token = deviceTokenProvider()
        val deviceId = deviceIdProvider()

        val requestBuilder = originalRequest.newBuilder()

        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        if (!deviceId.isNullOrBlank()) {
            requestBuilder.addHeader("X-Device-Id", deviceId)
        }

        requestBuilder.addHeader("X-Timestamp", timestamp)
        requestBuilder.addHeader("Content-Type", "application/json")

        val response = chain.proceed(requestBuilder.build())

        // 任何一次 401 都说明服务端不认我们手里的令牌了 —— 不只是心跳。
        //
        // 放在拦截器里是因为它是**所有**设备请求的必经之路。此前只有心跳处理 401，
        // 而心跳周期是 30 秒；更早一步能发现问题的是首页启动时的今日统计请求
        // （/api/device/sms/stats），但那条路径拿到 401 后什么都不做。
        // 结果是「服务端已删掉这台设备」这件事最长要 30 秒才被发现，
        // 而在这段窗口里界面一切正常、启动网关按钮可用 —— 用户会以为设备好好的，
        // 点了才发现不对。
        if (response.code == 401) {
            onUnauthorized()
        }
        return response
    }
}