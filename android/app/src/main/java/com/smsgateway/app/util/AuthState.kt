package com.smsgateway.app.util

import android.content.Context
import com.smsgateway.app.network.RetrofitClient
import com.smsgateway.app.service.GatewayForegroundService
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

        // 顺手把网关停掉。放在这里而不是界面里，是因为 401 也可能由上传工作器在
        // **界面已经退出**之后观察到 —— 那时没有 ViewModel 去收这条事件，
        // 服务就会一直挂在那里空转。
        //
        // 为什么必须停：令牌失效意味着服务端那条设备记录**已经不存在**了，
        // 此后每一次心跳、每一次上报都是 401，一条短信也送不出去；而前台服务那条
        // 常驻通知还写着「运行中」，界面上下是「未注册」、下是「停止网关」，
        // 现场看到的是一个自相矛盾的画面（反馈原文：注册成功后后台把设备删了，
        // 仍能点启动，提示完照样变成「停止网关」）。
        //
        // 注意这与「被管理员禁用」**不是一回事**，那条「保持心跳以便发现自己被恢复」
        // 的理由在这里不成立：被禁用时心跳是被放行的（见 DeviceAuthInterceptor），
        // 设备确实能靠它恢复；而令牌失效时记录已经没了，再跳多少次也回不来，
        // 唯一的出路是重新注册。
        GatewayForegroundService.stop(context)

        if (_tokenRejected.value) return false

        // 只记本轮第一次观察到的拒绝。心跳 30 秒一次、上传每次失败都会走到这里，
        // 不挡的话同一次失效会把日志刷满 —— 而这正是「重复调用不反复置位」的同一个理由。
        EventLog.write(
            context, EventLog.DEVICE_TOKEN_REJECTED, EventLog.LEVEL_ERROR,
            reason = "服务端不再认这台设备，已停止上报"
        )

        _tokenRejected.value = true
        return true
    }

    /** 界面展示完提示后调用，让下一轮 401 能再次触发提示。 */
    fun consume() {
        _tokenRejected.value = false
    }
}
