package com.smsgateway.app.util

import android.content.Context
import com.google.gson.Gson
import com.smsgateway.app.model.OutboundResult

/**
 * 已发出、但还没上报给服务端的外发结果。
 *
 * 存在的理由：发完之后那次心跳可能刚过去 29 秒，而结果不能等到 30 秒后自己消失 ——
 * 它的**送达窗口只有一次**（服务端只下发一次，回执丢了就记「结果未知」）。
 * 所以结果先落在本地，之后每一次心跳都带上，直到过期或被顶掉。
 *
 * **为什么不用「等下一次心跳再发」一个内存变量**：进程可能在这之间被系统杀掉
 * （发完短信之后那 30 秒里完全可能），而那条结果就再也报不上去了。
 *
 * **为什么不做「服务端确认后清掉」**：那需要服务端在心跳响应里回一个「我收到了哪些 key」，
 * 也就是一条 ack-of-ack。而服务端本来就是幂等的，重复报几次的代价只是
 * 心跳请求大一点点 —— 用 [MAX_AGE_MS] 与容量上限约束住就够，不值得为它加一层协议。
 */
object OutboundResultStore {

    /** 最多记多少条。与服务端的下发批量同量级，比它宽裕得多。 */
    const val CAPACITY = 20

    /**
     * 只上报这么久以内的结果。比心跳周期（30 秒）宽裕二十倍：
     * 服务端连着两三次心跳就能收全，而更早的报上去也只是白占请求体。
     */
    const val MAX_AGE_MS = 10 * 60 * 1000L

    private val gson = Gson()

    data class Entry(
        val key: String,
        val status: String,
        val errorReason: String? = null,
        val segments: Int? = null,
        val at: Long = System.currentTimeMillis()
    )

    // ------------------------------------------------------------------ 纯函数

    fun encode(entries: List<Entry>): String = gson.toJson(entries)

    /** 坏数据当成空台账，不抛异常（与 [CommandAckStore] 同一个取舍：这是旁路数据）。 */
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson(raw, Array<Entry>::class.java)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
    }

    /** 同 key 覆盖（一条外发只该有一个结论），并只留最后 [capacity] 条。 */
    fun upsert(entries: List<Entry>, entry: Entry, capacity: Int = CAPACITY): List<Entry> {
        val merged = entries.filterNot { it.key == entry.key } + entry
        return if (merged.size <= capacity) merged else merged.takeLast(capacity)
    }

    /** 取还没过期的那些。 */
    fun fresh(entries: List<Entry>, now: Long = System.currentTimeMillis()): List<Entry> =
        entries.filter { now - it.at <= MAX_AGE_MS }

    // ------------------------------------------------------------------ 持久化

    fun load(context: Context): List<Entry> =
        decode(DevicePrefs.get(context).getString(DevicePrefs.KEY_OUTBOUND_RESULTS, null))

    /** 这次心跳要带上去的结果。 */
    fun pending(context: Context): List<OutboundResult> =
        fresh(load(context)).map {
            OutboundResult(it.key, it.status, it.errorReason, it.segments)
        }

    fun record(context: Context, entry: Entry) {
        val next = upsert(load(context), entry)
        // commit 而不是 apply：紧接着可能就发心跳了，而这份结果只有一次送达机会
        // （服务端只下发一次）。同 CommandAckStore。
        DevicePrefs.get(context).edit()
            .putString(DevicePrefs.KEY_OUTBOUND_RESULTS, encode(next)).commit()
    }
}
