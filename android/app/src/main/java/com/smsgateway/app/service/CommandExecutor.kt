package com.smsgateway.app.service

import android.content.Context
import android.util.Log
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.model.CommandAckItem
import com.smsgateway.app.model.CommandAckRequest
import com.smsgateway.app.model.CommandPayload
import com.smsgateway.app.model.CommandStatus
import com.smsgateway.app.model.CommandType
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.util.CommandAckStore
import com.smsgateway.app.util.CommandRules
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceRegistrar
import com.smsgateway.app.util.EventLog
import com.smsgateway.app.worker.SmsUploadWorker
import kotlinx.coroutines.CancellationException

/**
 * 执行服务端下发的远程指令，并把结果回执回去。
 *
 * ### 每条指令**本身**也必须幂等
 *
 * [CommandAckStore] 记的是「我做过这条」，它挡掉了绝大多数重复执行；但那张台账只有
 * 20 条，一条被挤出去的旧指令如果还在被服务端重发，就会重新执行一次。所以每条指令的实现
 * 都不能假设「只跑一次」：
 *
 * - `START_GATEWAY` → 对一个已在跑的服务再 start 一次，是系统层面的空操作。
 * - `STOP_GATEWAY` → 对一个已停的服务再 stop 一次，同样是空操作。
 * - `SET_PHONE` → 写同一个值。
 * - `REUPLOAD` → `enqueue` 一个可能已在排的任务。
 * - `CLEAR_UPLOADED` → 再删一次，第二遍删 0 行。
 * - `RE_REGISTER` → 注册是幂等的（令牌由 deviceId 的 HMAC 推导，可重算）。
 *
 * ### 回执只发**固定短语**
 *
 * [CommandAckItem.detail] 会被原样写进服务端一张长期留存的表，所以这里绝不拼异常
 * message，也不回传短信正文 —— 与 [EventLog] 的受控文案是同一条纪律。
 */
object CommandExecutor {

    private const val TAG = "CommandExecutor"

