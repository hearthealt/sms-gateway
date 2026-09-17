package com.smsgateway.app.network

import android.content.Context
import com.smsgateway.app.util.DevicePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var baseUrl: String = "http://10.0.2.2:8080/"
    private var deviceToken: String? = null
    private var deviceId: String? = null

    private var apiService: ApiService? = null
    private var retrofit: Retrofit? = null
    private var httpClient: OkHttpClient? = null

    /** Retrofit 要求 baseUrl 以 / 结尾，这里统一收口，免得configure 与 ensureConfigured 各写一份。 */
    private fun normalizedUrl(url: String): String = url.trimEnd('/') + "/"

    fun configure(url: String, token: String?, id: String?) {
        baseUrl = normalizedUrl(url)
        deviceToken = token
        deviceId = id
        resetClient()
    }

    /**
     * 按 DevicePrefs 里的已保存配置幂等地装配客户端。
     *
     * WorkManager 拉起进程跑上传任务、或广播接收器被唤起时，DashboardViewModel 根本没被创建过。
     * 若不在这些入口兜底，请求会带着字段初始值（模拟器回环地址 10.0.2.2）且不带 Authorization 头
     * 打出去 —— 只会 401 然后无限重试，短信永远传不上去。
     *
     * 只在地址或凭据真的变了时才重建，否则每 30 秒一次的心跳都会新建一个 OkHttpClient。
     */
    fun ensureConfigured(context: Context) {
        val url = DevicePrefs.serverUrl(context)
        val token = DevicePrefs.deviceToken(context).ifBlank { null }
        val id = DevicePrefs.deviceId(context).ifBlank { null }

        if (apiService != null &&
            baseUrl == normalizedUrl(url) &&
            deviceToken == token &&
            deviceId == id
        ) {
            return
        }

        configure(url, token, id)
    }

    fun updateToken(token: String?) {
        deviceToken = token
        resetClient()
    }

    fun updateDeviceId(id: String?) {
        deviceId = id
        resetClient()
    }

    fun getDeviceToken(): String? = deviceToken
    fun getDeviceId(): String? = deviceId
    fun getBaseUrl(): String = baseUrl

    private fun resetClient() {
        apiService = null
        retrofit = null
        httpClient = null
    }

    private fun buildHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(
                AuthInterceptor(
                    deviceTokenProvider = { deviceToken },
                    deviceIdProvider = { deviceId }
                )
            )
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun getHttpClient(): OkHttpClient =
        httpClient ?: buildHttpClient().also { httpClient = it }

    fun getApiService(): ApiService {
        if (apiService == null) {
            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(getHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit!!.create(ApiService::class.java)
        }
        return apiService!!
    }

    /**
     * 探活：对给定地址发一次 GET。
     *
     * 拿到任何 HTTP 状态码都说明地址可达（根路径没有映射，返回 404 也算通），
     * 抛 IOException 才是真的连不上（地址写错、服务没起、不在同一网段）。
     * 设置页的「测试连接」用它，免得用户靠猜。
     *
     * 接受 url 参数而不是直接读 baseUrl，这样设置页可以测输入框里当前的值，
     * 不必先保存再测。
     */
    suspend fun probe(url: String): Int = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(normalizedUrl(url)).get().build()
        getHttpClient().newCall(request).execute().use { it.code }
    }
}
