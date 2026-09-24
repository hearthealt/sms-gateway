package com.smsgateway.app.qr

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * CameraX 取景 + 解码。扫码的两个入口（「配置二维码」与「扫码连接」）共用这一个实现。
 *
 * <p>刻意只有一份：相机这里有几处容易踩的细节（取景比例、分析器清理、executor 关闭），
 * 复制第二份的结果必然是两边各自演化，其中一边先坏掉，而坏的往往是少用的那个入口。
 *
 * <p>用 AndroidView + PreviewView 而不是 Compose 原生组件：camera-compose 直到
 * CameraX 1.5.0 才稳定，而 1.5.0 要求 compileSdk 35，本项目停留在 34
 * （见 build.gradle.kts 的说明）。
 *
 * @param onDecoded 解出一段文本时回调。调用方负责处理解析结果 ——
 *                  这里只管「解出来了」这一件事。
 * @param resetSignal 变化一次就把解码闩锁复位（见 [QrAnalyzer.reset]）。
 *                  调用方在「解出来的东西不是我要的码」时必须让它变一下：那种情况下
 *                  这个组件**不会**离开组合（scanning 在同一帧里先 false 后 true，
 *                  中间不发生重组），停了工的分析器会一直被留着。
 */
@Composable
fun CameraScanner(onDecoded: (String) -> Unit, resetSignal: Int = 0) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyzer = remember { QrAnalyzer(onDecoded) }
    val previewView = remember {
        PreviewView(context).apply {
            // 默认的 FILL_CENTER 会裁切画面，导致「用户对准的区域」与「实际分析的区域」
            // 不一致；FIT_CENTER 保证看到的都能被分析到。
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // 不写 remember(resetSignal)：重建 QrAnalyzer 只是换了个没人引用的对象，
    // ImageAnalysis 里装的仍是旧那个（setAnalyzer 只在 bind 时执行一次）。
    // 复位必须打在**同一个**实例上。
    LaunchedEffect(resetSignal) { analyzer.reset() }

    DisposableEffect(lifecycleOwner, context) {
        val executor = Executors.newSingleThreadExecutor()
        var provider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        var disposed = false

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                analysis = ImageAnalysis.Builder()
                    // 新帧到来时丢弃积压的旧帧。不设这个，队列会无限增长直至 OOM。
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor) { image -> analyzer.analyze(image) } }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("CameraScanner", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysis?.clearAnalyzer()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
