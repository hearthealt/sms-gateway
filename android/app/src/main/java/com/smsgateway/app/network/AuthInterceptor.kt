package com.smsgateway.app.network

import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuthInterceptor(
    private val deviceTokenProvider: () -> String?,
    private val deviceIdProvider: () -> String?
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

        return chain.proceed(requestBuilder.build())
    }
}