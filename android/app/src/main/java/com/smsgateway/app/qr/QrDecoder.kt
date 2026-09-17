package com.smsgateway.app.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * QR 解码。
 *
 * 刻意不使用任何 Android 类型 —— 这样它可以被纯 JVM 单测直接调用。
 * 这一点很重要：条带跨距（rowStride）相关的错误在模拟器上永远暴露不出来
 * （模拟器上 rowStride 恰好等于 width），只有在真实设备上才会表现为
 * 「图始终解不出来」。能覆盖它的唯一办法，就是用一段人造的、带填充跨距的
 * 缓冲区直接喂给这个函数。
 */
object QrDecoder {

    private val HINTS: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8"
    )

    /**
     * @param data      Y 平面原始数据（planes[0].buffer）
     * @param width     图像宽度
     * @param height    图像高度
     * @param rowStride planes[0].rowStride —— **不是 width**。
     *                  很多设备的每一行末尾有填充字节，把 width 当作跨距会让图像逐行左移，
     *                  表现为永远解不出码；而模拟器上两者相等，所以本机测不出来。
     * @return 解出的文本；没解出返回 null。
     */
    fun decodeYPlane(data: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        // PlanarYUVLuminanceSource 的第二个参数就是跨距，内部按
        // data[(y + top) * dataWidth + left + x] 取像素，所以直接把 rowStride 传进去
        // 即可零拷贝，不需要自己按行压缩数组。
        val source = PlanarYUVLuminanceSource(
            data, rowStride, height,
            0, 0, width, height,
            false
        )

        return try {
            val reader = MultiFormatReader().apply { setHints(HINTS) }
            reader.decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
        } catch (e: ReaderException) {
            // 没找到码是最常见的情况（每一帧都可能没对准），不是异常
            null
        } catch (e: Exception) {
            null
        }
    }
}
