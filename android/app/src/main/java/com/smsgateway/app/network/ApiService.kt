package com.smsgateway.app.network

import com.smsgateway.app.model.ApiResponse
import com.smsgateway.app.model.CommandAckRequest
import com.smsgateway.app.model.CommandAckResult
import com.smsgateway.app.model.DeviceEventLogEntry
import com.smsgateway.app.model.DeviceInfo
import com.smsgateway.app.model.DeviceSmsStats
import com.smsgateway.app.model.DeviceTrend
import com.smsgateway.app.model.DeviceTokenData
import com.smsgateway.app.model.HeartbeatRequest
import com.smsgateway.app.model.HeartbeatResponse
import com.smsgateway.app.model.NotifyTestResult
import com.smsgateway.app.model.PageResult
import com.smsgateway.app.model.SmsRecord
import com.smsgateway.app.model.SmsUploadRequest
import com.smsgateway.app.model.SmsUploadResponseData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {

    @POST("api/device/register")
    suspend fun registerDevice(@Body request: DeviceInfo): Response<ApiResponse<DeviceTokenData>>

    @POST("api/device/heartbeat")
    suspend fun heartbeat(@Body request: HeartbeatRequest): Response<HeartbeatResponse>

    /**
     * 报告「网关已停止」。
     *
     * 心跳是单向的「我还活着」；停了之后心跳就断了，服务端要等 90 秒超时才知道，
     * 那 90 秒管理后台一直显示在线。停止时补这一条，后台立刻变灰。
     * 只是尽力而为 —— 进程被杀时发不出去，那种情况仍旧由心跳超时兜底。
     */
    @POST("api/device/offline")
    suspend fun reportOffline(): Response<ApiResponse<Unit>>

    /**
     * 上报远程指令的执行结果。
     *
     * 走独立端点而不是搭下一次心跳：回执必须**立刻**发。搭下一次心跳意味着「已下发 →
     * 已执行」最长要 60 秒，而管理员点了「停止网关」之后，控制台 60 秒内一直显示
     * 「已下发」，他会以为按钮没反应而再点一次。
     *
     * 挂在 /api/device 下，同样受设备鉴权拦截器保护。
     */
    @POST("api/device/command/ack")
    suspend fun ackCommands(@Body request: CommandAckRequest): Response<ApiResponse<CommandAckResult>>

    /**
     * 本设备在**服务端**的运行日志，给「一键导出诊断包」用。
     *
     * 不分页，只取最近 N 条（服务端夹到 200）：那是一次快照，不是浏览功能。
     * 返回里的接收号码已由服务端打码。
     */
    @GET("api/device/eventlog")
    suspend fun deviceEventLog(
        @Query("limit") limit: Int
    ): Response<ApiResponse<List<DeviceEventLogEntry>>>

    /**
     * 本设备在服务端的历史记录。
     * 挂在 /api/device 下，因此自动受设备鉴权拦截器保护，设备身份由令牌决定，无需传 deviceId。
     */
    @GET("api/device/sms")
    suspend fun mySms(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("includeIgnored") includeIgnored: Boolean,
        /**
         * 关键词，命中发送方**或**正文；null 表示不过滤。
         *
         * **必须在服务端筛**：这个列表是无限滚动的（一次只加载一页），
         * 在已加载的几页里做前端筛只会给出「明明有这条却说没有」的结论。
         */
        @Query("keyword") keyword: String?
    ): Response<ApiResponse<PageResult<SmsRecord>>>

    /** 本设备今日的短信统计。设备身份同样来自令牌，服务端据此计算。 */
    @GET("api/device/sms/stats")
    suspend fun mySmsStats(): Response<ApiResponse<DeviceSmsStats>>

    /**
     * 主页两张小图的数据：近 N 天 + 今日逐小时。
     *
     * 两个图合成一个请求：它们永远一起出现、刷新节奏也一样，分开就是每次多一个往返，
     * 而这台设备是 7×24 挂着的。
     */
    @GET("api/device/sms/trend")
    suspend fun smsTrend(@Query("days") days: Int): Response<ApiResponse<DeviceTrend>>

    /**
     * 当前启用的转发渠道名（只有名字，没有配置）。
     *
     * 自检页在按钮旁边先摆出「会发给谁」：一个渠道都没启用时，点测试只会返回空列表，
     * 而人看到的是「测过了，什么都没发生」。
     */
    @GET("api/device/notify/channels")
    suspend fun notifyChannels(): Response<ApiResponse<List<String>>>

    /**
     * 测一次转发链路：服务端会给每个启用的转发渠道各发一条测试消息，返回逐个结果。
     *
     * 这条请求**有外发副作用**（真的会往微信/钉钉发消息），所以服务端按设备限流
     * （5 分钟一次），界面上也要挡住连点。
     */
    @POST("api/device/notify/test")
    suspend fun testNotify(): Response<ApiResponse<List<NotifyTestResult>>>

    @POST("api/sms/receive")
    suspend fun uploadSms(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: SmsUploadRequest
    ): Response<ApiResponse<SmsUploadResponseData>>
}
