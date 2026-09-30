package com.smsgateway.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 对账腿与广播路径之间的口径一致性。
 *
 * ## 这里没有「两条路算出同一个键」这条测试，是**故意的**
 *
 * 最初的设计假设两条路能给出一致的「收到时刻」，于是 `localMessageId` 的唯一索引
 * 就能拦住重复。**2026-09-30 在真机上把这个假设证伪了**：
 *
 * | `_id` | `date` | `date_sent` |
 * |---|---|---|
 * | 226 | …092853 | …091000 |
 * | 225 | …542726 | …542**269** |
 * | 224 | …655224 | …654000 |
 * | 223 | …611987 | …610000 |
 *
 * `date` 全是**收信时的墙上时间**（带毫秒），而广播路径拿的是 PDU 里的 SCTS
 * （短信中心时间戳，只有**秒**精度，必然整秒）。两者不是同一个值，差 0.4～2 秒；
 * `date_sent` 也不稳定（225 那条就不是整秒）。
 *
 * 所以对账腿改成按**内容 + 时间窗**判重（[reconcileDedupWindow]），本文件钉的就是
 * 这个窗口的口径 —— 这条链路上唯一会「一条短信被捞两遍」的风险全在它身上。
 */
class SmsReconcileTest {

    /** 2026-09-30 现场那条被漏投的短信，正文与发送方原样取自系统收件箱（`_id=225`）。 */
    private val lostBody =
        "【腾讯科技】腾讯视频验证码：087186 ，有效期60秒。请勿向任何人泄露，如非本人操作，请忽略本短信。"
    private val lostSender = "10687534293676825929"

    /**
     * 真机上实测到的 `date` 与 `date_sent`（`content query` 的原样输出）。
     *
     * 用它而不是编造的样本：要证明的正是「窗口能盖住**这台 ROM 上真实存在**的偏差」。
     */
    private val observedPairs = listOf(
        1_790_739_092_853L to 1_790_739_091_000L, // _id=226
        1_790_735_542_726L to 1_790_735_542_269L, // _id=225
        1_790_646_655_224L to 1_790_646_654_000L, // _id=224
        1_790_586_611_987L to 1_790_586_610_000L  // _id=223
    )

    @Test
    fun `两条路的时间偏差必须落在判重窗口内`() {
        // 判重窗取的是「provider 给的那一刻 ± 窗口」。广播路径给的是 SCTS，
        // 它落在哪一侧取决于投递延迟，所以两侧都要盖住。
        observedPairs.forEach { (providerDate, sctsLike) ->
            val delta = abs(providerDate - sctsLike)

            assertTrue(
                "实测偏差 ${delta}ms 落在判重窗口（±${RECONCILE_DEDUP_WINDOW_MS}ms）之外 —— " +
                    "广播已收过的短信会被对账腿重新捞一遍",
                delta <= RECONCILE_DEDUP_WINDOW_MS
            )
            assertTrue(
                "广播路径的 SCTS 没落进以 provider 时刻为中心的窗口里",
                sctsLike in reconcileDedupWindow(providerDate)
            )
        }
    }

    @Test
    fun `判重窗口是以给定时刻为中心的闭区间`() {
        val at = 1_790_735_542_726L
        val window = reconcileDedupWindow(at)

        assertEquals(at - RECONCILE_DEDUP_WINDOW_MS, window.first)
        assertEquals(at + RECONCILE_DEDUP_WINDOW_MS, window.last)

        // 边界是闭的：正好差一个窗口宽度的两条仍算同一条（宁可多判一次重复，
        // 也不要把一条真短信判成新的）。
        assertTrue(window.first in window)
        assertTrue(window.last in window)
        assertFalse(window.first - 1 in window)
        assertFalse(window.last + 1 in window)
    }

