package com.smsgateway.app.ui.screens.home

import androidx.compose.runtime.Composable
import com.smsgateway.app.ui.components.BatteryBannerCompact
import com.smsgateway.app.ui.components.PermissionBannerCompact
import com.smsgateway.app.ui.components.PhonePermissionBanner

/**
 * 主页顶部的警告横幅组。
 *
 * 顺序即优先级：最可能让人「什么也没发生」的问题排在最上面。
 *
 * **这里没有「被禁用」那条横幅。** 它原本也在这条列表里，说的是「已被管理员禁用，短信留在本地」，
 * 而 HeroCard 的标题也写着「已被管理员禁用」，副标题再接一句 —— 同一件事在屏幕上出现三遍。
 * 现在统一收进状态卡：标题说状态，「检查状态」这个动作跟着一起放在那里
 * （见 HeroCard 里禁用那一支）。横幅的位置留给「设备自己不知道、也不会自己好」的问题。
 */
@Composable
fun HomeBanners(checks: DeviceChecks) {
    if (!checks.smsPermission) {
        PermissionBannerCompact()
    }

    // 缺电话权限时也要说一声，哪怕它看起来「不影响收短信」。
    //
    // 它的后果藏在两跳之外：没有权限就分辨不出短信来自哪张卡，于是上传的
    // phone 可能是空的，服务端据此跳过 sms:code:{号码} 缓存，按号码等验证码的调用方
    // **每一条都会超时** —— 而设备侧显示的从头到尾都是「上传成功」。
    // 单卡机不受影响（号码能安全回落，见 DevicePhone.isSingleSim），所以文案不能写成
    // 「收不到验证码」那种吓人的话，要说清是「哪一类设备、哪一种场景下」。
    if (!checks.phonePermission) {
        PhonePermissionBanner()
    }

    if (!checks.ignoringBatteryOptimizations) {
        BatteryBannerCompact()
    }
}
