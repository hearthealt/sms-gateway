package com.smsgateway.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: SmsQueueEntity): Long

    @Query("UPDATE sms_queue SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE sms_queue SET status = :status, retryCount = :retryCount, nextRetryAt = :nextRetryAt WHERE id = :id")
    suspend fun updateRetry(id: Long, status: String, retryCount: Int, nextRetryAt: Long)

    /**
     * 本轮可以上传的行。
     *
     * 只取 pending 是对的：failed 表示已经确定传不上去（服务端判为非法，如 400），
     * 不该再进重试轮转。**但前提是上传失败时必须保持 pending** —— 早期版本失败即写
     * failed，于是这行永远查不出来，退避重试形同虚设，短信静默丢失。
     */
    @Query("SELECT * FROM sms_queue WHERE status = 'pending' AND (nextRetryAt = 0 OR nextRetryAt <= :currentTimeMillis) ORDER BY receiveTime ASC")
    suspend fun getPendingSms(currentTimeMillis: Long): List<SmsQueueEntity>

    /**
     * 还没上传的行数，**不管到没到重试时刻**。
     *
     * 用来区分「队列真的空了」和「有短信但都还在退避里等」—— 上传 worker 对前者
     * 该以 success 收场（整条工作链结束），对后者必须 retry（让 WorkManager 按自己的
     * 节奏回来）。两者原先都走 success，第三轮回访时就静默断链了。
     *
     * **不能用 [getOutstandingCountSync] 代替**：那个把 failed 也算进来，而 failed 是
     * 永远查不出来的终态行 —— 拿它做重试判据，一台有一条失败短信的设备会永远 retry
     * 下去，纯烧电。
     */
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status = 'pending'")
    suspend fun countPending(): Int

    /** 还没传上去的行（pending 待重试 + failed 终态），供队列页展示。 */
    @Query("SELECT * FROM sms_queue WHERE status != 'uploaded' ORDER BY receiveTime DESC")
    suspend fun getOutstanding(): List<SmsQueueEntity>

    /**
     * [getOutstanding] 的**订阅版**，队列页用它。
     *
     * 队列页原先拿的是一次读库的快照，而库随时在被后台的 worker 改动 ——
     * 页面上那一行与库里那一行于是会分叉，最贵的一次分叉是「立即重试」把已经传上去的
     * 短信又传了一遍（见 DashboardViewModel.observeQueue）。
     * 订阅之后每一处改动都会推回界面，快照与真值之间不再有窗口。
     */
    @Query("SELECT * FROM sms_queue WHERE status != 'uploaded' ORDER BY receiveTime DESC")
    fun observeOutstanding(): Flow<List<SmsQueueEntity>>

    /**
     * 「待上传」的口径：所有还没成功传上去的行。
     *
     * 包含 failed —— 它们同样没传上去，把它们排除在外会让界面显示 0 而实际有积压，
     * 这正是早期版本掩盖问题的方式。
     */
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status != 'uploaded'")
    suspend fun getOutstandingCountSync(): Int

    @Query("DELETE FROM sms_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 手动重试：立刻可传，并清零重试计数（用户主动介入，重新给满重试预算）。
     *
     * 末尾那条状态条件是必需的，不是防御性写法：队列页上那一行可能是几秒前的快照，
     * 而这段时间里后台 worker 完全可能已经把它传上去了。这时点「立即重试」会把一条
     * **已上传**的行改回 pending 再传一遍 —— 服务端 duplicate_count +1、管理端显示成
     * 「重复」，而现场看到的是一条正常的验证码莫名多了一次重复。
     * 界面那边同时改订阅 Flow（见 observeOutstanding），两条一起才封严：这一条挡住
     * 「快照过期」，Flow 挡住「快照与被点之间」。
     */
    @Query(
        "UPDATE sms_queue SET status = 'pending', retryCount = 0, nextRetryAt = 0 " +
            "WHERE id = :id AND status != 'uploaded'"
    )
    suspend fun retryNow(id: Long): Int

    /**
     * 一次性修复：把早期版本错标成 failed 的行扫回 pending。
     *
     * 旧代码在上传失败时写 failed，而查询只取 pending，等于一次失败就永久搁浅。
     * 这些行不会自己恢复，只能在升级后统一扫一次。
     */
    @Query("UPDATE sms_queue SET status = 'pending', nextRetryAt = 0 WHERE status = 'failed'")
    suspend fun sweepStrandedRows(): Int

    /**
     * 给注册前入库的行补上真实身份。
     *
     * 这些行是设备尚未注册时以空串 deviceId/phone 落库的。
     *
     * 关于 phone：**它不是「不补就传不上去」的原因**。这段注释原先写着「phone 为空
     * （列 NOT NULL）会直接 400」，与后端实现不符 —— SmsReceiveRequest 对 phone 只校验
     * 长度、没有 @NotBlank，SmsService 还会把 null 归一成空串，所以 phone 为空照样能入库。
     * 补它的实际收益是让服务端能写 `sms:code:{号码}` 缓存：号码为空时那份缓存被跳过，
     * 按号码等验证码的调用方会一直等到超时，而设备侧记的却是「上传成功」。
     * （见 SmsReceiver.reportMissingPhoneOnce 与 EventLog.SMS_NO_PHONE。）
     *
     * 关于 deviceId：上传时实际用的是 prefs 里的设备标识
     * （SmsUploadWorker 里 `DevicePrefs.deviceId(...).ifBlank { sms.deviceId }`），
     * 所以这一项主要服务于界面展示与未注册期的兜底。
     *
     * 关于 phone 的第四点：**只补空的那一份**。这句话是一条 CASE，不是一句无条件赋值，
     * 这一点是有意的：入库时 phone 已经由 SmsReceiver.resolveSmsPhone 按**收到它的那张卡**
     * 解析过一遍（这正是双卡不错标的原因），而这里补的是配置里那个「本机号码」。
     * 无条件覆盖会把自己解析出来的号码抹平成配置号码 —— 双卡错标会从这条路重新长回来。
     * 补空的那些（号码读不到、或当时还没有权限）仍然值得补：空号码会让服务端跳过
     * `sms:code:{号码}` 缓存，调用方等不到码。
     */
    @Query(
        "UPDATE sms_queue SET deviceId = :deviceId, " +
            "phone = CASE WHEN phone = '' THEN :phone ELSE phone END " +
            "WHERE status = 'pending' AND deviceId = ''"
    )
    suspend fun backfillIdentity(deviceId: String, phone: String): Int

    @Query("DELETE FROM sms_queue WHERE status = 'uploaded' AND receiveTime < :beforeTimestamp")
    suspend fun deleteOldRecords(beforeTimestamp: Long): Int

    @Query("DELETE FROM sms_queue WHERE status = 'uploaded'")
    suspend fun deleteAllUploaded(): Int

    /**
     * 全部重试：把 failed 的那些扫回 pending，并**清零重试计数**。
     *
     * 与逐条 [retryNow] 同义（用户主动介入，重新给满重试预算），只是一次做完整批。
     * 不碰 `uploaded` 的行 —— 那已经传上去了，重排只会让服务端记一次重复。
     *
     * 也不碰 `pending` 的行：它们本来就在等着重试，动它们反而会把 in-flight 的
     * 退避时刻（nextRetryAt）清零、导致一条正常退避中的短信被立刻重发。
     */
    @Query("UPDATE sms_queue SET status = 'pending', retryCount = 0, nextRetryAt = 0 WHERE status = 'failed'")
    suspend fun retryAllFailed(): Int

    /**
     * 删除全部 failed 的行。
     *
     * **只提供「删已失败」，不提供「全部删除」**：后者会把还没上传的验证码直接丢掉，
     * 而且没有撤销。已失败的行是服务端明确拒绝过的（400/422），重试多少次结果都一样 ——
     * 删它们是清理，不是丢数据。
     */
    @Query("DELETE FROM sms_queue WHERE status = 'failed'")
    suspend fun deleteFailed(): Int
}
