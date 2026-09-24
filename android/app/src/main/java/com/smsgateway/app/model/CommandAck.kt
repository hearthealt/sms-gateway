package com.smsgateway.app.model

/**
 * 远程指令的执行回执。
 *
 * 回执走独立端点（POST api/device/command/ack），不搭下一次心跳：
 * 回执必须立刻发 —— 否则管理员点完「停止网关」之后，控制台 60 秒内一直显示「已下发」，
 * 他会以为按钮没反应而再点一次。
 */
data class CommandAckRequest(
    val results: List<CommandAckItem>
)

data class CommandAckItem(
    val id: Long,
    /** DONE / FAILED / REJECTED，见 [CommandStatus]。 */
    val status: String,
    /**
     * 一句话说明。**只发固定短语**，不拼异常 message ——
     * 它会被原样写进服务端一张长期留存的表里，而异常文案可能带上本机路径之类的细节。
     */
    val detail: String? = null
)

/**
 * 服务端认下的条数（属于本机、且它收到了这条回执）。
 *
 * 注意**不是**「改动了几个字段」：设备重发一次已经处理过的回执时这个数不变，
 * 设备据此停止重发。返回改动数的话，重发会被理解成「服务端没收到」，于是永远重发下去。
 */
data class CommandAckResult(
    val accepted: Int = 0
)

/** 回执状态的字面量。写成常量而不是枚举，与后端用 String 接收的理由一致：不认识的值要能显式拒绝。 */
object CommandStatus {
    const val DONE = "DONE"
    const val FAILED = "FAILED"

    /**
     * 「我看懂了这条指令，但我做不到」（例如本机版本还不支持这个类型）。
     *
     * 必须存在：没有这一档，那种指令会在设备连续不回执中慢慢耗到过期，
     * 管理端看到的只是「设备没反应」，而真正的原因在客户端版本上。
     */
    const val REJECTED = "REJECTED"
}

/** 指令类型名，与后端 DeviceCommandType 一一对应。 */
object CommandType {
    const val START_GATEWAY = "START_GATEWAY"
    const val STOP_GATEWAY = "STOP_GATEWAY"
    const val SET_PHONE = "SET_PHONE"
    const val REUPLOAD = "REUPLOAD"
    const val CLEAR_UPLOADED = "CLEAR_UPLOADED"
    const val RE_REGISTER = "RE_REGISTER"
}
