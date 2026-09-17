package com.smsgateway.app.model

data class SmsUploadRequest(
    val deviceId: String,
    val localMessageId: String,
    val phone: String,
    val sender: String,
    val content: String,
    val code: String,
    val receiveTime: String
)