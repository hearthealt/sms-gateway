package com.smsgateway.app.qr

import com.google.gson.Gson
import java.net.URI

/** 二维码里携带的配置。 */
data class QrConfig(
    /** 字段可空是为了兼容 Gson：缺字段时它不会报错，而是给 null。 */
    val url: String? = null,

    /**
     * 设备恢复码：管理员在控制台为某台设备签发，扫码的设备会**采用**这个身份
     * （覆盖本机的设备标识与重注册密钥）。
     *
     * 这两个字段**不该由手机自己生成**。本应用里的「配置二维码」只产出 url；
     * 如果身份也能由任意一台手机写进二维码，那么给你看一张码
     * 就能让你的手机"变成"攻击者的设备，此后你的短信全部记在他名下、
     * 而他有后台权限能读走。所以恢复码必须出自管理后台。
     */
    val deviceId: String? = null,
    val enrollSecret: String? = null,

    /**
     * 服务器接入口令：管理后台「快速连接」页生成，新设备**首次注册**时带给服务端。
     *
     * 它与上面两个**不是一回事**，别混：deviceId/enrollSecret 是**设备身份**
     * （证明「我是哪台设备」，只在设备已存在时校验）；这个只是**服务器准入凭证**
     * （证明「我被允许接入本服务器」，只在设备还不存在时校验）。
     *
     * 它是唯一可以由二维码携带、而不破坏上面那条安全前提的凭证 —— 因为它认的是
     * 「服务器准不准你进」，不是「你是哪台设备」。拿到别人的口令，最多是让你的设备
     * 也接进同一台服务器、以**它自己**的身份（自注册成一台新设备），并不能顶替谁。
     */
    val enrollToken: String? = null
) {
    /** 是否携带了完整的设备身份。缺一不可 —— 只有一半会导致注册必失败。 */
    val hasIdentity: Boolean
        get() = !deviceId.isNullOrBlank() && !enrollSecret.isNullOrBlank()

    /** 完整身份，或 null。避免调用方到处写两个 `orEmpty()`。 */
    val identity: QrIdentity?
        get() = if (hasIdentity) QrIdentity(deviceId!!, enrollSecret!!) else null
}

/**
 * 二维码里携带的设备身份，即管理后台签发的**恢复码**。
 *
 * 单独一个类型是为了让调用方没法只传一半：这两个值必须成对出现，
 * 只给其中一个，服务端注册必然失败。
 */
data class QrIdentity(val deviceId: String, val enrollSecret: String)

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

    /**
     * 编码服务器地址，以及（如果本机有的话）服务器的接入口令。
     *
     * **不携带设备名**：名字跟随手机本身（见 DeviceName），放进二维码就等于给了一条
     * 「用别人的码把本机改名」的路 —— 现场已经踩过：两台手机顶着同一个名字出现在
     * 管理后台，谁也分不出哪台是哪台。
     *
     * **不携带设备身份**：理由见 [QrConfig.deviceId]。
     *
     * **带上接入口令是有意的**：服务端启用准入校验时，不含口令的码扫到另一台手机上
     * 注册会被拒 —— 而导出的本意就是「让另一台设备也接进这台服务器」，少了口令
     * 就不是一份完整的配置。口令认的是「服务器准不准你进」而非「你是哪台设备」，
     * 所以它不违反上面那条前提。
     */
    fun encode(url: String, enrollToken: String? = null): String =
        gson.toJson(
            QrConfig(
                url = url.trim().trimEnd('/'),
                enrollToken = enrollToken?.trim()?.takeIf { it.isNotBlank() }
            )
        )

    fun parse(raw: String): QrParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return QrParseResult.Invalid("二维码内容为空")

        // 先按 JSON 解析；解不出来（或解出来没有 url）时，就把整段当成一个**裸地址**。
        //
        // 这条兜底不是顺手加的：管理后台「快速连接」页上「复制地址」给出来的正是
        // `http://192.168.1.100:8080` 这样一段纯文本。不认它的话，用户从后台复制的
        // 地址粘到这里会得到「不是有效的配置内容」—— 而那是这个功能两条路里的一条。
        val config = runCatching { gson.fromJson(text, QrConfig::class.java) }
            .getOrNull()
            ?.takeIf { !it.url.isNullOrBlank() }
            ?: QrConfig(url = text)

        val rawUrl = config.url?.trim().orEmpty()
        // 空串保持空串：补前缀会把它变成 "http://"，报错就变成「没有主机名」而不是「地址为空」。
        val url = if (rawUrl.isEmpty()) rawUrl else withSchemeIfMissing(rawUrl)
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
                // 合法性不在这里判定 —— 由服务端在注册时比对密钥哈希，那才是权威。
                deviceId = config.deviceId?.trim()?.takeIf { it.isNotBlank() },
                enrollSecret = config.enrollSecret?.trim()?.takeIf { it.isNotBlank() },
                // 同样不在本地判定：口令对不对只有服务端知道。这里只负责原样带走、
                // 并在界面上告诉用户「这张码带了口令」。
                enrollToken = config.enrollToken?.trim()?.takeIf { it.isNotBlank() }
            )
        )
    }

    /**
     * 认得出 scheme 的写法：`http://`、`https://`、以及 `ftp://` 这类**故意要被拒**的。
     *
     * 锚在开头，所以 JSON 里的 `"url":"http://…"` 不会被误判 —— 那是整段文本里的
     * 第二个字符起才出现 `://`，而这段文本会先走 JSON 分支，根本到不了这里。
     */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /**
     * 没写 scheme 就补上 `http://`。
     *
     * 现场手抄、口述、从聊天记录里复制过来的多半是 `192.168.1.100:8080` 这一段。
     * 要求用户自己想起补 `http://`，是在一个纯粹的形式问题上卡人 —— 而补错了
     * （比如补成 `https://`）反而会连不上，那个错还很难看出来。
     *
     * 只补 http：本项目部署在内网、`network_security_config` 本就放开明文，
     * 而 `https://192.168.x.x:8080` 只会得到一个握手失败。
     */
    private fun withSchemeIfMissing(text: String): String =
        if (SCHEME_PREFIX.containsMatchIn(text)) text else "http://$text"

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
