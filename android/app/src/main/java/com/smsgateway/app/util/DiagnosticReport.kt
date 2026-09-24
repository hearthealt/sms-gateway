package com.smsgateway.app.util

import com.google.gson.Gson
import com.smsgateway.app.SelfTestItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断包的**内容与红线**。
 *
 * 这个对象只做一件事：把已经收集好的数据渲染成一份文本。收集（读库、读 prefs、发请求）
 * 在 [DiagnosticExporter] 里 —— 分开是为了让渲染与脱敏这两件「错了不会报错」的事
 * 能在 JVM 单测里钉住。
 *
 * ## 这份文件会离开设备
 *
 * 它经过系统分享面板，进到一个聊天工具里，而对面和云端都会存一份。所以：
 *
 * - **绝不导出**：deviceToken、enrollSecret、enrollToken、锁屏 PIN 的哈希与盐、
 *   短信正文、提取出的验证码、`localMessageId`。
 * - **完整导出**：设备标识（**它不是凭据** —— 后台列表、设备界面、任何截图里都可见，
 *   而它是本地日志与服务端日志之间唯一的连接键，脱敏它会让报告失去一半用途）、
 *   服务器地址（内网 IP 就是现场信息本身）。
 * - **打码导出**：手机号与发送方。
 *
 * `localMessageId` 不在「绝不导出」里是有原因的、值得写下来：它由
 * 「发送方 + 接收时刻 + 正文哈希」拼成，进文件等于**换一种方式留痕** ——
 * 与 `EventLogEntity` 拒绝把 `localMessageId` 写进事件表是同一条理由。所以队列行
 * 只打印自增 `id`。这一处最容易漏，因为它看起来只是一个「消息 ID」。
 *
 * **也不打 logcat**：debug 包的 `HttpLoggingInterceptor` 是 `Level.BODY`，
 * logcat 里**有** `Authorization: Bearer` 与短信正文。把它打进去等于亲手把上面
 * 整份脱敏策略作废。
 */
object DiagnosticReport {

    private val gson = Gson()

    private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // ------------------------------------------------------------------ 数据结构

    /** 队列里的一条（字段已脱敏）。 */
    data class QueueLine(
        val id: Long,
        val senderMasked: String?,
        val phoneMasked: String?,
        val receiveTime: Long,
        val status: String,
        val retryCount: Int,
        val nextRetryAt: Long,
        /** **正文字节数**，不是正文。让读者知道这条有多长，但不泄漏内容。 */
        val contentBytes: Int
    )

    /** 本地事件日志的一行（sender/phone 已打码）。 */
    data class EventLine(
        val at: Long,
        val level: String,
        val typeLabel: String,
        val senderMasked: String?,
        val phoneMasked: String?,
        val reason: String?
    )

    /** 一行「标签 = 值」。 */
    data class Field(val label: String, val value: String)

    data class DiagnosticData(
        val generatedAt: Long,
        val appVersion: String,
        val applicationId: String,
        val deviceId: String,
        val serverUrl: String,
        val buildInfo: String,
        val state: List<Field>,
        val permissions: List<Field>,
        val simSummary: String,
        val selfTest: List<SelfTestItem>,
        val queueLine: String,
        val queue: List<QueueLine>,
        val localEvents: List<EventLine>,
        /** 服务端返回的那一份（**已由服务端打码**）。取不到时为 null。 */
        val serverEvents: List<EventLine>?,
        /** 取服务端日志失败的原因（HTTP 码 / 异常类名），成功时为 null。 */
        val serverEventsError: String?,
        val counts: Map<String, Int>
    )

    // ------------------------------------------------------------------ 渲染

    /**
     * 渲染成一份纯文本。
     *
     * 文本为主件（任何设备都能打开、手机屏上能读、不需要任何工具），最后一节嵌一段
     * JSON（可解析、可比对、能被脚本消费）。**一个产物、两个读者。**
     * 不用 zip：手机上打不开，第一道门槛就把现场挡住了。
     */
    fun render(data: DiagnosticData): String = buildString {
        appendLine("=== 短信网关诊断包 ===")
        appendLine("生成时间：${formatTime(data.generatedAt)}")
        appendLine("应用版本：${data.appVersion}（${data.applicationId}）")
        appendLine("设备标识：${data.deviceId}")
        appendLine("服务器地址：${data.serverUrl}")
        appendLine()

        appendLine("--- 1. 配置与状态 ---")
        data.state.forEach { appendLine("${it.label}：${it.value}") }
        appendLine()

        appendLine("--- 2. 权限与系统环境 ---")
        data.permissions.forEach { appendLine("${it.label}：${it.value}") }
        appendLine("SIM 卡：${data.simSummary}")
        appendLine("系统：${data.buildInfo}")
        appendLine()

        appendLine("--- 3. 自检结果 ---")
        if (data.selfTest.isEmpty()) {
            // 导出**不会**为了这一节去重跑自检（那里面两次网络往返会让导出卡住，
            // 见 DiagnosticExporter），所以这里要说清「怎么才能有」。
            appendLine("（没有自检结果 —— 到应用的「自检」页跑一次，再在那里导出，包里就会有）")
        } else {
            data.selfTest.forEach {
                appendLine("[${if (it.ok) "通过" else "未通过"}] ${it.label}：${it.detail}")
            }
        }
        appendLine()

        appendLine("--- 4. 本地队列 ---")
        appendLine(data.queueLine)
        if (data.queue.isEmpty()) {
            appendLine("（没有未上传的记录）")
        } else {
            appendLine("id | 发送方 | 接收号码 | 接收时刻 | 状态 | 重试 | 下次重试 | 正文字节数")
            data.queue.forEach { line ->
                appendLine(
                    listOf(
                        line.id.toString(),
                        line.senderMasked ?: "-",
                        line.phoneMasked ?: "-",
                        formatTime(line.receiveTime),
                        line.status,
                        line.retryCount.toString(),
                        if (line.nextRetryAt > 0) formatTime(line.nextRetryAt) else "-",
                        line.contentBytes.toString()
                    ).joinToString(" | ")
                )
            }
        }
        appendLine()

        appendLine("--- 5. 本地运行日志（新的在前）---")
        appendEvents(data.localEvents)
        appendLine()

        appendLine("--- 6. 服务端运行日志（本设备，已由服务端打码）---")
        if (data.serverEvents == null) {
            // **不能静默省略**：省略的话读者分不清「服务端一条都没有」和「没取到」。
            // 与界面里 trendAttempted / smsLoaded 那条「区分『还没问过』与『问过但没有』」
            // 是同一条纪律。
            appendLine("（未取到：${data.serverEventsError ?: "未知原因"}）")
        } else {
            appendEvents(data.serverEvents)
        }
        appendLine()

        appendLine("--- 7. 机器可读（JSON）---")
        appendLine(renderJson(data))
        appendLine()
        appendLine("注：本文件不含短信正文、验证码、设备令牌与重注册密钥。")
    }