    @Test
    fun `窗口必须远大于实测偏差_留出投递延迟的余量`() {
        // 窗口贴着实测值取（比如就取 2 秒）会在网络慢一点时失效。这条把「余量」
        // 本身钉住：至少要比这台 ROM 上观测到的最坏情况宽一个数量级。
        val worstObserved = observedPairs.maxOf { (a, b) -> abs(a - b) }

        assertTrue(
            "判重窗口只有 ${RECONCILE_DEDUP_WINDOW_MS}ms，而实测偏差最大 ${worstObserved}ms —— " +
                "余量不足，投递一慢就会重复捞",
            RECONCILE_DEDUP_WINDOW_MS >= worstObserved * 10
        )
    }

    @Test
    fun `刚落库的短信要等一会儿再动_避免抢在广播前面`() {
        val now = 1_790_750_677_000L

        // 刚落库（age=0）：等满
        assertEquals(
            SmsInboxReconciler.RECONCILE_MIN_AGE_MS,
            SmsInboxReconciler.reconcileDeferMillis(now, now)
        )
        // 落了 2 秒：再等 3 秒
        assertEquals(
            SmsInboxReconciler.RECONCILE_MIN_AGE_MS - 2_000L,
            SmsInboxReconciler.reconcileDeferMillis(now - 2_000L, now)
        )
    }

    @Test
    fun `够老的短信立刻处理_不引入额外延迟`() {
        val now = 1_790_750_677_000L

        // 正好到界：可以处理（边界取闭，早一点总比晚一点安全）
        assertEquals(
            0L,
            SmsInboxReconciler.reconcileDeferMillis(now - SmsInboxReconciler.RECONCILE_MIN_AGE_MS, now)
        )
        // 很老：同样立刻处理 —— 这条覆盖的是「网关停了一阵子、重启后补采」那一批
        assertEquals(0L, SmsInboxReconciler.reconcileDeferMillis(now - 86_400_000L, now))
    }

    @Test
    fun `时间戳在未来时不能把管道堵死`() {
        // 系统时间被往回拨过（NTP 校时、用户手改）时，provider 里的 date 会比"现在"晚。
        // 那种行必须照常处理：在这里挡住的话水位线永远推不动，整条对账腿静默瘫掉 ——
        // 比处理一条时间怪异的短信糟得多。
        val now = 1_790_750_677_000L

        assertEquals(0L, SmsInboxReconciler.reconcileDeferMillis(now + 60_000L, now))
    }

    @Test
    fun `provider 里的 NULL 要归一成与广播路径相同的空值`() {
        // 广播路径上这两处分别是 `?: ""`（originatingAddress 可能为 null）与
        // `?: -1`（取不到 subscription extra），对账路径必须落到同一组值上。
        val row = normalizeInboxRow(
            id = 1,
            address = null,
            body = "您的验证码是 123456",
            date = 1_790_000_000_000L,
            subId = null,
            nowMillis = 1_790_000_000_001L
        )

        assertEquals("", row.sender)
        assertEquals(-1, row.subscriptionId)
    }

    @Test
    fun `date 不可用时用传入的当前时刻兜底_而不是留下 0`() {
        // 键里的「收到时刻」取到 0 会让键退化成 `sms-0-<卡槽>-<哈希>` ——
        // 同一号码连发两条同样内容的短信就会撞键，第二条被当成重投静默丢掉。
        val now = 1_790_740_000_000L
        val row = normalizeInboxRow(
            id = 2,
            address = "10690333",
            body = "验证码 123456",
            date = 0L,
            subId = null,
            nowMillis = now
        )

        assertEquals(now, row.receiveTime)
    }

    @Test
    fun `水位线用的 id 必须原样带过归一化`() {
        // id 是 `_id > watermark` 的游标，推进水位线时写回的就是它。
        // 归一化若在这里动了手脚（例如换成时间戳），水位线会永远推不动、
        // 每轮把整个收件箱重看一遍。
        val row = normalizeInboxRow(
            id = 225,
            address = lostSender,
            body = lostBody,
            date = 1_790_735_542_726L,
            subId = 2,
            nowMillis = 0L
        )

        assertEquals(225L, row.id)
    }
}
