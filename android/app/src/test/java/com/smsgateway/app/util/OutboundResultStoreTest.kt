package com.smsgateway.app.util

import com.smsgateway.app.model.OutboundStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外发结果的本地台账。
 *
 * 这份台账存在的理由很具体：发完之后那次心跳可能刚过去 29 秒，而结果的
 * **送达窗口只有一次**（服务端只下发一次，回执丢了就记「结果未知」）。
 * 所以它必须能反复带上、且过期之后不再白占请求体。
 */
class OutboundResultStoreTest {

    private fun entry(key: String, status: String, at: Long = System.currentTimeMillis()) =
        OutboundResultStore.Entry(key, status, null, 1, at)

    @Test
    fun encodeDecodeRoundTrip() {
        val entries = listOf(entry("out-1", OutboundStatus.SENT))

        val decoded = OutboundResultStore.decode(OutboundResultStore.encode(entries))

        assertEquals(1, decoded.size)
        assertEquals("out-1", decoded[0].key)
        assertEquals(OutboundStatus.SENT, decoded[0].status)
        assertEquals(1, decoded[0].segments)
    }

    @Test
    fun upsertReplacesSameKey() {
        // 同一条外发只该有一个结论：后到的覆盖先到的（同一段重复回调、或先失败后成功）
        val entries = listOf(entry("out-1", OutboundStatus.FAILED), entry("out-2", OutboundStatus.SENT))

        val merged = OutboundResultStore.upsert(entries, entry("out-1", OutboundStatus.SENT))

        assertEquals(2, merged.size)
        assertNull(merged.firstOrNull { it.key == "out-1" && it.status == OutboundStatus.FAILED })
        assertEquals(OutboundStatus.SENT, merged.last().status)
    }

    @Test
    fun upsertEvictsOldestBeyondCapacity() {
        val entries = (0 until OutboundResultStore.CAPACITY)
            .map { entry("out-$it", OutboundStatus.SENT) }

        val merged = OutboundResultStore.upsert(entries, entry("out-new", OutboundStatus.SENT))

        assertEquals(OutboundResultStore.CAPACITY, merged.size)
        assertNull(merged.firstOrNull { it.key == "out-0" })
    }

    @Test
    fun freshDropsExpiredEntries() {
        val now = 1_000_000_000_000L
        val entries = listOf(
            entry("out-old", OutboundStatus.SENT, at = now - OutboundResultStore.MAX_AGE_MS - 1),
            entry("out-new", OutboundStatus.SENT, at = now - 1000)
        )

        val fresh = OutboundResultStore.fresh(entries, now)

        // 过期的报上去只是白占心跳的请求体 —— 那份结果早在十几次心跳前就该到了
        assertEquals(listOf("out-new"), fresh.map { it.key })
    }

    @Test
    fun corruptDataYieldsEmptyStore() {
        assertTrue(OutboundResultStore.decode(null).isEmpty())
        assertTrue(OutboundResultStore.decode("").isEmpty())
        assertTrue(OutboundResultStore.decode("不是 JSON").isEmpty())
    }
}
