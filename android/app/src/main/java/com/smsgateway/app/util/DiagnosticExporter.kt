package com.smsgateway.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.smsgateway.app.BuildConfig
import com.smsgateway.app.SelfTestItem
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.network.RetrofitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一键导出诊断包：把现场排查从「来回问十轮」变成「发一个文件」。
 *
 * 收集（读库、读 prefs、发请求）在这里，渲染与脱敏在 [DiagnosticReport] ——
 * 分开是为了让后者能在 JVM 单测里钉住。
 */
object DiagnosticExporter {

    private const val TAG = "DiagnosticExporter"

    /** 与 `res/xml/file_paths.xml` 里暴露的那一个子目录一致。 */
    private const val DIR_NAME = "diagnostics"

    /** 与**服务端** `DeviceEventLogView` 的上限一致（那边会把更大的值夹到 200）。 */
    private const val SERVER_EVENT_LIMIT = 200

    /**
     * 取服务端日志的上限。
     *
     * 8 秒：局域网内一次 200 条的查询通常几十毫秒，8 秒足够覆盖一次抖动；
     * 而超过它多半是「根本连不上」，那时**快一点出文件**比凑齐这一节重要得多 ——
     * 用户会据此看到「未取到：超时」，那本身就是一条诊断信息。
     */
    private const val SERVER_EVENT_TIMEOUT_MS = 8_000L

    private const val LOCAL_EVENT_LIMIT = 200

    private val FILE_TIME = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 生成的结果。
     *
     * @param text 文件内容本身。**界面要能直接看到它** —— 只能分享、自己看不到，
     *   那连「确认里面有没有泄漏什么」都做不到，而这个包存在的意义恰恰是给人看的。
     * @param fileName 文件名（界面上显示一下，让人知道分享出去的是什么）。
     * @param shareIntent 分享用的 Intent（交给系统分享面板）。
     */
    data class Result(val text: String, val fileName: String, val shareIntent: Intent)

