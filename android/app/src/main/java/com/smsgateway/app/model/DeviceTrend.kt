package com.smsgateway.app.model

/**
 * 主页那两张小图的数据：近 N 天 + 今日逐小时。
 *
 * 全部给默认值：Gson 遇到缺字段时不会因此抛异常（与 SmsRecord 同一套写法）。
 * 缺的天/小时由**服务端**补 0 —— 柱状图里的空柱子本身就是信息（那天一条都没收到），
 * 客户端再补一遍就等于两处各有一套「补零规则」。
 */
data class DeviceTrend(
    val daily: List<DailyStat> = emptyList(),
    val hourly: List<HourlyStat> = emptyList()
)

/** 单日：日期（yyyy-MM-dd）+ 当天短信数 + 其中识别出验证码的条数。 */
data class DailyStat(
    val day: String = "",
    val value: Long = 0,
    val codes: Long = 0
)

/** 单个小时（0-23）。 */
data class HourlyStat(
    val hour: Int = 0,
    val value: Long = 0,
    val codes: Long = 0
)
