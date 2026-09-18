package com.smsgateway.app.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「刚刚有一条短信上传成功」的进程级通知。
 *
 * 抽出来是因为这条链路上有两个互不相识的组件：上传是 [com.smsgateway.app.worker.SmsUploadWorker]
 * 干的，而界面（DashboardViewModel）要据此立刻重算「待上传」和「今日短信/验证码」。
 * Worker 拿不到 ViewModel 的引用，用一个 StateFlow 当事件通道是本项目既有的做法
 * （见 AuthState、DeviceStatus）。
 *
 * 要解决的问题很具体：上传其实很快（实测从收到短信到入库 7~19 秒），而界面原来
 * 5 秒才看一次本地队列、30 秒才拉一次服务端统计 —— 于是现场看到的是
 * 「验证码到了，待上传还是 0，直到下一次心跳，今日短信才 +1」，
 * 中间那段完全看不出这条码到底进没进来。
 */
object UploadEvents {

    private val _successCount = MutableStateFlow(0L)

    /** 成功上传的累计次数。只用来看「变过没有」，数值本身没有意义。 */
    val successCount: StateFlow<Long> = _successCount.asStateFlow()

    fun notifyUploaded() {
        _successCount.value += 1
    }
}
