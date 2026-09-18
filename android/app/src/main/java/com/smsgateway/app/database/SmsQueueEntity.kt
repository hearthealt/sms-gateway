package com.smsgateway.app.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地待上传队列。
 *
 * `localMessageId` 上的唯一索引是必需的，不是优化：它由发送方 + 接收时刻 + 正文哈希
 * 拼成，一条短信重投时三个分量完全相同。没有这个约束，DAO 上的
 * `OnConflictStrategy.IGNORE` 就永远不触发（唯一约束只有自增 id，而插入从不提供它），
 * 于是同一条短信会入两行、被上传两次。
 */
@Entity(
    tableName = "sms_queue",
    indices = [Index(value = ["localMessageId"], unique = true)]
)
data class SmsQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val localMessageId: String,
    val deviceId: String,
    val phone: String,
    val sender: String,
    val content: String,
    val code: String,
    val receiveTime: Long,
    val status: String = "pending",
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0
)