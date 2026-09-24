package com.smsgateway.app.util

import com.smsgateway.app.model.CommandType

/**
 * 远程指令的判定规则。**全是纯函数**，因此可以直接在 JVM 单测里钉住边界。
 *
 * 抽出来的理由与 `LocalMessageId` / `SmsFilter` 一样：这些判断里有几处
 * 「差一点就错」的边界（等于过期时刻算不算过期、解析不出来时怎么办），
 * 埋在一个需要 Context、需要网络的类里就永远测不到。
 */
object CommandRules {

    /**
     * 指令是否已过期。
     *
     * **到点即过期**：`now >= expiresAt` 判过期，不是 `>`。差一个等号会让一条
     * 「还有 0 秒有效」的指令被执行 —— 而服务端那边的 Janitor 正是以
     * `expires_at <= now` 判它过期的，两边的边界必须一致，否则会出现
     * 「设备执行了、服务端认为它早已过期」这种对不上的记录。
     *
     * **解析不出来时按「没过期」处理**：宁可执行一次管理员确实下发的指令，
     * 也不要因为一个格式变化把指令静默丢掉 —— 后者在界面上表现为「点了没反应」，
     * 而前者至少是「做了它该做的事」。服务端下发的时刻是 ISO 本地时间，
     * 与 [ServerTime.parseInstant] 的假设一致。
     */
    fun isExpired(nowMs: Long, expiresAtIso: String?): Boolean {
        val at = ServerTime.parseInstant(expiresAtIso) ?: return false
        return nowMs >= at
    }

    /**
     * 探测心跳（网关已停止时的低频任务）允许执行哪些指令。
     *
     * 只有「启动网关」。用户停网关的意图是「这台机器冻结住，短信留在本地」，
     * 从一个他看不见的后台任务里执行「清理已上传记录」或「改本机号码」，
     * 是在**违背这个意图改本机状态**。服务端也只下发这一种，这里是第二道闸。
     */
    fun probeAllows(type: String): Boolean = type == CommandType.START_GATEWAY

    /**
     * 参数规整：去掉首尾空白，空串一律归一成 null。
     *
     * 归一成 null 而不是空串很重要：空串在服务端是「传了参数但没内容」，
     * 会被当成校验错误，而设备真正的意思是「没有参数」。
     */
    fun normalizeArgument(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }
}
