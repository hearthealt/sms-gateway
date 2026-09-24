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
import com.smsgateway.app.util.EventLog
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

        /**
         * 单轮工作的最大尝试次数，超过就收手，不让 WorkManager 无限重试下去。
         *
         * 8 次：WorkManager 的退避是 10s 起翻倍，第 8 次约 21 分钟，正好覆盖行内退避
         * 15 分钟的封顶 —— 否则「有行但都还没到点」会一直 retry 到收手，白白多跑几轮
         * （每一轮只花一次 COUNT，但仍是一次进程唤醒）。
         */
        private const val MAX_ATTEMPTS = 8

        /** 已上传的行在本地保留多久。到期由 [pruneOldUploads] 清掉。 */
        private const val UPLOADED_RETENTION_DAYS = 7L

        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        /**
         * 只在**没有**在跑或在排的任务时才排一次。
         *
         * 周期补传用这个而不是 [enqueue]：`REPLACE` 会取消正在跑的那一轮（半程上传白做，
         * 靠服务端幂等才不丢），而且重建任务链会把 `runAttemptCount` 归零 ——
         * 「不让 WorkManager 无限重试下去」那条收手守卫于是**在网关运行期间永远到不了**，
         * 一条必然失败的行会每 5 分钟被真发一次请求，与那段注释的承诺正好相反。
         * `KEEP` 则只在链条已经结束（跑完/放弃）时才重新点火，正是补传要的语义。
         */
        fun enqueueIfIdle(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
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
                .enqueueUniqueWork(WORK_NAME, policy, workRequest)
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

                // 剪枝放在判空**之前**。原先挂在下面，于是「没到点就提前回来」的那一轮
                // 会把剪枝一起跳过 —— 一台长期只在重试失败、没有新短信的设备，
                // 已上传的行与过期事件就再也不会被清掉。
                pruneOldUploads(dao)
                EventLog.prune(context)

                val pendingSms = dao.getPendingSms(System.currentTimeMillis())

                if (pendingSms.isEmpty()) {
                    // 「队列空了」和「有短信但都还没到重试时刻」是两件完全不同的事，
                    // 原先都走 success —— 而这一支正是重试链静默断裂的地方。
                    //
                    // WorkManager 的退避是 10s 起翻倍（回到 T0+10、T0+30、T0+70…），
                    // 行内退避是 10/30/60/300/900s 的**绝对截止时刻**。第三轮回访
                    // （T0+30）必然早于行内的第二个截止（T0+40），于是 getPendingSms
                    // 返回空 —— 到这里以 success 收场，整条重试链结束，行还躺在库里
                    // pending，再没有人会来传它。而 runAttemptCount 才 2 < MAX_ATTEMPTS，
                    // 连下面那条「本轮放弃」都跑不到，事件表里一条痕迹都没有。
                    //
                    // 有行、只是还没到点，就交给 WorkManager 继续回来：它才是唯一的
                    // 调度者，nextRetryAt 只负责**否决**某一次运行，不负责决定何时跑。
                    if (dao.countPending() > 0) {
                        Log.i(TAG, "Nothing due yet but queue is not empty, asking WorkManager to come back")
                        return@withContext Result.retry()
                    }
                    Log.d(TAG, "No pending SMS to upload")
                    return@withContext Result.success()
                }

                var allSuccess = true
                for (sms in pendingSms) {
                    // 用 attempt.detail 而不是各写一句笼统的话：日志里「网络异常」这四个字
                    // 对排查没有任何帮助 —— 500、502、超时、DNS 失败要处理的事完全不同，
                    // 而这张表存在的全部目的就是事后能说清「当时到底怎么了」。
                    val attempt = uploadSingleSms(sms)
                    when (attempt.outcome) {
                        Outcome.SUCCESS -> {
                            dao.updateStatus(sms.id, "uploaded")
                            // 通知界面立刻重算「待上传」与「今日短信/验证码」：
                            // 上传只要几秒，等下一个 5 秒/30 秒轮询的话，现场看到的是
                            // 「验证码进来了，界面上什么都没发生」。
                            UploadEvents.notifyUploaded()
                            EventLog.writeNow(
                                context, EventLog.UPLOAD_OK, EventLog.LEVEL_INFO,
                                sender = sms.sender, phone = sms.phone, reason = attempt.detail, smsId = sms.id
                            )
                            Log.d(TAG, "Uploaded SMS: ${sms.id}")
                        }

                        Outcome.DISABLED -> {
                            DeviceStatus.set(context, true)
                            EventLog.writeNow(
                                context, EventLog.UPLOAD_DEVICE_DISABLED, EventLog.LEVEL_WARN,
                                sender = sms.sender,
                                phone = sms.phone,
                                reason = "${attempt.detail}：设备已被管理员禁用",
                                smsId = sms.id
                            )
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
                            EventLog.writeNow(
                                context, EventLog.UPLOAD_TOKEN_REJECTED, EventLog.LEVEL_ERROR,
                                sender = sms.sender,
                                phone = sms.phone,
                                reason = "${attempt.detail}：令牌被拒，已停止上报",
                                smsId = sms.id
                            )
                            // 带上这次请求用的那份令牌：AuthState 会先核对它是不是**当前**
                            // 令牌，不是就什么都不做 —— 一条迟到的 401 不该删掉刚重新注册
                            // 拿到的新令牌（见 AuthState.markTokenRejected）。
                            //
                            // 注：AuthInterceptor 其实已经先一步报过一次了（401 是它先看到的），
                            // 这里是第二条路径，两者都走同一个判据，重复调用是幂等的。
                            AuthState.markTokenRejected(context, attempt.authToken)
                            return@withContext Result.success()
                        }

                        Outcome.INVALID -> {
                            // 服务端明确拒绝这条内容（400/422），重试多少次结果都一样，标终态。
                            // 这是 failed 唯一该出现的地方。
                            dao.updateRetry(sms.id, "failed", sms.retryCount + 1, 0)
                            EventLog.writeNow(
                                context, EventLog.UPLOAD_REJECTED, EventLog.LEVEL_ERROR,
                                sender = sms.sender,
                                phone = sms.phone,
                                reason = "${attempt.detail}：服务端拒绝这条内容",
                                smsId = sms.id
                            )
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
                            // 只在**首次**失败时记一条。逐次记的话，一条卡住的短信会按
                            // 10s/30s/1m/5m/15m 的退避节奏把整页日志刷满，真正重要的事件
                            // 反而被埋掉 —— 而「它失败过几次」队列行上本来就有 retryCount。
                            if (sms.retryCount == 0) {
                                EventLog.writeNow(
                                    context, EventLog.UPLOAD_RETRYING, EventLog.LEVEL_WARN,
                                    sender = sms.sender, phone = sms.phone, reason = attempt.detail, smsId = sms.id
                                )
                            }
                            Log.w(TAG, "Failed to upload SMS: ${sms.id}, retry ${sms.retryCount + 1}")

                            // 连不上服务器（连接超时、DNS 失败、连接被拒）时**立刻收手**，
                            // 不再逐条去撞同一堵墙。
                            //
                            // 不加这一条，一条积压 20 条以上的队列会这样跑：每条都要等满
                            // 30 秒的 connect/read 超时，一轮就是 10 分钟以上 —— 而
                            // WorkManager 的 CoroutineWorker 上限正好是 10 分钟，
                            // 到点被系统中止。中止发生在上传循环中途，结果是这一轮
                            // 一条都没记账（后面那些行的 retryCount 全没动），
                            // WorkManager 那边还叠加了一次「被中止」的重试，
                            // 下一轮再从第一条开始等 30 秒。整个队列永远推不动。
                            //
                            // 只对**连接层**失败收手，HTTP 5xx 继续往下走：那说明对面活着，
                            // 只是这一条（或这一个时刻）出了问题，下一条完全可能成功。
                            if (attempt.connectionFailed) {
                                Log.w(
                                    TAG,
                                    "Server unreachable, stopping this round to stay within " +
                                        "WorkManager's execution limit; ${pendingSms.size} due row(s) left"
                                )
                                break
                            }
                        }
                    }
                }

                when {
                    allSuccess -> Result.success()

                    // 退避封顶到 WorkManager 的 5 小时之后会一直重试下去，永不停止 ——
                    // 一条服务端必然拒绝的短信（例如撞上唯一约束返回 500）会让设备此后
                    // 每隔 5 小时被唤醒一次，永远，纯烧电。
                    //
                    // 放弃的是**这一轮**，不是这条数据：失败的行写回的是 pending，
                    // 仍留在队列里，下次有新短信进来重新 enqueue 时会再带上它。
                    runAttemptCount >= MAX_ATTEMPTS -> {
                        // 收手是「这一轮放弃了」，不是「这些短信没了」—— 但仍然值得留痕：
                        // 它意味着有一批短信在退避里卡了很久，而界面上只看得到一个静态的
                        // 「待上传 N」，看不出已经停滞。
                        EventLog.writeNow(
                            context, EventLog.UPLOAD_ROUND_GAVE_UP, EventLog.LEVEL_WARN,
                            reason = "本轮放弃，仍有 ${dao.getOutstandingCountSync()} 条待上传"
                        )
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

    /**
     * 一次上传的结果，外加**一句能拿去排查的细节**。
     *
     * 光有枚举不够：同样是 TRANSIENT，`HTTP 502`（对面网关挂了）和
     * `SocketTimeoutException`（网络不通）要做的事完全不同，而日志里只写
     * 「网络异常」等于什么都没说。所以把状态码 / 异常类名一路带到事件表里。
     *
     * detail 一律是**受控文案**（`HTTP NNN` 或异常类名），绝不拼 errorBody ——
     * 那是一段不可控的字节流，而事件表要留 7 天。
     *
     * @param connectionFailed 失败发生在**连接层**（请求根本没到对面）。
     *   与「对面回了 5xx」是两回事，调用方对这两者的处置不同：前者要立刻停手
     *   （见上传循环里的说明），后者要继续往下试。
     */
    private data class UploadAttempt(
        val outcome: Outcome,
        val detail: String,
        val connectionFailed: Boolean = false,
        /**
         * 这次请求实际带上去的那份令牌，仅在 [Outcome.UNAUTHORIZED] 时有意义。
         *
         * 必须把它一起带回来给 [AuthState.markTokenRejected]：那里要拿它比对**当前**令牌，
         * 被拒的不是当前这一份就不该清库。用「处理 401 那一刻读到的 prefs 值」代替是不行的
         * —— 那时可能已经换成了重新注册拿到的新令牌，照着清就是刚注册完又被踢回未注册。
         */
        val authToken: String? = null
    )

    private suspend fun uploadSingleSms(sms: SmsQueueEntity): UploadAttempt {
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
                receiveTime = sms.receiveTime.toString()
            )
            // 发请求之前把当前令牌记下来：401 时要用它去比对「被拒的是不是现在这一份」。
            // 放在这里而不是收到 401 之后再读，是因为那中间可能已经有别的路径换过令牌。
            val authToken = RetrofitClient.getDeviceToken()

            val idempotencyKey = "$deviceId:${sms.localMessageId}"
            val response = api.uploadSms(idempotencyKey, request)

            val detail = "HTTP ${response.code()}"
            val outcome = when {
                response.isSuccessful -> Outcome.SUCCESS
                response.code() == 403 -> Outcome.DISABLED
                response.code() == 401 -> Outcome.UNAUTHORIZED
                response.code() == 400 || response.code() == 422 -> Outcome.INVALID
                else -> Outcome.TRANSIENT
            }
            UploadAttempt(outcome, detail, authToken = authToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload error for SMS ${sms.id}", e)
            // 异常不带 message：IOException 的 message 里可能带完整 URL 或对端响应片段，
            // 而类名已经足够区分「超时 / DNS / 连接被拒」这几类。
            //
            // 走到 catch 就是「请求没换回一个 HTTP 响应」，也就是连接层的问题 ——
            // 传 connectionFailed = true 让调用方据此收手。
            UploadAttempt(Outcome.TRANSIENT, e.javaClass.simpleName, connectionFailed = true)
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
