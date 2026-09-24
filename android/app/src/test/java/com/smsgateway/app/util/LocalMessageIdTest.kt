package com.smsgateway.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMessageIdTest {

    /**
     * 改造**之前**的键，原样抄自当时的 SmsReceiver。
     *
     * 留着它才有对照物：下面那条回归证明的是「老格式会把两条不同的短信判成同一条，
     * 新格式不会」。没有它，这条测试就只是在复述新格式的实现。
     */
    private fun legacy(sender: String, receiveTime: Long, body: String) =
        "sms-$sender-$receiveTime-${body.hashCode().toUShort()}"

    /**
     * 穷举出一对「哈希低 16 位相同、完整 32 位不同」的正文。
     *
     * String.hashCode 是 32 位，而低位只有 65536 种取值 —— 按生日悖论，几百次之内
     * 必然命中，不需要真的构造恶意输入。
     */
    private fun findLowBitCollision(): Pair<String, String> {
        val seen = HashMap<Int, String>()
        repeat(200_000) { i ->
            val candidate = "您的验证码是${i}，5分钟内有效"
            // 取低 16 位当桶键 —— 与老格式 toUShort() 丢掉的正是高 16 位是同一回事。
            val lowBits = candidate.hashCode() and 0xFFFF
            val previous = seen.put(lowBits, candidate)
            if (previous != null && previous.hashCode() != candidate.hashCode()) {
                return previous to candidate
            }
        }
        throw AssertionError("200000 次内没凑出低 16 位碰撞 —— 哈希实现变了？")
    }

    @Test
    fun `老格式会把两条不同的正文判成同一条_新格式不会`() {
        val (bodyA, bodyB) = findLowBitCollision()

        // 前提校验：这一对确实在低位上撞了，否则下面两条断言都是空转。
        assertEquals(
            "构造的样本没撞上低位，测试无意义",
            legacy("10690333", 1_700_000_000_000L, bodyA),
            legacy("10690333", 1_700_000_000_000L, bodyB)
        )

        assertNotEquals(
            "同一号码同一秒的两条不同短信必须能区分开，否则第二条会被当成重投丢弃",
            LocalMessageId.build(1_700_000_000_000L, -1, bodyA, "10690333"),
            LocalMessageId.build(1_700_000_000_000L, -1, bodyB, "10690333")
        )
    }

    @Test
    fun `同一条短信重投时键必须一致`() {
        // 反过来也要成立：真的重投（四个成分全同）必须落到同一行上，
        // 否则唯一索引形同虚设，同一条短信会被传两遍。
        val receiveTime = 1_700_000_000_000L
        val body = "【示例】您的验证码是 123456，5分钟内有效"

        assertEquals(
            LocalMessageId.build(receiveTime, 1, body, "10690333"),
            LocalMessageId.build(receiveTime, 1, body, "10690333")
        )
    }

    @Test
    fun `两张卡收到的同样内容不会互相顶掉`() {
        // 双卡机上这是真实的：两张卡收到同一段文案和同一个短信中心时间戳。
        // 新键把卡槽算进去，两条各自成行。
        val receiveTime = 1_700_000_000_000L
        val body = "【示例】您的验证码是 123456"

        assertNotEquals(
            LocalMessageId.build(receiveTime, 1, body, "10690333"),
            LocalMessageId.build(receiveTime, 2, body, "10690333")
        )

        // 对照：老键的签名里**根本没有卡槽这个维度**（见 legacy 的参数表），
        // 所以同一个 (发送方, 时刻, 正文) 无论来自哪张卡都算出同一个值，
        // 第二条会被唯一索引 IGNORE 掉。这一点由签名本身保证，不需要再断言一遍。
    }

    @Test
    fun `同一张卡同一秒正文相同但发送方不同_不能互相顶掉`() {
        // 这一维此前**根本不在键里**：两个 106 短号在同一秒各发一条同样文案的验证码，
        // 第二条会被唯一索引静默 IGNORE，事件表里记成「重复短信」——
        // 与真重投长得一模一样。概率低，但撞上就是一条验证码彻底丢失。
        val receiveTime = 1_700_000_000_000L
        val body = "【示例】您的验证码是 123456"

        assertNotEquals(
            LocalMessageId.build(receiveTime, 1, body, "10690333"),
            LocalMessageId.build(receiveTime, 1, body, "10698888")
        )
    }

    @Test
    fun `发送方为空时也要算出一个稳定的键`() {
        // 个别 PDU 解出的 originatingAddress 就是 null，SmsReceiver 会如实上报空串。
        // 空串必须仍然是一个**确定**的值，不能是「和不带发送方一样」也不能是随机。
        val receiveTime = 1_700_000_000_000L
        val body = "【示例】您的验证码是 123456"

        assertEquals(
            LocalMessageId.build(receiveTime, 1, body, ""),
            LocalMessageId.build(receiveTime, 1, body, "")
        )
        assertNotEquals(
            LocalMessageId.build(receiveTime, 1, body, ""),
            LocalMessageId.build(receiveTime, 1, body, "10690333")
        )
    }

    @Test
    fun `键长必须留在服务端上限之内`() {
        // 发送方取原串时最大长度会逼近 128（这是个 100 字符的上限字段）——
        // 取哈希之后它是定长的 10 个字符。这条断言把余量钉死，
        // 免得日后有人「顺手」把某个成分换回原串。
        val longBody = "验证码" + "1".repeat(20_000)
        val longSender = "1".repeat(100)

        val id = LocalMessageId.build(Long.MAX_VALUE, Int.MIN_VALUE, longBody, longSender)

        assertTrue(
            "键长 ${id.length} 超过服务端上限 ${LocalMessageId.MAX_LENGTH}",
            id.length <= LocalMessageId.MAX_LENGTH
        )
    }
}
