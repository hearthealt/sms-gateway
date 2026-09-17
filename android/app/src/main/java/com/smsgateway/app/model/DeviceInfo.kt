package com.smsgateway.app.model

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    /** 读不到本机号码时为 null，Gson 会省略该字段，后端存 null 而不是假号码。 */
    val phone: String? = null,
    val appVersion: String = "1.0.0"
)

data class DeviceTokenData(
    val deviceToken: String = "",
    val deviceId: String? = null,
    /**
     * 服务端的设备状态：ACTIVE / DISABLED。
     *
     * 可空是必要的：字段是后加的，旧服务端不会返回它；Gson 遇到缺失字段会给 null。
     * 注意后端必须回传 SmsDevice.status，而不是管理端视图里那个
     * online/offline/DISABLED 的展示值 —— 后者会把健康设备误报成 offline。
     */
    val status: String? = null
)
