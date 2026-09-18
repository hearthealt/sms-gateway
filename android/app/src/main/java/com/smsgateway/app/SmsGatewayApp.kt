package com.smsgateway.app

import android.app.Application
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
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

        // 进程也可能由 WorkManager 或广播接收器拉起，那时 DashboardViewModel 不会被创建，
        // 必须在这里按已保存的配置把网络客户端与设备状态装配好。
        RetrofitClient.ensureConfigured(this)
        DeviceStatus.ensureLoaded(this)
        // 同上：Worker / 短信接收器会在进程刚起来时判断「网关在不在跑」，
        // 而那时没有任何界面组件被创建过。
        GatewayState.ensureLoaded(this)

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
