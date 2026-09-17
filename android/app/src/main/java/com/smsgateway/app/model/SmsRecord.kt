package com.smsgateway.app.model

/**
 * 服务端分页结果。字段名与后端 PageResult 对齐。
 * 全部给默认值：Gson 遇到缺字段时不会因此抛异常。
 */
data class PageResult<T>(
    val records: List<T> = emptyList(),
    val total: Long = 0,
    val page: Int = 1,
    val pageSize: Int = 15
)

/**
 * 服务端算好的今日统计。
 *
 * 由服务端下发而不是本地统计：设备端的记录页展示的就是服务端数据，
 * 两者同源才不会出现「显示 0、点进去却有内容」。
 */
data class DeviceSmsStats(
    val todaySms: Long = 0,
    val todayCodes: Long = 0
)

/**
 * 服务端的短信记录（对应后端 SmsView）。
 *
 * status 是**服务端的判定**：RECEIVED 已收下、DUPLICATE 内容重复、IGNORED 被采集规则忽略。
 * 这是本地数据看不到的信息 —— 本地只知道"传没传上去"。
 */
data class SmsRecord(
    val id: Long = 0,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val phone: String? = null,
    val sender: String? = null,
    val content: String? = null,
    val code: String? = null,
    val status: String? = null,
    val receiveTime: String? = null
)
