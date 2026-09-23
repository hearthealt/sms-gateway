package com.smsgateway.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
     * 「待上传」的口径：所有还没成功传上去的行。
     *
     * 包含 failed —— 它们同样没传上去，把它们排除在外会让界面显示 0 而实际有积压，
     * 这正是早期版本掩盖问题的方式。
     */
    @Query("SELECT COUNT(*) FROM sms_queue WHERE status != 'uploaded'")
    suspend fun getOutstandingCountSync(): Int

    @Query("DELETE FROM sms_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 手动重试：立刻可传，并清零重试计数（用户主动介入，重新给满重试预算）。 */
    @Query("UPDATE sms_queue SET status = 'pending', retryCount = 0, nextRetryAt = 0 WHERE id = :id")
    suspend fun retryNow(id: Long)

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
     */
    @Query("UPDATE sms_queue SET deviceId = :deviceId, phone = :phone WHERE status = 'pending' AND deviceId = ''")
    suspend fun backfillIdentity(deviceId: String, phone: String): Int

    @Query("DELETE FROM sms_queue WHERE status = 'uploaded' AND receiveTime < :beforeTimestamp")
    suspend fun deleteOldRecords(beforeTimestamp: Long): Int

    @Query("DELETE FROM sms_queue WHERE status = 'uploaded'")
    suspend fun deleteAllUploaded(): Int
}
