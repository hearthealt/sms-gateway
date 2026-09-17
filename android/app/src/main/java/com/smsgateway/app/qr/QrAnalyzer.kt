package com.smsgateway.app.qr

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX 帧分析器：从每一帧的 Y 平面里找二维码。
 *
 * 节流分三层，缺一不可：
 *  1. ImageAnalysis 侧用 STRATEGY_KEEP_ONLY_LATEST（在绑定处设置），新帧到来时丢弃积压的旧帧
 *  2. 这里的时间闸，把解码频率上限压在 ~3 次/秒 —— 这才是真正的 CPU 上限
 *  3. 命中后的闩锁，避免出来结果后还在继续解码
 *
 * 刻意**不**在 analyze() 里另开线程异步解码：那样需要跨线程持有 ImageProxy，
 * 是缓冲区泄漏和相机卡死的经典成因。同步解码 + 时间闸 + KEEP_ONLY_LATEST
 * 吞吐相同，却没有这些风险。
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val lastAttemptAt = AtomicLong(0L)
    private val finished = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (finished.get()) return
            if (image.format != android.graphics.ImageFormat.YUV_420_888) return

            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastAttemptAt.get() < THROTTLE_MS) return
            lastAttemptAt.set(now)

            val plane = image.planes.getOrNull(0) ?: return

            // position 在部分设备上不是 0，不 rewind 会读到截断/错位的数据
            val buffer = plane.buffer
            buffer.rewind()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val text = QrDecoder.decodeYPlane(
                data = data,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride.coerceAtLeast(image.width)
            )

            if (text != null && finished.compareAndSet(false, true)) {
                onResult(text)
            }
        } catch (e: Throwable) {
            // 异常一旦逃出 analyze()，CameraX 会永久停止投递帧 —— 相机看起来就是卡死，
            // 而且不会有任何崩溃日志。所以这里必须兜住 Throwable。
            Log.w(TAG, "analyze failed", e)
        } finally {
            // 漏掉这一行的后果：缓冲区永不释放，一两帧之后预览直接冻结。
            image.close()
        }
    }

    private companion object {
        const val TAG = "QrAnalyzer"
        const val THROTTLE_MS = 300L
    }
}