    /**
     * 生成诊断包。
     *
     * **不抛异常以外的返回**：失败（自检命中凭据、写文件失败）直接抛，由界面弹一条错误 ——
     * 静默失败在这里特别坏，用户会以为文件已经分享出去了。
     */
    suspend fun export(context: Context, selfTest: List<SelfTestItem>): Result {
        val app = context.applicationContext
        val data = collect(app, selfTest)

        val text = DiagnosticReport.render(data)

        // 生成后自检：命中任何一项真实凭据就拒绝出文件。理由见 DiagnosticReport.assertNoSecrets。
        DiagnosticReport.assertNoSecrets(text, secretsOf(app))

        val file = writeToCache(app, text)

        val uri = FileProvider.getUriForFile(
            app,
            // 必须与清单里 android:authorities="${applicationId}.fileprovider" 求值后一致。
            // 用 packageName 而不是写死包名：applicationId 与 namespace 在本项目里是不同的
            // （com.yunyi.smshub vs com.smsgateway.app），写死 namespace 会在这里抛。
            "${app.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "短信网关诊断包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Result(text = text, fileName = file.name, shareIntent = shareIntent)
    }

    // ------------------------------------------------------------------ 收集

    private suspend fun collect(app: Context, selfTest: List<SelfTestItem>): DiagnosticReport.DiagnosticData {
        val dao = AppDatabase.getInstance(app).smsQueueDao()
        val eventDao = AppDatabase.getInstance(app).eventLogDao()

        val outstanding = dao.getOutstanding()

        val queue = outstanding.map { row ->
            DiagnosticReport.QueueLine(
                id = row.id,
                senderMasked = DiagnosticReport.maskNumber(row.sender),
                phoneMasked = DiagnosticReport.maskNumber(row.phone),
                receiveTime = row.receiveTime,
                status = row.status,
                retryCount = row.retryCount,
                nextRetryAt = row.nextRetryAt,
                // **正文字节数，不是正文**。读者需要知道这条有多长，但不该看到内容。
                contentBytes = row.content.toByteArray(Charsets.UTF_8).size
            )
        }

        val localEvents = eventDao.getRecent(LOCAL_EVENT_LIMIT).map { row ->
            DiagnosticReport.EventLine(
                at = row.createdAt,
                level = row.level,
                typeLabel = EventLog.labelOf(row.type),
                senderMasked = DiagnosticReport.maskNumber(row.sender),
                phoneMasked = DiagnosticReport.maskNumber(row.phone),
                reason = row.reason
            )
        }

        val serverResult = fetchServerEvents(app)

        return DiagnosticReport.DiagnosticData(
            generatedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            // 设备标识**完整**导出：它不是凭据，且是本地日志与服务端日志之间唯一的连接键
            deviceId = DevicePrefs.deviceId(app),
            serverUrl = DevicePrefs.serverUrl(app),
            buildInfo = buildInfo(),
            state = stateFields(app, dao.countPending(), outstanding.size, eventDao.count()),
            permissions = permissionFields(app),
            simSummary = simSummary(app),
            selfTest = selfTest,
            queueLine = "未上传 ${outstanding.size} 条，其中到期待传 ${dao.countPending()} 条。",
            queue = queue,
            localEvents = localEvents,
            serverEvents = serverResult.first,
            serverEventsError = serverResult.second,
            counts = mapOf(
                "queueOutstanding" to outstanding.size,
                "eventLog" to eventDao.count()
            )
        )
    }

    private fun stateFields(app: Context, pending: Int, outstanding: Int, eventCount: Int): List<DiagnosticReport.Field> {
        val heartbeat = HeartbeatSender.lastSuccessAt.value ?: DevicePrefs.lastHeartbeatAt(app)
        return listOf(
            DiagnosticReport.Field("注册状态", if (DevicePrefs.isRegistered(app)) "已注册" else "未注册"),
            DiagnosticReport.Field("网关运行态", if (DevicePrefs.isGatewayRunning(app)) "运行中" else "已停止"),
            DiagnosticReport.Field("被服务端禁用", if (DevicePrefs.isDisabled(app)) "是" else "否"),
            // **打码后再写**。这里原先写的是原值，而 DiagnosticReport.assertNoSecrets
            // 会把完整号码当成敏感值搜出来 —— 于是导出每次都失败。
            // 那正是那道自检存在的意义：它把「有人顺手把原值写进去了」变成一次响亮的失败，
            // 而不是一份悄悄泄漏了号码的文件。
            DiagnosticReport.Field("本机号码", DiagnosticReport.maskNumber(DevicePrefs.phone(app)) ?: "未设置"),
            DiagnosticReport.Field(
                "最近一次心跳成功",
                heartbeat?.let { DiagnosticReportTime.absolute(it) } ?: "从未成功过"
            ),
            DiagnosticReport.Field("本次启动时刻", DevicePrefs.gatewayStartedAt(app)?.let {
                DiagnosticReportTime.absolute(it)
            } ?: "-"),
            DiagnosticReport.Field("待上传", "$pending 条（队列未完成 $outstanding 条）"),
            DiagnosticReport.Field("本地事件条数", eventCount.toString()),
            DiagnosticReport.Field("接入口令", if (DevicePrefs.enrollToken(app).isNotBlank()) "已配置" else "未配置"),
            DiagnosticReport.Field("应用锁", if (AppLock.isEnabled(app)) "已开启" else "未开启")
        )
    }

    private fun permissionFields(app: Context): List<DiagnosticReport.Field> {
        fun granted(permission: String) =
            if (ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED) {
                "已授予"
            } else {
                "未授予"
            }

        val fields = mutableListOf(
            DiagnosticReport.Field("接收短信", granted(Manifest.permission.RECEIVE_SMS)),
            DiagnosticReport.Field("相机（扫码用）", granted(Manifest.permission.CAMERA)),
            DiagnosticReport.Field("电话（读号码用）", granted(Manifest.permission.READ_PHONE_NUMBERS)),
            DiagnosticReport.Field("电池优化白名单", if (ignoringBatteryOptimizations(app)) "已加入" else "未加入")
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            fields.add(1, DiagnosticReport.Field("通知", granted(Manifest.permission.POST_NOTIFICATIONS)))
        }
        return fields
    }

    private fun ignoringBatteryOptimizations(app: Context): Boolean = try {
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(app.packageName)
    } catch (e: Exception) {
        // 个别 ROM 上这个调用会抛。这里当成「未加入」—— 那正是「该去加白名单」的保守答案。
        Log.w(TAG, "Failed to query battery optimization state", e)
        false
    }

    /**
     * SIM 卡的**数量与读号码的结果**，不带任何号码。
     *
     * 需要的是「有几种读不到」，不是号码本身 —— 号码那一项在主状态里已经有（打码后）。
     */
    private fun simSummary(app: Context): String {
        val result = DevicePhone.querySlots(app)
        if (result.problem != null) {
            // problem 是给用户看的一句话（没权限 / 系统不给列表 / 调不通），原样带上
            return "读不到（${result.problem}）"
        }
        if (result.slots.isEmpty()) {
            return "没有读到任何卡"
        }
        val detail = result.slots.joinToString("，") { slot ->
            "${slot.label}号码${if (slot.number.isNullOrBlank()) "未知" else "已读到"}"
        }
        return "${result.slots.size} 张：$detail"
    }

    private fun buildInfo(): String = listOf(
        "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "ROM ${Build.DISPLAY}"
    ).joinToString("；")

    /**
     * 服务端那一半日志。
     *
     * 取不到时返回**原因**而不是空表：静默省略会让读者分不清「服务端一条都没有」
     * 与「没取到」，而那正是这份报告最需要回答的那类问题。
     */
    private suspend fun fetchServerEvents(app: Context): Pair<List<DiagnosticReport.EventLine>?, String?> {
        if (!DevicePrefs.isRegistered(app)) {
            return null to "设备未注册"
        }
        return try {
            RetrofitClient.ensureConfigured(app)
            // **给这次取日志一个短上限。** 共享的那个客户端读超时是 30 秒，而服务器不可达
            // 恰恰是最需要导出诊断包的时候 —— 那 30 秒会让人以为「点了没反应」。
            // 拿不到就照实写「未取到」，其余部分照常出文件（那本就是这个包的用法：
            // 它是给人看的，缺一节比卡住有用）。
            withTimeoutOrNull(SERVER_EVENT_TIMEOUT_MS) {
                val response = RetrofitClient.getApiService().deviceEventLog(SERVER_EVENT_LIMIT)
                val body = response.body()?.data
                if (!response.isSuccessful || body == null) {
                    null to "HTTP ${response.code()}"
                } else {
                    body.map { entry ->
                        DiagnosticReport.EventLine(
                            at = ServerTime.parseInstant(entry.at) ?: System.currentTimeMillis(),
                            level = entry.level,
                            typeLabel = entry.label,
                            senderMasked = entry.sender,
                            // 服务端已经打过码了，这里**不再打一次**（打两次会打出带星号的怪值）
                            phoneMasked = entry.phone,
                            reason = entry.reason
                        )
                    } to null
                }
            } ?: (null to "取服务端日志超时（${SERVER_EVENT_TIMEOUT_MS / 1000} 秒）")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to e.javaClass.simpleName
        }
    }

    /** 自检要搜的那一份敏感值。改任何一处存储位置，这里都要跟着改 —— 单测会盯着。 */
    private fun secretsOf(app: Context): List<String?> = listOf(
        DevicePrefs.deviceToken(app),
        DevicePrefs.enrollSecret(app),
        DevicePrefs.enrollToken(app),
        DevicePrefs.get(app).getString(DevicePrefs.KEY_LOCK_PIN_HASH, null),
        DevicePrefs.get(app).getString(DevicePrefs.KEY_LOCK_PIN_SALT, null),
        // 完整号码：报告里只出现打码后的形态
        DevicePrefs.phone(app)
    )

    // ------------------------------------------------------------------ 落盘

    /**
     * 写进 `cacheDir/diagnostics/`。
     *
     * 用 cacheDir 而不是 filesDir：这是**可再生**的诊断快照，不该长期占用户的存储，
     * 也不该进备份（应用本来就 `allowBackup="false"`）。
     *
     * 每次导出前清掉旧文件（同目录只留一份）。**不在 onCreate 里清** ——
     * 那会让用户在分享前的最后一步失去文件。
     */
    private fun writeToCache(app: Context, text: String): File {
        val dir = File(app.cacheDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("无法创建诊断包目录")
        }
        dir.listFiles()?.forEach { it.delete() }

        val file = File(dir, fileName(app))
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    /**
     * 文件名里放设备标识的**前 12 位**。
     *
     * 不截断的话迟早撞上文件名长度上限（device_id 是 128 字符的列）。
     * 不放手机号：文件名会出现在分享目标的界面上，也会进相册/文件的索引。
     */
    private fun fileName(app: Context): String {
        val raw = DevicePrefs.deviceId(app).take(12)
        // 设备标识是 android-<hex> 或 UUID，正常不会有非法字符；这里仍然过一遍 ——
        // 出问题时（改名、恢复码导入的旧值）失败方式是「文件建不出来」，而这条路径没有 catch。
        val safe = raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return "sms-gateway-diag-$safe-${FILE_TIME.format(Date())}.txt"
    }
}

/** 诊断报告里的时间格式。单独放着，免得与 `TimeFormat` 里界面用的相对时间混在一起。 */
private object DiagnosticReportTime {
    private val FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun absolute(at: Long): String = FORMAT.format(Date(at))
}
