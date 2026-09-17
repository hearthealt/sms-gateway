package com.smsgateway.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Arrays

/**
 * QrDecoder 的纯 JVM 测试。
 *
 * 存在的意义：Y 平面的行跨距（rowStride）在模拟器上恰好等于图像宽度，所以
 * 「把 width 当成跨距」这个 bug 在模拟器上永远复现不出来，只在真实设备上表现为
 * 「图始终扫不出来」。这里用一段人造的、带填充跨距的缓冲区把它固定住。
 */
class QrDecoderTest {

    private val content = """{"url":"http://192.168.1.100:8080","deviceName":"测试机"}"""

    /**
     * 把二维码画进一段 Y 平面数据里。
     * rowStride 通常大于 width —— 每行末尾的填充字节是真实设备上的常态。
     */
    private fun renderYPlane(size: Int, rowStride: Int): ByteArray {
        // CHARACTER_SET 必须显式指定：ZXing 默认 ISO-8859-1，中文会直接编码错。
        // 生产代码 QrEncoder 里同样设了这个（载荷可能带中文设备名），两边必须一致，
        // 否则会出现「自己生成的码自己扫不出来」。
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val plane = ByteArray(rowStride * size)
        Arrays.fill(plane, 0xFF.toByte())          // 白底 = 高亮度

        for (y in 0 until size) {
            for (x in 0 until size) {
                if (matrix.get(x, y)) {
                    plane[y * rowStride + x] = 0x00  // 黑点 = 低亮度
                }
            }
        }
        return plane
    }

    @Test
    fun `跨距等于宽度时能解出`() {
        val size = 300
        val plane = renderYPlane(size, rowStride = size)

        assertEquals(content, QrDecoder.decodeYPlane(plane, size, size, rowStride = size))
    }

    @Test
    fun `跨距带填充时仍能解出`() {
        val size = 300
        // 41 是刻意选的奇数：既不是宽度也不是宽度的整数倍，避免碰巧对齐
        val rowStride = size + 41
        val plane = renderYPlane(size, rowStride)

        assertEquals(content, QrDecoder.decodeYPlane(plane, size, size, rowStride))
    }

    /**
     * 反向验证：把 width 当作跨距就应该解不出来。
     *
     * 这条断言的作用不是测试产品代码，而是证明上面那个用例真的有意义 ——
     * 如果传错参数也能解出，说明这个测试根本挡不住它要挡的 bug。
     */
    @Test
    fun `把宽度当跨距会解不出来`() {
        val size = 300
        val rowStride = size + 41
        val plane = renderYPlane(size, rowStride)

        assertNull(QrDecoder.decodeYPlane(plane, size, size, rowStride = size))
    }

    @Test
    fun `空白图像返回 null 而不是抛异常`() {
        val size = 64
        val plane = ByteArray(size * size) { 0xFF.toByte() }

        assertNull(QrDecoder.decodeYPlane(plane, size, size, size))
    }
}
