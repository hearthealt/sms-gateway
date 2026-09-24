package com.smsgateway.app.network

import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuthInterceptor(
    private val deviceTokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?,
    /**
     * 服务端回 401 时回调一次，参数是**这次请求实际带上去的那个令牌**。
     * 由 RetrofitClient 接到 AuthState 上。
     *
     * 带上令牌是必需的，不是多传一个参数：重新注册会换掉令牌，而注册前发出的请求
     * 可能这时候才回来 —— 它带的 401 说的是**旧令牌**被拒，与新令牌无关。
     * 不比对就清库的话，刚存下的新令牌会被这条迟到的 401 删掉，
     * 界面重新显示「未注册」，用户只能再注册一次，而重注册之后同样的事还可能再发生。
     */
    private val onUnauthorized: (String) -> Unit = {}
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
        //
        // **没带令牌的请求除外**。未注册时（或刚改过服务器地址、令牌被清空之后）
        // 请求本来就不带 Authorization 头，拿到 401 是必然的，而它与「令牌失效」
        // 是两件完全不同的事：前者只需要去注册，后者意味着服务端那条设备记录已经没了。
        // 不区分的话，新装的应用点一下「自检」就会被提示「服务端已不认这台设备」——
        // 而且会顺手把网关停掉、写一条 ERROR 日志，三处都是误报。
        if (response.code == 401 && !token.isNullOrBlank()) {
            onUnauthorized(token)
        }
        return response
    }
}