package com.smsgateway.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log

/**
 * 记「WiFi 是什么时候连上的」。
 *
 * ## 它解决的那个现场问题
 *
 * 实测（Redmi K60 Ultra / Android 14）：**WiFi 连上、`ip addr` 里已经有地址之后，
 * 还要十几秒局域网才真正通** —— 这期间 TCP 连过去要么被秒回「本机没路由」，
 * 要么干等超时：
 *
 * ```
 * 11:22:28  2018ms FAIL ip=0   ← WiFi 还没起来
 * 11:22:31  2039ms FAIL ip=1   ← IP 到手了，但仍不通
 * 11:22:43  2044ms FAIL ip=1
 * 11:22:46  1063ms OK   ip=1   ← 首次通，慢
 * 11:22:48    78ms OK   ip=1   ← 之后一直正常
 * ```
 *
 * 而现场扫码连接恰好就发生在刚连上 WiFi 的那十几秒里。探测的重试预算本来很短
 * （3 次，秒回失败时只覆盖约 5 秒），撞进这个窗口就是「连不上，过一会再点一次就好了」，
 * 全看运气 —— 那正是现场反馈的现象。
 *
 * 有了这个时间戳，探测才能把「链路还没就绪」和「对面真的没响应」分开：
 * 前者值得多试十几秒，后者多试只是白等。
 *
 * ## 为什么盯 WiFi，而不是「默认网络」
 *
 * 先写的版本记的是默认网络（`registerDefaultNetworkCallback`）。实测它**在关掉 WiFi、
 * 只剩蜂窝之后仍然一直报「刚切过来」**（等了 100 秒还是），于是探测在「服务器真的不可达」
 * 这种正常场景下也会一路重试到 ~20 秒 —— 把一个少见场景的修复，变成了常见场景的退化。
 *
 * 而现象本身只跟 WiFi 有关：链路没就绪说的是 WiFi 这条链路。所以这里只盯 WiFi 网卡，
 * WiFi 不在时它就是「没在窗口里」，探测保持原来的快节奏。
 *
 * 只读，不改任何出网行为：曾经考虑把请求绑到 WiFi 网卡上解决这个问题，实测否掉了
 * —— 那 15 秒里默认网络**已经是** WiFi 且带 `VALIDATED`，局域网照样不通，
 * 问题不在「用哪张网卡发」，在「链路还没就绪」。
 */
object NetworkWatch {

    private const val TAG = "NetworkWatch"

    private val lock = Any()

    /** 当前可用的 WiFi 网络。用集合是因为可能同时存在多个（旧的那个还没被回收）。 */
    private val availableWifi = mutableSetOf<Network>()

    /** 最近一次「从没有 WiFi 变成有 WiFi」的时刻；0 表示当前没有 WiFi。 */
    private var wifiAvailableSince = 0L

    @Volatile
    private var started = false

    /**
     * 开始跟踪。幂等，可从任意线程、任意入口调用。
     *
     * 必须在进程早期调用（见 `SmsGatewayApp.onCreate`）：等到探测那一刻才注册的话，
     * 第一个回调要等**下一次**网络变化才来。
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        started = true

        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "取不到 ConnectivityManager，无法跟踪 WiFi 连接")
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(lock) {
                        // 已经有别的 WiFi 在用时不动时间戳：那是「换了一张网卡」，
                        // 不是「刚连上 WiFi」。
                        if (availableWifi.isEmpty()) {
                            wifiAvailableSince = SystemClock.elapsedRealtime()
                        }
                        availableWifi.add(network)
                    }
                }

                override fun onLost(network: Network) {
                    synchronized(lock) {
                        availableWifi.remove(network)
                        if (availableWifi.isEmpty()) {
                            wifiAvailableSince = 0L
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // 被 ROM 挡掉之类的意外：不要让它把进程拖垮，退化成「跟踪不了」，
            // 探测那边会按「不在窗口里」处理，也就是原来的行为。
            Log.w(TAG, "注册 WiFi 网络回调失败", e)
        }
    }

    /**
     * WiFi 连上多久了（毫秒）；**还没开始跟踪、或当前没有 WiFi 时返回 null**。
     *
     * 调用方不要拿它当「有没有网」——那是另一回事（见 DeviceTelemetry）。
     * 这里只回答「WiFi 上来多久了」。
     */
    fun millisSinceWifiAvailable(): Long? = synchronized(lock) {
        if (wifiAvailableSince == 0L) return null
        return SystemClock.elapsedRealtime() - wifiAvailableSince
    }
}
