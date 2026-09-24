package com.smsgateway.app.util

/**
 * 本地短信的唯一标识。它同时是**服务端的幂等键** —— 服务端按
 * `(devicePk, localMessageId)` 判重，所以格式改动要当心。
 *
 * 格式：`sms-{收到时刻}-{卡槽}-{正文哈希}-{发送方哈希}`
 *
 * 四个成分都是刻意的：
 *
 * - **哈希不再截断。** 老格式是 `fullBody.hashCode().toUShort()`，只留低 16 位，
 *   碰撞概率 1/65536；而 receiveTime 取的是**短信中心时间戳**，PDU 里只有秒级精度。
 *   于是「同一发送方、同一秒、两条不同正文」是完全可能的（106 短号连发就是），
 *   此时唯一的区分维度就是这 16 位。撞上的后果不是「多一行」，而是**新短信被当成
 *   重投直接丢弃** —— SmsQueueDao.insert 撞唯一索引时 `OnConflictStrategy.IGNORE`
 *   静默返回 -1，事件表里写的是「重复短信」，与真重投长得一模一样，现场无从分辨。
 *
 * - **带上卡槽。** 卡槽才是「这条短信属于哪张卡」的稳定标识：两张卡收到同样内容时，
 *   老格式会把第二条直接丢掉。
 *
 * - **带上发送方（取哈希）。** 取哈希而不是原串，是为了让长度可控：发送方是键里唯一
 *   无界的成分（上限 100），原样拼进来会让最大长度逼近服务端 `@Size(max:128)`，
 *   再加任何维度都会把一条合法短信变成一个终态 400。换成 32 位哈希之后它是定长的
 *   10 个字符，而它要挡的那件事照样挡得住：同一张卡、同一秒、正文相同、
 *   **发送方不同**的两条短信（两个 106 号在同一秒各发一条同样文案的验证码），
 *   原先会被算成同一个键、第二条静默丢弃。
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

    /**
     * @param sender 短信发送方。允许空串（个别 PDU 解出的 originatingAddress 就是 null），
     *   空串这里算出的哈希与任何具体号码都不同，仍然是一个稳定的值。
     */
    fun build(receiveTime: Long, subscriptionId: Int, body: String, sender: String): String =
        "sms-$receiveTime-$subscriptionId-${body.hashCode().toUInt()}-${sender.hashCode().toUInt()}"
}
