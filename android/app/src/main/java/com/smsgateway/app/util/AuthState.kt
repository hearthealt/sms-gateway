package com.smsgateway.app.util

import android.content.Context
import com.smsgateway.app.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设备与后端的鉴权状态。目前只有一件事：服务端拒绝了我们手里的令牌。
 *
 * 单独抽出来是因为**有两处**会观察到 401 —— 前台服务里每 30 秒一次的心跳，
 * 以及上传工作器 —— 而提示只能由界面发出。用一个进程级的 StateFlow 当一次性事件，
 * 两边都能置位，界面统一消费。
 *
 * 要解决的问题很具体：令牌失效（服务端删了设备、或换了主密钥）之后，
 * 原来的行为是**什么都不做** —— 本地 deviceId 与 token 都还在，于是
 * `isRegistered` 一直是 true，界面一直显示「已注册」，而实际上一条也传不上去。
 * 现场看到的是「已注册 + 一直连不上」，根本想不到要去重新注册。
 */
object AuthState {

    private val _tokenRejected = MutableStateFlow(false)
    val tokenRejected: StateFlow<Boolean> = _tokenRejected.asStateFlow()

    /**
     * 记录一次「服务端拒绝了我们的令牌」。
     *
     * 会清掉本地令牌 —— 这是关键的一步：清掉之后 `isRegistered` 变回 false，
     * 界面才肯显示「设备未注册」，用户才有机会看到并去重新注册。
     *
     * @return true 表示这是本轮第一次观察到拒绝。重复调用不会反复置位，
     *         免得每 30 秒的心跳把同一条提示刷屏。
     */
    fun markTokenRejected(context: Context): Boolean {
        DevicePrefs.clearToken(context)
        RetrofitClient.updateToken(null)

        if (_tokenRejected.value) return false
        _tokenRejected.value = true
        return true
    }

    /** 界面展示完提示后调用，让下一轮 401 能再次触发提示。 */
    fun consume() {
        _tokenRejected.value = false
    }
}
