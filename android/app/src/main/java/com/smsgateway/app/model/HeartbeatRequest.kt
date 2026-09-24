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
    val pendingCount: Int? = null,
    /**
     * 这是一次「网关已停止」时的低频探测心跳，不是常规心跳。
     *
     * 异步指令搭心跳下发，而网关停着就没人发心跳 —— 于是「启动网关」这条最需要在
     * 「已停」状态下送达的指令恰好送不到。设备用一个 15 分钟的周期任务发这种心跳
     * （见 CommandProbeWorker），服务端在它上面只下发「启动网关」，并且
     * **完全不更新在线状态**（否则后台会每 15 分钟把一台已停的设备显示成在线）。
     *
     * 常规心跳传 null，字段被 Gson 省略，服务端行为与本次变更之前完全一致。
     */
    val commandProbe: Boolean? = null,

    /**
     * 外发短信的执行结果。
     *
     * 设备会把**最近若干条**未过期的结果每次都带上（它不知道服务端收没收到），
     * 所以服务端必须幂等 —— 同一份结果报几次都只算一次。
     */
    val outboundResults: List<OutboundResult>? = null
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
    val status: String? = null,
    /**
     * 下发给本机的远程指令。服务端恒定返回该字段（没有时是空列表）。
     *
     * 用可空类型是为了兼容「服务端还没升级」：老服务端的响应里没有这个字段，
     * Gson 会给 null —— 而 null 与空列表在这里的处理完全一样（什么都不做）。
     */
    val commands: List<CommandPayload>? = null,

    /**
     * 交给本机去发的外发短信。服务端恒定返回该字段（没有时是空列表）。
     *
     * 探测心跳（网关已停止时的低频心跳）**不会带**它：停掉网关的意图就是
     * 「这台机器冻结住」，那时让它发短信（要计费、对方会收到）是违背这个意图的。
     */
    val outbound: List<OutboundPayload>? = null
)

/**
 * 一条待执行的远程指令。
 *
 * 只有四个字段，与后端 DeviceCommandPayload 一一对应：status / attempts /
 * issuedBy 那些是管理端的运维信息，设备不需要。
 */
data class CommandPayload(
    val id: Long,
    /** 见后端 DeviceCommandType。不认识的名字一律回执 REJECTED，不静默忽略。 */
    val type: String,
    val argument: String? = null,
    /** ISO 本地时间。设备自己判一次过期，不等服务端告知。 */
    val expiresAt: String? = null
)
