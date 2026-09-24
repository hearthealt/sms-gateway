package com.smsgateway.app.util

import com.smsgateway.app.model.CommandStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已执行指令台账的纯逻辑部分。
 *
 * 这张台账是「服务端会重复下发」这个设计的另一半：服务端把一条没收到回执的指令
 * 停在 SENT、下次心跳再下一次，而设备必须认得出「这条我做过了」。台账一旦错，
 * 两个方向各有一个后果：**认错了**（把没做过的当成做过了）会静默不执行一条管理员
 * 刚下的指令，而管理端显示的是「已执行」—— 那是最难查的一种；不认则重复执行。
 */
class CommandAckStoreTest {

    private fun entry(id: Long, status: String) = CommandAckStore.Entry(id, status, "说明$id")

    @Test
    fun encodeDecodeRoundTrip() {
        val entries = listOf(entry(1, CommandStatus.DONE), entry(2, CommandStatus.FAILED))

        val decoded = CommandAckStore.decode(CommandAckStore.encode(entries))

        assertEquals(2, decoded.size)
        assertEquals(1L, decoded[0].id)
        assertEquals(CommandStatus.DONE, decoded[0].status)
        assertEquals("说明1", decoded[0].detail)
        assertEquals(CommandStatus.FAILED, decoded[1].status)
    }

    @Test
    fun detailMayBeNull() {
        // detail 允许为空。空值能往返是必需的：拿不回来时那条记录会变成一个
        // 「有 id、没有结论」的条目，而回执要发的正是那个结论。
        val entries = listOf(CommandAckStore.Entry(7L, CommandStatus.REJECTED, null))

        val decoded = CommandAckStore.decode(CommandAckStore.encode(entries))

        assertEquals(1, decoded.size)
        assertNull(decoded[0].detail)
    }

    @Test
    fun upsertReplacesSameIdInsteadOfAppending() {
        // 同一个 id 只该有一条记录：重复回执时我们要发的是**上一次的结论**，
        // 追加会让「上一次」变成最早那条 —— 语义上同样成立，却白占容量。
        val entries = listOf(entry(5, CommandStatus.FAILED), entry(6, CommandStatus.DONE))

        val merged = CommandAckStore.upsert(entries, entry(5, CommandStatus.DONE))

        assertEquals(2, merged.size)
        assertNull(merged.firstOrNull { it.id == 5L && it.status == CommandStatus.FAILED })
        // 被覆盖的那条移到**队尾**：台账是「旧 → 新」排序，队尾是最远被淘汰的一端。
        // 原地不动的话，一条被服务端反复重发（= 被反复回执）的指令会一直停在旧位置，
        // 而它恰恰是最不该被挤掉的那个。
        assertEquals(5L, merged.last().id)
        assertEquals(CommandStatus.DONE, merged.last().status)
    }

    @Test
    fun upsertEvictsOldestBeyondCapacity() {
        val entries = (0 until CommandAckStore.CAPACITY).map { entry(it.toLong(), CommandStatus.DONE) }

        val merged = CommandAckStore.upsert(entries, entry(999, CommandStatus.DONE))

        assertEquals(CommandAckStore.CAPACITY, merged.size)
        // 淘汰最旧的：服务端下发次数上限 10 次、间隔 90 秒，一条还在被重发的指令
        // 绝不会离队尾超过 20 条。
        assertNull(merged.firstOrNull { it.id == 0L })
        assertEquals(CommandStatus.DONE, merged.last().status)
    }

    @Test
    fun decodeCorruptDataYieldsEmptyStore() {
        // 坏数据当成空台账，**不抛异常**：这份数据只用来「避免重复执行」，
        // 它坏掉的后果是某条指令可能被再执行一次（那几种指令本来就幂等）——
        // 而让一个 JSON 解析异常打断整条指令链路、连回执都发不出去，代价大得多。
        assertTrue(CommandAckStore.decode(null).isEmpty())
        assertTrue(CommandAckStore.decode("").isEmpty())
        assertTrue(CommandAckStore.decode("不是 JSON").isEmpty())
        assertTrue(CommandAckStore.decode("{\"a\":1}").isEmpty())
    }
}
