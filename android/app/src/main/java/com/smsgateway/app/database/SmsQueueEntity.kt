package com.smsgateway.app.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_queue")
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