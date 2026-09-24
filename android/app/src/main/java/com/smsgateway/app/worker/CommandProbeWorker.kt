package com.smsgateway.app.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.HeartbeatSender
import java.util.concurrent.TimeUnit

/**
 * 「网关已停止」时的低频心跳，只为取回一条指令：**启动网关**。
 *
 * ### 它补的是一个死结
 *
 * 远程指令搭心跳响应下发（见服务端 `DeviceCommandService`）—— 而用户把网关停掉之后，
 * **没有人再发心跳**。于是「启动」这条最需要在「已停」状态下送达的指令，恰好送不到：
 * 管理员在控制台上点它，设备永远收不到，而界面上只会看到一条停在「待下发」的记录。
 *
 * 这个周期任务就是那条唯一还活着的通道。它做的事只有一件：以 `commandProbe = true`
 * 发一次心跳。服务端在那种心跳上**只下发「启动网关」**，并且**完全不更新在线状态** ——
 * 否则后台会在用户明明按了停止之后，每 15 分钟把设备显示成在线一次。
 *
 * ### 为什么是 15 分钟
 *
 * WorkManager 周期任务的最小间隔就是 15 分钟，没有更快的选项。这个延迟是可以接受的：
 * 「启动网关」不紧急，而真正的实时通道（网关在跑时的 30 秒心跳）一直都在。
 * Doze 下会更慢，控制台那边也照实说了这一点。
 *
 * ### 随网关起停而收放
 *
 * 网关启动时**取消**它（`GatewayForegroundService.onCreate`），停止时**排上**它
 * （`GatewayForegroundService.stop`）。不收放的后果是：网关正常运行时还多一个
 * 每 15 分钟把一个进程拉起来的闹钟 —— 纯耗电，而且那个探测心跳还会让服务端的
 * 在线状态少更新一次。
 */
class CommandProbeWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "command_probe"

        /** WorkManager 周期任务允许的最小间隔。 */
        private const val INTERVAL_MINUTES = 15L

        /**
         * 排上这个探测任务。
         *
         * 用 [ExistingPeriodicWorkPolicy.KEEP] 而不是 REPLACE：每次点「停止网关」都
         * 重排一次的话，任务的下一次执行时刻会被不断往后推 —— 而用户完全可能反复
         * 开关网关，那这条通道就等于永远不执行。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CommandProbeWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** 网关启动时撤掉：此后有 30 秒一次的正规心跳，探测纯属多余。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        // 进程可能正是被 WorkManager 拉起来的，那时 DashboardViewModel 从未创建过，
        // 内存里的客户端还停在字段初始值（模拟器回环地址、无令牌）。
        RetrofitClient.ensureConfigured(context)

        // 未注册时没有令牌可发，服务端的设备列表里也没有这台设备 —— 没有指令可下发。
        if (!DevicePrefs.isRegistered(context)) {
            return Result.success()
        }

        // 只在网关确实停着时探测。网关被重新拉起来之后（无论是用户点的还是上一条
        // 指令执行的），正规心跳已经接手，这次探测是多余的。
        if (GatewayState.isRunning(context)) {
            return Result.success()
        }

        HeartbeatSender.send(context, probe = true)
        // 任务本身没有失败语义：网络不通就是这一轮没取到，15 分钟后自会再来。
        // 返回 retry 反而会让它按 WorkManager 的退避反复唤醒，纯耗电。
        return Result.success()
    }
}
