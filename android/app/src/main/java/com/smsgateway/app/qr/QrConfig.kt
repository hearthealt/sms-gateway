package com.smsgateway.app.qr

import com.google.gson.Gson
import java.net.URI

/** 二维码里携带的配置。 */
data class QrConfig(
    /** 字段可空是为了兼容 Gson：缺字段时它不会报错，而是给 null。 */
    val url: String? = null,
    val deviceName: String? = null,

    /**
     * 设备恢复码：管理员在控制台为某台设备签发，扫码的设备会**采用**这个身份
     * （覆盖本机的设备标识与重注册密钥）。
     *
     * 这两个字段**不该由手机自己生成**。本应用里的「配置二维码」只产出
     * url + deviceName；如果身份也能由任意一台手机写进二维码，那么给你看一张码
     * 就能让你的手机"变成"攻击者的设备，此后你的短信全部记在他名下、
     * 而他有后台权限能读走。所以恢复码必须出自管理后台。
     */
    val deviceId: String? = null,
    val enrollSecret: String? = null
) {
    /** 是否携带了完整的设备身份。缺一不可 —— 只有一半会导致注册必失败。 */
    val hasIdentity: Boolean
        get() = !deviceId.isNullOrBlank() && !enrollSecret.isNullOrBlank()
}

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

        // 身份字段只能成对出现：只给一半的话注册必然失败（服务端要么没有 deviceId 可比，
        // 要么拿不出密钥），在这里挡掉比让用户到注册页再看一次报错要好。
        val hasDeviceId = !config.deviceId.isNullOrBlank()
        val hasSecret = !config.enrollSecret.isNullOrBlank()
        if (hasDeviceId != hasSecret) {
            return QrParseResult.Invalid("恢复码不完整：deviceId 与 enrollSecret 必须同时提供")
        }

        return QrParseResult.Ok(
            QrConfig(
                url = url.trimEnd('/'),
                deviceName = config.deviceName?.trim()?.takeIf { it.isNotBlank() },
                // 合法性不在这里判定 —— 由服务端在注册时比对密钥哈希，那才是权威。
                deviceId = config.deviceId?.trim()?.takeIf { it.isNotBlank() },
                enrollSecret = config.enrollSecret?.trim()?.takeIf { it.isNotBlank() }
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
