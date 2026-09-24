package com.smsgateway.app.util

import com.google.gson.Gson

/**
 * 从服务端返回的错误体里取出那句给人看的话。
 *
 * 后端所有接口的失败响应都是同一个信封 `{code, message, data}`，而 Retrofit 在非 2xx
 * 时 `body()` 恒为 null —— 消息其实在 `errorBody()` 里，不解析就永远只能显示一个
 * 光秃秃的状态码（现场看到「注册失败（HTTP 403）」，而服务端明明写清了原因）。
 *
 * 抽成一个共用点是因为有三个调用方（注册、转发测试、设备端短信列表），而它们原先
 * 各自抄了一份：抄三份的下一步就是其中一份被改坏，表现是「同一个错误在这一页说得清、
 * 在那一页说不清」。
 */
object ApiError {

    private val gson = Gson()

    /** 解析失败或消息为空时返回 null，由调用方决定兜底文案 —— 不因此再抛一次异常。 */
    fun parseMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            gson.fromJson(raw, ErrorBody::class.java)?.message?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class ErrorBody(val code: Int = 0, val message: String? = null)
}
