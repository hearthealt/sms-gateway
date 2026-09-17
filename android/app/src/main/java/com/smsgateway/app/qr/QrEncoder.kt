package com.smsgateway.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrEncoder {

    /** 定长渲染：太小扫不动，太大在手机上要滚动才能看全。 */
    private const val DEFAULT_SIZE = 720

    fun encode(content: String, size: Int = DEFAULT_SIZE): Bitmap {
        val hints = mapOf(
            // ZXing 默认用 ISO-8859-1，中文设备名会变成乱码
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 25% 容错：屏幕反光和摩尔纹会吃掉不少边缘，M 级(15%)在实拍时偏紧
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q
        )

        // QRCodeWriter 自带 4 模块静默区，不要再自己加白边
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0 until size) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }

        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
