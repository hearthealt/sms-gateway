package com.smsgateway.app.util

import android.content.Context
import com.google.gson.Gson

/**
 * 已执行过的远程指令台账：`id → 上次的执行结论`。
 *
 * **这是「服务端会重复下发」这个设计的另一半。** 服务端把一条指令停在 SENT 直到
 * 收到回执，于是回执一丢、或者设备离线一会儿，同一条指令就会被再下发一次。
 * 没有这张台账，一条「清理本地已上传记录」会被执行两次 —— 而它恰好无害；
 * 真正的问题是**无法区分「设备没收到」与「设备执行了但回执没发成」**，
 * 于是服务端只能无限重发下去。
 *
 * 存最近 [CAPACITY] 条。淘汰最旧的：服务端下发次数上限是 10 次、间隔 90 秒，
 * 也就是一条指令最多折腾 15 分钟 —— 20 条足够覆盖任何一个还在被重发的指令。
 *
 * 编码/解码与淘汰都是**纯函数**，不碰 SharedPreferences，所以能在 JVM 单测里
 * 直接钉住「同 id 覆盖而不是追加」「超容量淘汰最旧」「坏数据不抛异常」这些边界。
 */
object CommandAckStore {

    /** 台账容量。见类注释。 */
    const val CAPACITY = 20

    private val gson = Gson()

    data class Entry(
        val id: Long,
        /** DONE / FAILED / REJECTED，见 [com.smsgateway.app.model.CommandStatus]。 */
        val status: String,
        val detail: String? = null
    )

    // ------------------------------------------------------------------ 纯函数

    fun encode(entries: List<Entry>): String = gson.toJson(entries)

    /**
     * 解码。**坏数据一律当成空台账**，不抛异常。
     *
     * 这份数据只用来「避免重复执行」，它坏掉的后果是某条指令可能被再执行一次
     * （而那几种指令本来就是幂等的）—— 与之相比，让一个 JSON 解析异常把整条
     * 指令处理链路打断、连回执都发不出去，代价大得多。
     */
    fun decode(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { gson.fromJson(raw, Array<Entry>::class.java)?.toList() ?: emptyList() }
            .getOrDefault(emptyList())
    }

    /**
     * 写入一条：**同 id 覆盖，不追加**，并且只保留最后 [capacity] 条。
     *
     * 覆盖而不是追加，是因为同一个 id 只该有一条记录 —— 重复回执时我们要发的是
     * **上一次的结论**，追加会让「上次的结论」变成最早的那条，而它在语义上同样成立，
     * 却白占容量、还要在查询时去重。
     */
    fun upsert(entries: List<Entry>, entry: Entry, capacity: Int = CAPACITY): List<Entry> {
        val merged = entries.filterNot { it.id == entry.id } + entry
        return if (merged.size <= capacity) merged else merged.takeLast(capacity)
    }

    // ------------------------------------------------------------------ 持久化

    fun load(context: Context): List<Entry> =
        decode(DevicePrefs.get(context).getString(DevicePrefs.KEY_COMMAND_ACKS, null))

    fun find(context: Context, id: Long): Entry? = load(context).firstOrNull { it.id == id }

    fun record(context: Context, entry: Entry) {
        val next = upsert(load(context), entry)
        // commit 而不是 apply：这里紧接着就要发回执，而回执一发出去服务端就不再下发了 ——
        // 万一进程在 apply 落盘前被杀，「已执行」这件事就丢了，同一条指令会被再执行一次。
        // 一次心跳最多 5 条指令，这点同步写代价可以接受。
        DevicePrefs.get(context).edit().putString(DevicePrefs.KEY_COMMAND_ACKS, encode(next)).commit()
    }
}
