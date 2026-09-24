package com.smsgateway.app.model

/**
 * 本设备在**服务端**的一条运行日志。给「一键导出诊断包」用。
 *
 * 为什么要把服务端那一半也带上：最常见的现场正是「设备说传上去了、服务端说没收到」，
 * 而答案就在服务端事件里（存下 / 重复 / 被规则忽略 / 被拒）。只有设备本地那一半，
 * 报告读过之后仍然回答不了那个问题。
 *
 * [phone] 是**服务端已经打过码**的（设备端不再打一次：打码两次会打出
 * `138****8000` 这种已经打过的形态上再加一层，反而看不出原样）。
 */
data class DeviceEventLogEntry(
    val type: String,
    val label: String,
    val level: String,
    val reason: String? = null,
    val sender: String? = null,
    val phone: String? = null,
    /** ISO 本地时间，与其它接口一致（见 ServerTime）。 */
    val at: String? = null
)
