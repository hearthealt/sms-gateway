package com.smsgateway.app.model

/**
 * 上报一条短信。
 *
 * **刻意不带 `code`**：验证码由服务端从 `content` 里提取（`CodeExtractor`），
 * 设备端不再解析、也不再上传自己算出来的值。原先上传的那个值会被服务端优先采用，
 * 而设备端的规则比服务端窄（认不出字母数字码）也更宽（会把流水号当验证码），
 * 于是它同时是最宽的错答案来源和最窄的宽度上限。认码只留一处，改规则也就不必发版。
 *
 * 服务端的 DTO 仍然接受这个字段（旧版本 App 还在送），只是不再采信。
 */
data class SmsUploadRequest(
    val deviceId: String,
    val localMessageId: String,
    val phone: String,
    val sender: String,
    val content: String,
    val receiveTime: String
)