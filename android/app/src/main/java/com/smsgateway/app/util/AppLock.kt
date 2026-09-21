package com.smsgateway.app.util

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用锁：一个 PIN，可用指纹代打。
 *
 * ## 它挡的是什么
 *
 * 这台手机是**常驻**的 —— 摆在工位上、插着电、屏幕不锁。装了网关之后，主页与记录页
 * 上摆着可以拿去登录的验证码，谁路过都能看一眼。这条锁挡的就是「路过的人」，
 * 不是「拿到手机的技术人」（那种人清一下应用数据就绕过去了，而清数据会丢掉设备身份，
 * 得去控制台签恢复码 —— 那条路是**故意**留的，见下面的「忘记 PIN」）。
 *
 * ## PIN 怎么存
 *
 * 只存 `salt + PBKDF2(pin, salt)`，不存明文也不存 SHA-256(pin)：6 位数字的搜索空间只有
 * 一百万，裸 SHA-256 在手机上几秒就能跑完，PBKDF2 迭代把离线爆破的代价拉到有意义的一档。
 * 存的那份在 prefs 里，设备没 root 拿不到；拿到了也只能爆破。
 *
 * ## 忘记 PIN
 *
 * **没有找回，也没有后门**。真忘了就清应用数据（App 里的说明会给一个直达系统设置页
 * 的入口），然后用管理员在控制台签发的恢复码重新注册 —— 设备标识取自 SSAID，
 * 清数据不会变，所以后台还是同一台设备、历史记录也还挂在它名下。
 * 「用一个能绕过 PIN 的入口找回」等于锁没装，这里不提供。
 */
object AppLock {

    private const val TAG = "AppLock"

    private const val PBKDF2_ITERATIONS = 60_000
    private const val KEY_LENGTH_BITS = 256

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** 有没有设过 PIN。没设过就没有锁。 */
    fun isEnabled(context: Context): Boolean = DevicePrefs.lockPinHash(context) != null

    /**
     * 需要重新上锁时调用（冷启动、从后台回来超过阈值）。
     *
     * 没设过 PIN 时什么都不做 —— 别给一台没配锁的设备凭空弹一个锁屏。
     */
    fun lockIfEnabled(context: Context) {
        if (isEnabled(context)) {
            _locked.value = true
        }
    }

    fun unlock() {
        _locked.value = false
    }

    /** 设或改 PIN。设完立刻解锁：能设它的人显然已经证明过自己了。 */
    fun setPin(context: Context, pin: String) {
        DevicePrefs.setLockPin(context, pin)
        _locked.value = false
    }

    /** 关掉锁（调用方要先验过 PIN）。 */
    fun disable(context: Context) {
        DevicePrefs.clearLockPin(context)
        _locked.value = false
    }

    fun verify(context: Context, pin: String): Boolean = DevicePrefs.verifyLockPin(context, pin)

    /**
     * 这台设备现在能不能用指纹/人脸。
     *
     * API 28 以下一律 false：那时还没有平台的 `BiometricPrompt`（指纹要走已弃用的
     * `FingerprintManager`），而引入 `androidx.biometric` 只为兜住 26/27 两个版本，
     * 要多一个依赖、还要把 MainActivity 换成 FragmentActivity —— 不值。
     * 那些设备上退回纯 PIN，锁照样是锁。
     */
    @Suppress("DEPRECATION")
    fun canUseBiometric(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val manager = context.getSystemService(Context.BIOMETRIC_SERVICE) as? BiometricManager
            ?: return false

        // API 29 起无参的那个被弃用，改成显式指定「要哪种强度的生物识别」。
        // 用 WEAK 而不是 STRONG：指纹在不少 ROM 上被判成弱强度，用 STRONG 会出现
        // 「明明录了指纹却说不能用」。
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            manager.canAuthenticate()
        }
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * 弹系统指纹/人脸框。回调在主线程。
     *
     * 用平台自带的 `BiometricPrompt` 而不是 `androidx.biometric`：后者要新依赖，
     * 而且它要求宿主是 `FragmentActivity`（本应用是 `ComponentActivity`）——
     * 为了一个「可以用指纹代替输 PIN」的便利去改 Activity 的基类，不划算。
     */
    fun promptBiometric(
        activity: Activity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onFailure("这台设备不支持指纹解锁（Android 9 以下）")
            return
        }

        try {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle("解锁云驿站")
                .setSubtitle("用指纹或人脸代替 PIN")
                .setNegativeButton("取消", activity.mainExecutor) { _, _ -> }
                .build()

            prompt.authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        // 用户按取消也会走到这里，不当成错误报出来
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            onFailure(errString?.toString() ?: "指纹解锁失败（$errorCode）")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // ROM 把接口挡掉之类的意外：退回输 PIN，别让锁屏卡在「点了没反应」
            Log.w(TAG, "指纹弹框失败", e)
            onFailure("这台设备暂时用不了指纹，请输入 PIN")
        }
    }
}
