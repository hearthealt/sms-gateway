package com.smsgateway.app.model

data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    /** 读不到本机号码时为 null，Gson 会省略该字段，后端存 null 而不是假号码。 */
    val phone: String? = null,
    val appVersion: String = "1.0.0",
    /**
     * 重注册密钥：本机首次注册时生成并保管，服务端只存它的 SHA-256。
     *
     * 这个 deviceId 在服务端已存在时，必须带上它才拿得到令牌 —— 否则任何知道
     * 设备号的人都能把自己冒充成这台设备。重装丢失后由管理员在控制台签发恢复码取回。
     */
    val enrollSecret: String? = null,

    /**
     * 服务器接入口令，随管理后台「快速连接」的二维码下发。
     *
     * 与上面的 [enrollSecret] 是**两回事**：那个证明「我是这台设备」，服务端只在
     * deviceId 已存在时校验；这个证明「我被允许接入本服务器」，服务端只在**首次注册**
     * （deviceId 还不存在）时校验。
     *
     * 服务端没启用接入口令时留空即可 —— 校验会放行。
     */
    val enrollToken: String? = null
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
