package com.smsgateway.app.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.database.SmsQueueEntity
import com.smsgateway.app.model.SmsUploadRequest
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
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
                            Log.d(TAG, "Uploaded SMS: ${sms.id}")
                        }

                        Outcome.DISABLED -> {
                            DeviceStatus.set(context, true)
                            Log.w(TAG, "Server reports device disabled, parking queue")
                            return@withContext Result.success()
                        }

                        Outcome.UNAUTHORIZED -> {
                            // 令牌失效但设备标识还在。不重试，等重新注册拿回新令牌。
                            Log.w(TAG, "Token rejected, parking queue until re-register")
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

                if (allSuccess) Result.success() else Result.retry()
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
