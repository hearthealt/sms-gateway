package com.smsgateway.app.model

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?
)

data class SmsUploadResponseData(
    val messageId: Long,
    val duplicate: Boolean
)