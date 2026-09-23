package com.smsgateway.app

import android.app.Application
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.network.NetworkWatch
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsGatewayApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)

        // 进程启动留痕。放在最前面（DB 刚就绪、其余初始化还没跑）：
        // 万一后面的初始化抛异常，这一条也已经在写队列里了 —— 而「进程起来了但没初始化完」
        // 正是最需要看见的情况。
        //
        // 不节流，每次冷启动都记一条：进程被系统反复拉起来（WorkManager、短信广播）
        // 本身就是重要信号，压掉它等于把「这台机器一直在被唤醒」这件事藏起来。
        // 与「网关启动」区分：只看到这一条 = 进程起来了但网关没起。
        EventLog.write(
            this, EventLog.APP_STARTED, EventLog.LEVEL_INFO,
            reason = "进程启动"
        )

        // 进程也可能由 WorkManager 或广播接收器拉起，那时 DashboardViewModel 不会被创建，
        // 必须在这里按已保存的配置把网络客户端与设备状态装配好。
        RetrofitClient.ensureConfigured(this)
        DeviceStatus.ensureLoaded(this)
        // 同上：Worker / 短信接收器会在进程刚起来时判断「网关在不在跑」，
        // 而那时没有任何界面组件被创建过。
        GatewayState.ensureLoaded(this)
        // 网络回调要在进程早期注册：探测那边靠它判断「默认网络是不是刚切过来」，
        // 注册晚了的话第一个回调要等下一次网络变化才来（理由见 NetworkWatch）。
        NetworkWatch.ensureStarted(this)

        sweepStrandedRowsOnce()
    }

    /**
     * 一次性修复：把早期版本错标成 failed 的行扫回 pending。
     *
     * 旧代码在上传失败时写 failed，而 DAO 查询只取 pending，等于一次失败就永久搁浅、
     * 再也不会被重试。这些行不会自愈，只能升级后补一次。用 prefs 标记保证只做一次
     * （失败则不标记，下次启动再试）。
     */
    private fun sweepStrandedRowsOnce() {
        if (DevicePrefs.hasSweptStrandedRows(this)) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val swept = database.smsQueueDao().sweepStrandedRows()
                Log.i(TAG, "Swept $swept stranded rows back to pending")
                if (swept > 0) {
                    SmsUploadWorker.enqueue(this@SmsGatewayApp)
                }
                DevicePrefs.markStrandedRowsSwept(this@SmsGatewayApp)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sweep stranded rows", e)
            }
        }
    }

    companion object {
        private const val TAG = "SmsGatewayApp"

        lateinit var instance: SmsGatewayApp
            private set
    }
}
