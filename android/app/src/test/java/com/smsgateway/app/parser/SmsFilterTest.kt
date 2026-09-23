package com.smsgateway.app.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集过滤。
 *
 * 这道闸门的失败方向是不对称的：**漏掉一条验证码短信，服务端根本看不到它**，
 * 后面再好的提取规则也救不回来（短信没上传）；而多放进来一条噪音，
 * 只是多一行记录，服务端的采集规则还能拦。
 *
 * 所以这里的用例重点在「该过的一律要过」，误放只在英文关键词上守一条底线。
 */
class SmsFilterTest {

    @Test
    fun `中文关键词照常通过`() {
        assertTrue(SmsFilter.shouldCollect("10690333", "【XX】您的验证码是 123456"))
        assertTrue(SmsFilter.shouldCollect("10690333", "校验码 123456，请勿泄露"))
        assertTrue(SmsFilter.shouldCollect("10690333", "动态码 123456"))
    }

    @Test
    fun `原先漏掉的中文关键词现在要过`() {
        // 服务端的 CodeExtractor 本来就认「安全码」，但短信此前在客户端就被丢了，
        // 服务端根本收不到这条。
        assertTrue(SmsFilter.shouldCollect("10690333", "您的安全码是 483920"))
        assertTrue(SmsFilter.shouldCollect("10690333", "认证码 123456"))
        // 繁体：港台短信此前一律漏
        assertTrue(SmsFilter.shouldCollect("10690333", "您的驗證碼是 123456"))
        assertTrue(SmsFilter.shouldCollect("10690333", "您的認證碼是 123456"))
    }

    @Test
    fun `英文裸 code 要过，但字母数字码形态不受影响`() {
        // 原先只认字面 "verification code"，这几条全漏
        assertTrue(SmsFilter.shouldCollect("Telegram", "Telegram code: 12345"))
        assertTrue(SmsFilter.shouldCollect("Uber", "Your Uber code: 1234"))
        assertTrue(SmsFilter.shouldCollect("GitHub", "Your verification code is A3F9K2"))
        assertTrue(SmsFilter.shouldCollect("X", "otp 123456"))
        assertTrue(SmsFilter.shouldCollect("X", "Your PIN is 1234"))
    }

    @Test
    fun `关键词与数字直接相连也要过 —— 用词边界会在这里漏掉`() {
        // `\bcode\b` 在 "code123456" 上匹配不到：`e` 与 `1` 都是词字符，中间没有边界。
        // 边界用的是「两侧都不是字母」，所以这种写法照样能过，而 `codes` / `encode` 仍被挡。
        assertTrue(SmsFilter.shouldCollect("X", "Your code123456 is ready"))
    }

    @Test
    fun `英文关键词不能被更长的单词带进来`() {
        // contains("code") 会让这些全部命中，而英文短信里它们的出现概率远高于验证码。
        // 这是放宽成裸 code 之后必须守住的底线。
        assertFalse(SmsFilter.shouldCollect("X", "Your postcode is 200000"))
        assertFalse(SmsFilter.shouldCollect("X", "This message is encoded"))
        assertFalse(SmsFilter.shouldCollect("X", "Failed to decode the file"))
        assertFalse(SmsFilter.shouldCollect("X", "You have pinned a chat"))
    }

    @Test
    fun `普通短信仍然被拦下`() {
        assertFalse(SmsFilter.shouldCollect("10086", "您本月话费 58 元"))
        assertFalse(SmsFilter.shouldCollect("95588", "您的账户发生一笔支出 100 元"))
    }

    @Test
    fun `发件人里带关键词也算命中`() {
        // 判定对象是 "发件人 + 正文"，与原先一致
        assertTrue(SmsFilter.shouldCollect("验证码中心", "123456"))
    }
}
