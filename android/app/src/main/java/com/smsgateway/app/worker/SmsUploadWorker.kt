package com.smsgateway.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueDao
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.model.SmsUploadRequest
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.AuthState
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.GatewayState
import com.smsgateway.app.util.UploadEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that uploads pending SMS to the backend.
 * Uses exponential backoff on failure: 10s -> 30s -> 1m -> 5m -> 15m.
 */
class SmsUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SmsUploadWorker"
        private const val WORK_NAME = "sms_upload"

        /** 单轮工作的最大尝试次数，超过就收手，不让 WorkManager 无限重试下去。 */
        private const val MAX_ATTEMPTS = 5

        /** 已上传的行在本地保留多久。到期由 [pruneOldUploads] 清掉。 */
        private const val UPLOADED_RETENTION_DAYS = 7L

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SmsUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS
                )
                .addTag(WORK_NAME)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    workRequest
                )
        }
    }

    /**
     * 单条短信的上传结果。区分这几种是必需的：
     * 都当成失败会让设备对着不可能成功的条件无限重试，都当成成功又会丢数据。
     */
    private enum class Outcome { SUCCESS, DISABLED, UNAUTHORIZED, INVALID, TRANSIENT }

    /**
     * 清掉早就传完的历史行。
     *
     * 原先的 `deleteOldRecords` 只有定义、全项目没有一个调用点（设置页那句注释里说的
     * 「终于接上了」接的其实是 `deleteAllUploaded`，那个只在用户手动点「清理」时才跑）。
     * 于是已上传的行会一直堆在库里，而每一行都存着短信全文与提取出的验证码明文 ——
     * 应用数据体积、云备份体积、以及数据暴露面都随时间只增不减。
     *
     * 放在这里是因为上传工作是唯一会定期跑起来的路径。清库失败不该影响上报，
     * 所以整段吞掉异常只记日志。
     */
    private suspend fun pruneOldUploads(dao: SmsQueueDao) {
        try {
            val cutoff = System.currentTimeMillis() -
                TimeUnit.DAYS.toMillis(UPLOADED_RETENTION_DAYS)
            val removed = dao.deleteOldRecords(cutoff)
            if (removed > 0) {
                Log.i(TAG, "Pruned $removed uploaded rows older than $UPLOADED_RETENTION_DAYS days")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Prune uploaded rows failed", e)
        }
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            // 进程可能正是被 WorkManager 拉起来的，那时 DashboardViewModel 从未被创建，
            // 内存里的客户端还停在字段初始值（模拟器回环地址、无令牌）。
            RetrofitClient.ensureConfigured(context)

            // 未注册时上传多少次都不会成功，用 success 而非 retry 收场：
            // 这个条件不会自愈，retry 只会白烧退避预算和电量。
            if (!DevicePrefs.isRegistered(context)) {
                Log.i(TAG, "Device not registered, deferring upload")
                return@withContext Result.success()
            }

            // 被管理员禁用时同样不发请求、不动任何行。短信留在库里保持 pending，
            // 「待上传」因此能如实反映积压量。重新启用由心跳侦测到后重新触发本 worker。
            DeviceStatus.ensureLoaded(context)
            if (DeviceStatus.isDisabled(context)) {
                Log.i(TAG, "Device disabled, parking queue")
                return@withContext Result.success()
            }

            // 网关停着一行都不动。界面在停止时明确承诺「短信会留在本地，不会上报」，
            // 而 WorkManager 会在进程被拉起时照跑不误 —— 只在接收端挡是不够的：
            // 排进队列的任务会在网关停掉之后才被执行。行保持 pending，
            // 网关重新启动时（GatewayForegroundService.onCreate）会重新排队补传。
            if (!GatewayState.isRunning(context)) {
                Log.i(TAG, "Gateway stopped, parking queue")
                return@withContext Result.success()
            }

            try {
                val dao = AppDatabase.getInstance(context).smsQueueDao()
                val pendingSms = dao.getPendingSms(System.currentTimeMillis())

                if (pendingSms.isEmpty()) {
                    Log.d(TAG, "No pending SMS to upload")
                    return@withContext Result.success()
                }

                var allSuccess = true
                for (sms in pendingSms) {
                    when (uploadSingleSms(sms)) {
                        Outcome.SUCCESS -> {
                            dao.updateStatus(sms.id, "uploaded")
                            // 通知界面立刻重算「待上传」与「今日短信/验证码」：
                            // 上传只要几秒，等下一个 5 秒/30 秒轮询的话，现场看到的是
                            // 「验证码进来了，界面上什么都没发生」。
                            UploadEvents.notifyUploaded()
                            Log.d(TAG, "Uploaded SMS: ${sms.id}")
                        }

                        Outcome.DISABLED -> {
                            DeviceStatus.set(context, true)
                            Log.w(TAG, "Server reports device disabled, parking queue")
                            return@withContext Result.success()
                        }

                        Outcome.UNAUTHORIZED -> {
                            // 令牌失效但设备标识还在。不重试，等重新注册拿回新令牌。
                            //
                            // 同时清掉本地令牌并置一个一次性事件：只打日志的话，
                            // isRegistered 仍是 true、界面一直显示「已注册」，
                            // 而设备其实静默地什么都传不上去。提示由界面消费该事件后发出。
                            Log.w(TAG, "Token rejected, parking queue until re-register")
                            AuthState.markTokenRejected(context)
                            return@withContext Result.success()
                        }

                        Outcome.INVALID -> {
                            // 服务端明确拒绝这条内容（400/422），重试多少次结果都一样，标终态。
                            // 这是 failed 唯一该出现的地方。
                            dao.updateRetry(sms.id, "failed", sms.retryCount + 1, 0)
                            Log.w(TAG, "SMS ${sms.id} rejected by server, marked terminal")
                        }

                        Outcome.TRANSIENT -> {
                            // 必须保持 pending。写 failed 会让这行再也查不出来 —— 查询只取
                            // pending —— 于是重试机制形同虚设，短信静默丢失。
                            allSuccess = false
                            dao.updateRetry(
                                id = sms.id,
                                status = "pending",
                                retryCount = sms.retryCount + 1,
                                nextRetryAt = calculateNextRetry(sms.retryCount)
                            )
                            Log.w(TAG, "Failed to upload SMS: ${sms.id}, retry ${sms.retryCount + 1}")
                        }
                    }
                }

                pruneOldUploads(dao)

                when {
                    allSuccess -> Result.success()

                    // 退避封顶到 WorkManager 的 5 小时之后会一直重试下去，永不停止 ——
                    // 一条服务端必然拒绝的短信（例如撞上唯一约束返回 500）会让设备此后
                    // 每隔 5 小时被唤醒一次，永远，纯烧电。
                    //
                    // 放弃的是**这一轮**，不是这条数据：失败的行写回的是 pending，
                    // 仍留在队列里，下次有新短信进来重新 enqueue 时会再带上它。
                    runAttemptCount >= MAX_ATTEMPTS -> {
                        Log.w(
                            TAG,
                            "Reached max attempts ($runAttemptCount), stopping this run; " +
                                "unsent rows stay pending for the next trigger"
                        )
                        Result.success()
                    }

                    else -> Result.retry()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Upload work failed", e)
                Result.retry()
            }
        }
    }

    private suspend fun uploadSingleSms(sms: SmsQueueEntity): Outcome {
        return try {
            val api = RetrofitClient.getApiService()

            // 从 prefs 取而不是 RetrofitClient：服务端的鉴权走令牌、而设备归属走请求体里的
            // deviceId，两者必须是同一个值，否则会被解析成「设备不存在」直接 500。
            val deviceId = DevicePrefs.deviceId(context).ifBlank { sms.deviceId }

            val request = SmsUploadRequest(
                deviceId = deviceId,
                localMessageId = sms.localMessageId,
                phone = sms.phone,
                sender = sms.sender,
                content = sms.content,
                code = sms.code,
                receiveTime = sms.receiveTime.toString()
            )
            val idempotencyKey = "$deviceId:${sms.localMessageId}"
            val response = api.uploadSms(idempotencyKey, request)

            when {
                response.isSuccessful -> Outcome.SUCCESS
                response.code() == 403 -> Outcome.DISABLED
                response.code() == 401 -> Outcome.UNAUTHORIZED
                response.code() == 400 || response.code() == 422 -> Outcome.INVALID
                else -> Outcome.TRANSIENT
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for SMS ${sms.id}", e)
            Outcome.TRANSIENT
        }
    }

    private fun calculateNextRetry(currentRetry: Int): Long {
        val delays = listOf(
            10_000L,    // 10 seconds
            30_000L,    // 30 seconds
            60_000L,    // 1 minute
            300_000L,   // 5 minutes
            900_000L    // 15 minutes
        )
        val delay = if (currentRetry < delays.size) {
            delays[currentRetry]
        } else {
            delays.last()
        }
        return System.currentTimeMillis() + delay
    }
}
