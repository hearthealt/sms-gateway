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

    // ---------- 接入口令（「快速连接」） ----------

    @Test
    fun `解析带接入口令的载荷`() {
        val raw = """{"url":"http://192.168.1.100:8080","enrollToken":"abc123"}"""

        val config = (QrConfigCodec.parse(raw) as QrParseResult.Ok).config

        assertEquals("abc123", config.enrollToken)
        assertFalse(
            "带口令不等于带设备身份：口令认的是「服务器准不准你进」，" +
                "不是「你是哪台设备」，两者不能混",
            config.hasIdentity
        )
    }

    @Test
    fun `导出时带上口令，没有口令时不写这个字段`() {
        val withToken = QrConfigCodec.encode("http://192.168.1.100:8080", "abc123")
        assertTrue("服务端启用准入校验时，导出必须带口令", withToken.contains("abc123"))

        // 没有口令时不能写一个空字段：服务端会当成「带了但不匹配」而拒绝，
        // 而不是走「没带」那条放行分支。
        val withoutToken = QrConfigCodec.encode("http://192.168.1.100:8080")
        assertFalse(withoutToken.contains("enrollToken"))

        // 口令仍不能把设备身份捎出去
        assertFalse(withToken.contains("deviceId"))
        assertFalse(withToken.contains("enrollSecret"))
    }

    @Test
    fun `带口令导出时也不夹带设备名`() {
        // 覆盖带口令的新写法：给 encode 加参数时，最容易顺手把别的字段也塞进去。
        val payload = QrConfigCodec.encode("http://192.168.1.100:8080", "abc123")
        assertFalse(payload.contains("deviceName"))
    }

    // ---------- 裸地址（管理后台「复制地址」那条路） ----------

    @Test
    fun `粘贴的纯地址也要能解析`() {
        // 管理后台「快速连接」页上的「复制地址」给的正是这样一段纯文本。
        // 不认它的话，「复制地址」这条设计好的路会当场变成
        // 「不是有效的配置内容」—— 而这个功能有一半是冲着它去的。
        val result = QrConfigCodec.parse("http://192.168.1.100:8080")

        assertTrue("裸地址要能被当作配置接受", result is QrParseResult.Ok)
        assertEquals("http://192.168.1.100:8080", (result as QrParseResult.Ok).config.url)
        assertEquals("裸地址里没有口令", null, result.config.enrollToken)
    }

    @Test
    fun `纯地址会去掉末尾斜杠`() {
        val config = (QrConfigCodec.parse("http://192.168.1.100:8080/") as QrParseResult.Ok).config
        assertEquals("http://192.168.1.100:8080", config.url)
    }

    @Test
    fun `不写 http 前缀也能用`() {
        // 现场手抄、口述、从聊天记录里复制过来的多半就是这一段。要求用户自己想起补
        // "http://" 是在一个纯形式的问题上卡人；而补错成 https:// 反而连不上，
        // 那个错还很难看出来。
        assertEquals(
            "http://192.168.1.100:8080",
            (QrConfigCodec.parse("192.168.1.100:8080") as QrParseResult.Ok).config.url
        )
        assertEquals(
            "http://192.168.1.100",
            (QrConfigCodec.parse("192.168.1.100") as QrParseResult.Ok).config.url
        )
    }

    @Test
    fun `写了非 http 的协议时按原样判错，不要自作主张改掉`() {
        // 补前缀这条兜底只在「没有协议」时生效。用户写了 ftp:// 是有意的（或者是从
        // 别处复制来的一条错地址），把它偷偷改成 http:// 会让人对着一个能用的地址
        // 也搞不懂当初为什么连上/连不上。
        val result = QrConfigCodec.parse("ftp://192.168.1.100")
        assertTrue(result is QrParseResult.Invalid)
        assertTrue((result as QrParseResult.Invalid).reason.contains("http"))
    }

    @Test
    fun `粘进来的垃圾内容仍然被拒`() {
        // 裸地址兜底不能把校验一起兜掉：整段当地址之后照样要过 scheme / host 检查。
        assertTrue(QrConfigCodec.parse("这不是地址") is QrParseResult.Invalid)
        assertTrue(QrConfigCodec.parse("随便什么鬼东西 123") is QrParseResult.Invalid)
        assertTrue(QrConfigCodec.parse("") is QrParseResult.Invalid)
    }

    @Test
    fun `粘贴的连接信息一次带上地址与口令`() {
        // 管理后台启用口令后，「复制地址」单独一段是不完整的 —— 服务端会拒。
        // 后台另给一个「复制连接信息」，内容就是二维码里那一段（JSON），
        // 手机这里必须原样认出来。
        val raw = """{"url":"http://192.168.1.100:8080","enrollToken":"abc123"}"""

        val config = (QrConfigCodec.parse(raw) as QrParseResult.Ok).config

        assertEquals("http://192.168.1.100:8080", config.url)
        assertEquals("abc123", config.enrollToken)
    }
}
