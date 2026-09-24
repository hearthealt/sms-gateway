package com.smsgateway.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断包的脱敏。
 *
 * 这组测试守的是全项目**唯一一个把数据送出设备**的出口：文件经过系统分享面板
 * 进到一个聊天工具里，而对面和云端都会存一份。写进一个 deviceToken 等于把那台设备
 * 交出去（拿它可以冒充设备上报、读取短信）；写进短信正文等于把验证码交出去。
 *
 * 所以这里不测「写的时候记得脱敏」（那是一种会随人而失效的保证），
 * 测的是**生成后的自检会不会拦住它**。
 */
class DiagnosticRedactionTest {

    @Test
    fun assertNoSecretsRejectsLeakedToken() {
        val text = "设备标识：android-abc\ntoken=deadbeef1234\n"

        val error = assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReport.assertNoSecrets(text, listOf("deadbeef1234"))
        }

        // 失败要**响亮**：这条错误本身就是「有人新加了一个字段却忘了脱敏」的信号
        assertTrue(error.message!!.contains("拒绝生成"))
    }

    @Test
    fun assertNoSecretsAcceptsCleanText() {
        val text = "设备标识：android-abc\n号码：138****8000\n"

        DiagnosticReport.assertNoSecrets(text, listOf("13800138000", "deadbeef1234"))
    }

    @Test
    fun assertNoSecretsIgnoresBlankSecrets() {
        // 空串与 null 忽略掉：它们「到处都能匹配上」，不忽略的话任何文本都过不了
        DiagnosticReport.assertNoSecrets("随便什么文本", listOf(null, "", "   "))
    }

    @Test
    fun assertNoSecretsCatchesFullPhoneBehindMaskedForm() {
        // 只打码、没清干净时（例如号码同时又出现在别处），自检必须发现
        val text = "本机号码：138****8000（原始值 13800138000）"

        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticReport.assertNoSecrets(text, listOf("13800138000"))
        }
    }

    @Test
    fun maskNumberKeepsOnlyHeadAndTail() {
        assertEquals("138****8000", DiagnosticReport.maskNumber("13800138000"))
        // **带国家码的要与服务端打出一模一样的结果**：同一个号码在报告前半段
        // （设备端打的）与后半段（服务端打的）长得不一样，读者只会以为那是两个号。
        assertEquals("138****8000", DiagnosticReport.maskNumber("+86 138-0013-8000"))
        assertEquals("138****8000", DiagnosticReport.maskNumber("8613800138000"))
        // 短号：中间段必须整段打掉，否则星号旁边还留着能拼回原号的数字
        assertEquals("123****67", DiagnosticReport.maskNumber("1234567"))
        assertEquals("1****", DiagnosticReport.maskNumber("12345"))
        assertNull(DiagnosticReport.maskNumber(null))
        assertNull(DiagnosticReport.maskNumber("未知"))
    }

    @Test
    fun maskedNumberDropsTheMiddle() {
        val masked = DiagnosticReport.maskNumber("13800138000")!!

        assertFalse(masked.contains("0013"))
        assertFalse(masked.contains("13800138"))
    }
}