    /**
     * 处理一批下发的指令：去重 → 执行 → 回执。
     *
     * 整段吞异常：调用方是心跳，而心跳是设备与服务器之间唯一还活着的连接，
     * 一条指令执行失败绝不能让心跳也断掉（与 `NotifyOutbox` 那条「转发是旁路能力」
     * 同一条铁律）。
     *
     * @param probeOnly 这批指令来自「网关已停止」时的低频探测心跳。此时只允许
     *   执行 [CommandType.START_GATEWAY] —— 用户停网关的意图是「这台机器冻结住」，
     *   从一个他看不见的后台任务里执行「清理已上传记录」是在违背这个意图改本机状态。
     *   （服务端也只下发这一种，这里是第二道闸。）
     */
    suspend fun handle(context: Context, commands: List<CommandPayload>, probeOnly: Boolean) {
        val app = context.applicationContext
        val acks = mutableListOf<CommandAckItem>()

        for (payload in commands) {
            try {
                val ack = handleOne(app, payload, probeOnly)
                if (ack != null) {
                    acks.add(ack)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单条失败不能带走整批：其余几条该执行还得执行。
                Log.e(TAG, "Failed to handle command ${payload.id}", e)
                val ack = CommandAckItem(payload.id, CommandStatus.FAILED, e.javaClass.simpleName)
                CommandAckStore.record(app, toEntry(ack))
                EventLog.write(
                    app, EventLog.COMMAND_FAILED, EventLog.LEVEL_ERROR,
                    reason = "${payload.type}：${e.javaClass.simpleName}"
                )
                acks.add(ack)
            }
        }

        if (acks.isNotEmpty()) {
            sendAcks(app, acks)
        }
    }

    /** @return 要回执的内容；null 表示这条不该回执（已过期）。 */
    private suspend fun handleOne(
        app: Context,
        payload: CommandPayload,
        probeOnly: Boolean
    ): CommandAckItem? {
        // 1) 本地时钟判过期：**不执行、也不回执**。
        //
        // 不回执是刻意的：服务端的清理任务会独立把这条判成「已过期」，两个时钟各自
        // 得出结论，比让设备去「报告这条已经过期了」少一个状态，也省掉「回执一条
        // 已过期指令」那种需要服务端额外处理的第三态。
        if (CommandRules.isExpired(System.currentTimeMillis(), payload.expiresAt)) {
            EventLog.write(
                app, EventLog.COMMAND_EXPIRED_LOCAL, EventLog.LEVEL_WARN,
                reason = "${payload.type}：本机判定已过期，未执行"
            )
            return null
        }

        // 2) 探测心跳不下发的类型：什么都不做，也不回执。
        //    这不是「做不到」，而是「现在还轮不到它」—— 网关启动后下一次正规心跳
        //    会重新下发。回执 REJECTED 反而会在服务端把它标成失败。
        if (probeOnly && !CommandRules.probeAllows(payload.type)) {
            Log.d(TAG, "Skipping ${payload.type} on probe heartbeat")
            return null
        }

        // 3) 台账命中：这条做过了，**不重做**，把上次的结论重发一遍。
        //    这是「效果执行成功、但回执丢在路上」能被修好的全部实现。
        CommandAckStore.find(app, payload.id)?.let { previous ->
            Log.i(TAG, "Command ${payload.id} already executed (${previous.status}), re-acking")
            return CommandAckItem(previous.id, previous.status, previous.detail)
        }

        // 4) 真执行。RECEIVED 记在执行**之前**，所以「只有 RECEIVED、没有下面那条」
        //    恰好说明执行过程中进程没了 —— 那是这条指令最需要留下的痕迹。
        EventLog.write(
            app, EventLog.COMMAND_RECEIVED, EventLog.LEVEL_INFO,
            reason = if (payload.argument.isNullOrBlank()) payload.type
            else "${payload.type}：${payload.argument}"
        )

        val ack = execute(app, payload)
        CommandAckStore.record(app, toEntry(ack))

        when (ack.status) {
            CommandStatus.DONE -> EventLog.write(
                app, EventLog.COMMAND_DONE, EventLog.LEVEL_INFO,
                reason = "${payload.type}：${ack.detail ?: "已完成"}"
            )

            CommandStatus.REJECTED -> EventLog.write(
                app, EventLog.COMMAND_REJECTED, EventLog.LEVEL_WARN,
                reason = "${payload.type}：${ack.detail ?: "本机不支持"}"
            )

            else -> EventLog.write(
                app, EventLog.COMMAND_FAILED, EventLog.LEVEL_ERROR,
                reason = "${payload.type}：${ack.detail ?: "执行失败"}"
            )
        }

        return ack
    }

    private suspend fun execute(app: Context, payload: CommandPayload): CommandAckItem {
        return when (payload.type) {
            CommandType.START_GATEWAY -> {
                GatewayForegroundService.start(app)
                done(payload, "网关已启动")
            }

            CommandType.STOP_GATEWAY -> {
                // 先补一条「主动报停」再停。**顺序不能反**：服务一停就没人再发心跳，
                // 而这条上报让控制台立刻变灰，而不是等 90 秒心跳超时把它判成掉线。
                //
                // 服务端那边还有第二道：收到这条指令「已执行」的回执时，它会自己写一次
                // reported_offline_at。两道是刻意的 —— 这道可能失败（网络断了），
                // 而那道依赖回执送达，互为兜底。少了它们，「设备离线」告警会在管理员
                // 自己按下停止之后被触发。
                reportOfflineQuietly(app)
                GatewayForegroundService.stop(app)
                done(payload, "网关已停止")
            }

            CommandType.SET_PHONE -> {
                val number = CommandRules.normalizeArgument(payload.argument)
                if (number == null) {
                    fail(payload, "缺少号码参数")
                } else {
                    // subId 传 -1：远程下发的号码不属于任何一张已知的 SIM 卡。
                    // 这个字段的语义是「这个号码来自哪张卡」，用来判断「短信来自另一张卡时
                    // 不要把这张卡的号码顶上去」。随手传一个真实的 subId 会让那个已修好的
                    // 「双卡号码错标」从这条路重新长回来。
                    DevicePrefs.setPhone(app, number, -1)
                    done(payload, "号码已更新")
                }
            }

            CommandType.REUPLOAD -> {
                // enqueue 而不是 enqueueIfIdle：这是人的明确意图（「立刻传」），
                // 该抢占当前那一轮，而不是等它自己跑完。
                SmsUploadWorker.enqueue(app)
                done(payload, "已触发重传")
            }

            CommandType.CLEAR_UPLOADED -> {
                val removed = AppDatabase.getInstance(app).smsQueueDao().deleteAllUploaded()
                done(payload, "已清理 $removed 条已上传记录")
            }

            CommandType.RE_REGISTER -> {
                // 用**本机已有的**身份重跑一次注册。没有任何密钥走网络 ——
                // 服务端刻意不支持经下行通道下发新的重注册密钥，理由见
                // DeviceCommandType.RE_REGISTER 的说明（那会让主密钥泄漏后无法靠轮换止损）。
                val result = DeviceRegistrar.register(app)
                if (result.success) {
                    done(payload, "重新注册成功")
                } else {
                    fail(payload, "重新注册失败：${result.detail}")
                }
            }

            else -> CommandAckItem(
                payload.id, CommandStatus.REJECTED,
                // 类型名是服务端下发的受控枚举值（或它未来的新值），直接回显是安全的，
                // 而且这句话的全部价值就在于说出「是哪个类型不认识」。
                "本机不支持该指令：${payload.type}"
            )
        }
    }

    /**
     * 尽力而为地告诉服务端「网关停了」。
     *
     * 失败只记日志、不影响指令的结论：网关确实已经停了，而服务端那边还会从
     * 「这条指令已执行」的回执里得到同一个结论。
     */
    private suspend fun reportOfflineQuietly(app: Context) {
        try {
            if (!DevicePrefs.isRegistered(app)) return
            RetrofitClient.ensureConfigured(app)
            RetrofitClient.getApiService().reportOffline()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Report offline failed after remote stop", e)
        }
    }

    /**
     * 把回执发回去。
     *
     * **发失败必须留痕**：那意味着服务端会重发，而现场看到的现象是「同一条指令被执行了
     * 两次」。没有这条事件，那件事在日志里完全没有痕迹 —— 上面两条（收到 / 已完成）
     * 都已经写上，看起来一切正常。
     */
    private suspend fun sendAcks(app: Context, acks: List<CommandAckItem>) {
        try {
            RetrofitClient.ensureConfigured(app)
            val response = RetrofitClient.getApiService().ackCommands(CommandAckRequest(acks))
            if (!response.isSuccessful) {
                EventLog.write(
                    app, EventLog.COMMAND_ACK_FAILED, EventLog.LEVEL_WARN,
                    reason = "HTTP ${response.code()}，服务端会重发这些指令"
                )
            } else {
                Log.i(TAG, "Acked ${acks.size} command(s), accepted=${response.body()?.data?.accepted}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EventLog.write(
                app, EventLog.COMMAND_ACK_FAILED, EventLog.LEVEL_WARN,
                reason = "${e.javaClass.simpleName}，服务端会重发这些指令"
            )
        }
    }

    private fun done(payload: CommandPayload, detail: String) =
        CommandAckItem(payload.id, CommandStatus.DONE, detail)

    private fun fail(payload: CommandPayload, detail: String) =
        CommandAckItem(payload.id, CommandStatus.FAILED, detail)

    private fun toEntry(ack: CommandAckItem) =
        CommandAckStore.Entry(id = ack.id, status = ack.status, detail = ack.detail)
}
