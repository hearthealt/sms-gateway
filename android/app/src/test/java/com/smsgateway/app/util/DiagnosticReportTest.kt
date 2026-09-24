package com.smsgateway.app.util

import com.smsgateway.app.SelfTestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断包的渲染。
 *
 * 最要紧的一条在最后：**结构上不允许**把短信正文与 `localMessageId` 放进报告。
 * 前者是验证码明文，后者由「发送方 + 接收时刻 + 正文哈希」拼成 —— 进文件等于换一种
 * 方式留痕（与 EventLogEntity 拒绝把 localMessageId 写进事件表是同一条理由）。
 *
 * 钉的是**字段清单**而不是某一次渲染的结果，因为「这一次没写进去」证明不了下一次：
 * 加一个字段的人不会记得回来看这里，而字段清单变了这条测试就会红。
 */
class DiagnosticReportTest {

    private fun data(
        serverEvents: List<DiagnosticReport.EventLine>?,
        error: String?
    ) = DiagnosticReport.DiagnosticData(
        generatedAt = 1_756_000_000_000L,
        appVersion = "1.1.0",
        applicationId = "com.yunyi.smshub",
        deviceId = "android-abc",
        serverUrl = "http://192.168.1.10:8080",
        buildInfo = "Android 14（API 34）；Xiaomi 23127PN0CC；ROM UKQ1",
        state = listOf(DiagnosticReport.Field("注册状态", "已注册")),
        permissions = listOf(DiagnosticReport.Field("接收短信", "已授予")),
        simSummary = "2 张：卡1号码未知，卡2已读到",
        selfTest = listOf(SelfTestItem("短信接收权限", true, "已授予", null)),
        queueLine = "未上传 1 条，其中到期待传 1 条。",
        queue = listOf(
            DiagnosticReport.QueueLine(
                id = 7, senderMasked = "106****0000", phoneMasked = "138****8000",
                receiveTime = 1_756_000_000_000L, status = "pending", retryCount = 2,
                nextRetryAt = 1_756_000_060_000L, contentBytes = 96
            )
        ),
        localEvents = listOf(
            DiagnosticReport.EventLine(
                at = 1_756_000_000_000L, level = "info", typeLabel = "已入库",
                senderMasked = "106****0000", phoneMasked = "138****8000", reason = "未命中采集关键词"
            )
        ),
        serverEvents = serverEvents,
        serverEventsError = error,
        counts = mapOf("queueOutstanding" to 1, "eventLog" to 37)
    )

    @Test
    fun rendersAllSections() {
        val text = DiagnosticReport.render(data(emptyList(), null))

        assertTrue(text.contains("=== 短信网关诊断包 ==="))
        assertTrue(text.contains("--- 1. 配置与状态 ---"))
        assertTrue(text.contains("--- 4. 本地队列 ---"))
        assertTrue(text.contains("--- 5. 本地运行日志"))
        assertTrue(text.contains("--- 7. 机器可读（JSON）---"))
        // 自检结果要能看出通过与否
        assertTrue(text.contains("[通过] 短信接收权限"))
    }

    @Test
    fun queueLineCarriesByteCountNotContent() {
        val text = DiagnosticReport.render(data(emptyList(), null))

        // 正文字节数在，正文本身不在
        assertTrue(text.contains("96"))
        assertTrue(text.contains("正文字节数"))
    }

    @Test
    fun missingServerLogSpellsOutTheReason() {
        // 静默省略会让读者分不清「服务端一条都没有」与「没取到」——
        // 而那正是这份报告最需要回答的那类问题。
        val text = DiagnosticReport.render(data(null, "HTTP 401"))

        assertTrue(text.contains("未取到：HTTP 401"))
    }

    @Test
    fun fetchedServerLogIsRendered() {
        val text = DiagnosticReport.render(
            data(
                listOf(
                    DiagnosticReport.EventLine(
                        1_756_000_000_000L, "warn", "重复", null, null, "已存在"
                    )
                ),
                null
            )
        )

        assertFalse(text.contains("未取到"))
        assertTrue(text.contains("重复"))
    }

    @Test
    fun jsonSectionCarriesCountsAndFetchFlag() {
        val text = DiagnosticReport.render(data(emptyList(), null))

        assertTrue(text.contains("\"serverEventLogFetched\":true"))
        assertTrue(text.contains("\"queueOutstanding\":1"))
    }

    @Test
    fun queueLineHasNoContentOrLocalMessageIdField() {
        // 结构上的红线：队列行只允许「自增 id + 打码后的号码 + 状态 + 元数据 + 字节数」。
        // 加一个 content 或 localMessageId 字段，这条测试就红 —— 那正是我们要的：
        // 加字段的人不一定会想起「这个文件会离开设备」。
        // 滤掉 `$` 开头的合成字段：Compose 编译器会给 data class 加一个 `$stable`，
        // 而它不是这条测试关心的东西（真实字段才是）。
        val fields = DiagnosticReport.QueueLine::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }

        assertFalse("队列行不得带正文", fields.contains("content"))
        assertFalse("队列行不得带 localMessageId（正文哈希的另一种形态）", fields.contains("localMessageId"))
        // 用「无序相等」而不是逐位相等：declaredFields 的顺序在 JVM 上不保证与
        // 声明顺序一致，把它当不变量只会在换编译器版本时凭空红一次。
        assertEquals(
            "队列行的字段清单变了就回来看一眼：新增的每一项都得先过脱敏",
            setOf(
                "id", "senderMasked", "phoneMasked", "receiveTime",
                "status", "retryCount", "nextRetryAt", "contentBytes"
            ),
            fields.toSet()
        )
    }
}
