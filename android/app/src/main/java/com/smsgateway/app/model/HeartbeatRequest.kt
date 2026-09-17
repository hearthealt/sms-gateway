package com.smsgateway.app.model

/**
 * 心跳上报。
 *
 * deviceId 是必填项。
 * 其余字段可为 null：读不到时 Gson 会整体省略该字段，
 * 后端只在字段存在时才更新，避免用无效值覆盖上一次的有效值。
 */
data class HeartbeatRequest(
    val deviceId: String,
    val timestamp: String,
    /** 本机号码，后端用 @JsonAlias("phone") 接收。为空时省略，不会把已存号码覆盖成空。 */
    val phone: String? = null,
    /** 用户自定义的设备名。为空时省略，后端保留原值。 */
    val deviceName: String? = null,
    val battery: Int? = null,
    val network: String? = null,
    val charging: Boolean? = null,
    val pendingCount: Int? = null
)

data class HeartbeatResponse(
    val code: Int,
    val message: String,
    val data: HeartbeatData?
)

/**
 * 心跳响应体。
 *
 * 设备状态走「正常响应里带状态」而不是错误码：这样心跳既能继续上报（管理端因此看得到
 * 「禁用但仍在线」），设备也能从一条 200 里学到自己的状态。上传接口则用 403 拒绝。
 */
data class HeartbeatData(
    val status: String? = null
)