    private fun StringBuilder.appendEvents(events: List<EventLine>) {
        if (events.isEmpty()) {
            appendLine("（没有记录）")
            return
        }
        events.forEach { line ->
            appendLine(
                listOfNotNull(
                    formatTime(line.at),
                    line.level,
                    line.typeLabel,
                    line.senderMasked,
                    line.phoneMasked,
                    line.reason
                ).joinToString(" | ")
            )
        }
    }

    /** JSON 节从**同一批已脱敏的值**构造，所以两边不可能各自漏一处。 */
    private fun renderJson(data: DiagnosticData): String {
        val summary = linkedMapOf<String, Any?>(
            "generatedAt" to data.generatedAt,
            "appVersion" to data.appVersion,
            "applicationId" to data.applicationId,
            "deviceId" to data.deviceId,
            "serverUrl" to data.serverUrl,
            "selfTest" to data.selfTest.map {
                linkedMapOf("label" to it.label, "ok" to it.ok, "detail" to it.detail)
            },
            "serverEventLogFetched" to (data.serverEvents != null),
            "counts" to data.counts
        )
        return gson.toJson(summary)
    }

    private fun formatTime(at: Long): String = TIME.format(Date(at))

    // ------------------------------------------------------------------ 脱敏

    /**
     * 号码打码：保留前 3 与后 4，中间打掉。
     *
     * 与**服务端** `PhoneUtil.mask` 是同一套规则（那边给服务端日志那一节用）。
     * 两处实现必须保持同一口径：同一个号码在报告的前半段与后半段长得不一样，
     * 会让人以为那是两个号。
     *
     * 位数不足时更激进（前 3 后 4 叠在一起等于把整个号露出来）。
     */
    fun maskNumber(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
        return maskDigits(digits)
    }

    /** 已经确定是纯数字时走这里；测试直接调它，免得每个用例都要先造一遍原始格式。 */
    internal fun maskDigits(digits: String): String? {
        var value = digits
        if (value.isEmpty()) return null

        // 去掉中国大陆国家码，与服务端 PhoneUtil.mask **同一口径**。
        // 两边不一致的话，同一个号码在报告的前半段（设备端打的）与后半段
        // （服务端打的）会长得不一样 —— 而读者只会以为那是两个号。
        if (value.length == 13 && value.startsWith("86")) {
            value = value.substring(2)
        }

        if (value.length >= 11) {
            return value.substring(0, 3) + "****" + value.substring(value.length - 4)
        }
        if (value.length >= 7) {
            return value.substring(0, 3) + "****" + value.substring(value.length - 2)
        }
        return value.substring(0, 1) + "****"
    }

    /**
     * 生成后的**自检**：文件里不得出现任何一个真实凭据，命中就拒绝出文件。
     *
     * 这一道不是防御性写法。诊断包会离开这台设备，写进一个 deviceToken 等于把那台设备
     * 交出去（拿它可以冒充设备上报与读取短信）。
     *
     * 所以不靠「写的时候记得脱敏」—— 那是一种会随人而失效的保证 —— 而是**写完再验一遍**：
     * 把当前所有敏感值逐个在正文里搜。失败是响亮的（导出按钮弹一条错误），
     * 而那条错误本身就是「有人新加了一个字段却忘了脱敏」的信号。
     *
     * @param secrets 空串与 null 会被忽略（它们到处都能匹配上）
     * @throws IllegalArgumentException 命中任何一项
     */
    fun assertNoSecrets(text: String, secrets: List<String?>) {
        val leaked = secrets.filter { !it.isNullOrBlank() && text.contains(it) }
        require(leaked.isEmpty()) {
            "诊断包自检失败：检测到 ${leaked.size} 项凭据未脱敏，已拒绝生成。"
        }
    }
}
