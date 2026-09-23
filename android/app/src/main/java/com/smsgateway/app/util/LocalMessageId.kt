package com.smsgateway.app.util

/**
 * 本地短信的唯一标识。它同时是**服务端的幂等键** —— 服务端按
 * `(devicePk, localMessageId)` 判重，所以格式改动要当心。
 *
 * 格式：`sms-{收到时刻}-{卡槽}-{正文哈希}`
 *
 * 三个成分都是刻意的：
 *
 * - **哈希不再截断。** 老格式是 `fullBody.hashCode().toUShort()`，只留低 16 位，
 *   碰撞概率 1/65536；而 receiveTime 取的是**短信中心时间戳**，PDU 里只有秒级精度。
 *   于是「同一发送方、同一秒、两条不同正文」是完全可能的（106 短号连发就是），
 *   此时唯一的区分维度就是这 16 位。撞上的后果不是「多一行」，而是**新短信被当成
 *   重投直接丢弃** —— SmsQueueDao.insert 撞唯一索引时 `OnConflictStrategy.IGNORE`
 *   静默返回 -1，事件表里写的是「重复短信」，与真重投长得一模一样，现场无从分辨。
 *
 * - **带上卡槽、去掉发送方。** 发送方是键里唯一无界的成分（上限 100），老格式的
 *   最大长度算下来正好 124，离服务端 `@Size(max = 128)` 只剩 4 个字符 —— 再加任何
 *   维度都会把一条合法短信变成一个终态 400。而卡槽才是「这条短信属于哪张卡」的
 *   稳定标识：两张卡收到同样内容时，老格式会把第二条直接丢掉。
 *
 * - **不带号码。** 号码常常是空串（多数运营商不往 SIM 卡里写号码），两个不同的
 *   值反而会把同一条短信切成两行。
 *
 * 旧格式的行不需要迁移：它们的键原样留在唯一索引里，照样能被查出来上传；升级后
 * 同一条短信被重投会生成新键的本地第二行，服务端再按内容哈希
 * （`uk_device_source_hash`）判重返回 DUPLICATE，最终仍然只有一行。
 */
object LocalMessageId {

    /**
     * 服务端 SmsReceiveRequest 对 localMessageId 的长度上限。
     *
     * 本地不对长度做校验（格式本身已经保证远低于上限），留着它是为了让单元测试
     * 能直接断言「键长必须在这个范围内」——长度曾经是这条链路上唯一会把合法短信
     * 变成终态失败的隐患。
     */
    const val MAX_LENGTH = 128

    fun build(receiveTime: Long, subscriptionId: Int, body: String): String =
        "sms-$receiveTime-$subscriptionId-${body.hashCode().toUInt()}"
}
