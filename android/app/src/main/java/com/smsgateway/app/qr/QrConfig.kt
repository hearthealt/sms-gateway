package com.smsgateway.app.qr

import com.google.gson.Gson
import java.net.URI

/** 二维码里携带的配置。 */
data class QrConfig(
    /** 字段可空是为了兼容 Gson：缺字段时它不会报错，而是给 null。 */
    val url: String? = null,
    val deviceName: String? = null
)

sealed interface QrParseResult {
    data class Ok(val config: QrConfig) : QrParseResult
    data class Invalid(val reason: String) : QrParseResult
}

/**
 * 配置二维码的编解码与校验。
 *
 * **安全前提**：本应用的 network_security_config 允许明文访问任意主机，所以一张二维码
 * 等于一条未认证的配置注入通道 —— 恶意二维码可以把设备指向攻击者的服务器，此后每一条
 * 短信和验证码都会被截走。因此这里只做格式校验，真正的把关在界面上：
 * 解码后不自动保存，必须由用户看过完整地址并确认。
 */
object QrConfigCodec {

    private val gson = Gson()

    fun encode(url: String, deviceName: String?): String =
        gson.toJson(
            QrConfig(
                url = url.trim().trimEnd('/'),
                deviceName = deviceName?.trim()?.takeIf { it.isNotBlank() }
            )
        )

    fun parse(raw: String): QrParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return QrParseResult.Invalid("二维码内容为空")

        val config = runCatching { gson.fromJson(text, QrConfig::class.java) }
            .getOrNull()
            ?: return QrParseResult.Invalid("不是有效的配置内容")

        val url = config.url?.trim().orEmpty()
        validateUrl(url)?.let { return QrParseResult.Invalid(it) }

        return QrParseResult.Ok(
            QrConfig(
                url = url.trimEnd('/'),
                deviceName = config.deviceName?.trim()?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** @return 不合法时返回原因，合法返回 null。 */
    fun validateUrl(url: String): String? {
        if (url.isBlank()) return "地址为空"

        val uri = runCatching { URI(url) }.getOrNull() ?: return "地址格式不对"

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "只支持 http/https 开头的地址"
        if (uri.host.isNullOrBlank()) return "地址里没有主机名"

        return null
    }
}
