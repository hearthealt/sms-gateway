package com.smsgateway.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * 重要事件记录的读写。
 *
 * 与 [SmsQueueDao] 同一套写法：全是 `suspend fun` + 字符串 `@Query`，**不返回 Flow** ——
 * 界面靠「进页面读一次 + 刷新按钮」，与队列页一致，不为一张诊断表引入响应式订阅。
 */
@Dao
interface EventLogDao {

    /**
     * 插入一条事件。**不用 `OnConflictStrategy.IGNORE`。**
     *
     * `SmsQueueDao.insert` 用 IGNORE 是因为那条路径上「重复」是有意义的业务信号
     * （同一条短信重投），而且调用方会判返回值。这里正相反：事件表没有唯一约束，
     * 同一条事件重复出现本来就是可能的，静默吞掉才是错的。
     */
    @Insert
    suspend fun insert(event: EventLogEntity): Long

    /**
     * 最近 N 条，新的在前。
     *
     * 有上限是刻意的：这张表保留 7 天，量级虽小但理论上无界，
     * 而界面一次渲染几万行没有意义。调用方负责把「只显示最近 N 条」讲给用户听。
     */
    @Query("SELECT * FROM event_log ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<EventLogEntity>

    @Query("SELECT COUNT(*) FROM event_log")
    suspend fun count(): Int

    /**
     * 保留策略的剪枝入口，返回删掉的行数。
     *
     * 契约与 [SmsQueueDao.deleteOldRecords] 一致（同样返回 Int 给调用方拼文案），
     * 但语义有一处关键差别：那边只删 `status = 'uploaded'` 的行，
     * 这边**无条件删** —— 事件过期就是过期，不存在「还没用上」这一说。
     */
    @Query("DELETE FROM event_log WHERE createdAt < :beforeTimestamp")
    suspend fun deleteOldRecords(beforeTimestamp: Long): Int

    /** 用户在设置页主动清空。 */
    @Query("DELETE FROM event_log")
    suspend fun deleteAll(): Int
}
