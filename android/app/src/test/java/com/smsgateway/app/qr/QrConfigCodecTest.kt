package com.smsgateway.app.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置二维码载荷的约定。
 *
 * 这份测试是给「扫码不该改设备名」这条规矩立的桩。
 *
 * 名字现在跟随手机本身（见 DeviceName），二维码只是「把服务器地址从一台手机
 * 交给另一台」的通道。早先它顺带携带 deviceName，于是扫谁的码就顶谁的名字 ——
 * 现场表现为两台手机在管理后台同名，谁都分不出哪台是哪台。
 */
class QrConfigCodecTest {

    @Test
    fun `导出的载荷里没有设备名`() {
        val payload = QrConfigCodec.encode("http://192.168.1.100:8080")

        assertFalse(
            "二维码不该携带设备名：带了就会把别人机器的名字写到扫码方身上",
            payload.contains("deviceName")
        )
    }

    @Test
    fun `解析带设备名的载荷时不报错`() {
        // 管理后台签发的恢复码、以及早先版本生成的码里都还带着 deviceName。
        // 解析层必须忽略这个字段（Gson 对未知字段就是这样），不能整条判为非法 ——
        // 否则现场那些已经印出来/存下来的码会全部失效。
        val raw = """{"url":"http://192.168.1.100:8080","deviceName":"别人的手机"}"""

        val result = QrConfigCodec.parse(raw)

        assertTrue("带设备名的旧载荷仍应可用", result is QrParseResult.Ok)
        assertEquals(
            "http://192.168.1.100:8080",
            (result as QrParseResult.Ok).config.url
        )
    }

    @Test
    fun `恢复码载荷照常解析且仍然带着名字字段也被忽略`() {
        val raw = """
            {"url":"http://192.168.1.100:8080","deviceName":"别人的手机",
             "deviceId":"android-abcdef","enrollSecret":"c2VjcmV0"}
        """.trimIndent()

        val config = (QrConfigCodec.parse(raw) as QrParseResult.Ok).config

        assertTrue("恢复码仍然要被识别出来", config.hasIdentity)
        assertEquals("android-abcdef", config.deviceId)
    }

    @Test
    fun `QrConfig 里不该再有名字字段`() {
        // 结构层面的桩：这条断言的是「类的字段表」，而不是某个值。
        // 谁把 deviceName 加回 QrConfig，这里就会红 —— 而扫码写名字那条老路
        // 正是从那个字段开始的。
        val fieldNames = QrConfig::class.java.declaredFields.map { it.name }

        assertFalse(
            "QrConfig 一旦重新带上 deviceName，扫码就会再次改写本机设备名；" +
                "设备名应当只由 DeviceName 从手机系统读取",
            fieldNames.contains("deviceName")
        )
    }
}
