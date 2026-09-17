package com.smsgateway.app.network

import com.smsgateway.app.model.ApiResponse
import com.smsgateway.app.model.DeviceInfo
import com.smsgateway.app.model.DeviceSmsStats
import com.smsgateway.app.model.DeviceTokenData
import com.smsgateway.app.model.HeartbeatRequest
import com.smsgateway.app.model.HeartbeatResponse
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
     * 本设备在服务端的历史记录。
     * 挂在 /api/device 下，因此自动受设备鉴权拦截器保护，设备身份由令牌决定，无需传 deviceId。
     */
    @GET("api/device/sms")
    suspend fun mySms(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("includeIgnored") includeIgnored: Boolean
    ): Response<ApiResponse<PageResult<SmsRecord>>>

    /** 本设备今日的短信统计。设备身份同样来自令牌，服务端据此计算。 */
    @GET("api/device/sms/stats")
    suspend fun mySmsStats(): Response<ApiResponse<DeviceSmsStats>>

    @POST("api/sms/receive")
    suspend fun uploadSms(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body request: SmsUploadRequest
    ): Response<ApiResponse<SmsUploadResponseData>>
}
