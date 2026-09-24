package com.smsgateway.app.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.smsgateway.app.MainActivity
import com.smsgateway.app.R
import com.smsgateway.app.database.AppDatabase
import com.smsgateway.app.util.DevicePrefs
import com.smsgateway.app.util.DeviceStatus
import com.smsgateway.app.util.GatewayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 快捷设置磁贴：从下拉通知栏里一眼看到网关状态、一键开关。
 *
 * 补齐的是主页之外最后一块「不用打开应用就能看到」的地方。这个应用的常态是
 * 被丢在一边跑，而想知道「它还在跑吗」时，让人先去启动器里找应用是最绕的一条路。
 *
 * 磁贴的副标题最多显示待上传条数（需要读一次本地库，所以异步补上）——
 * 那个数字是这台设备唯一「正在积压」的信号，也是主页上最显眼的那一格。
 */
class GatewayTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 点一下：在跑就停，没跑就启。
     *
     * 未注册时既不启也不停，而是**打开应用去扫码** —— 启一个没注册的服务只会拉起一个
     * 每 30 秒刷「设备未注册」通知的空转服务（与 `DashboardViewModel.startService`
     * 同一条判断）。
     */
    override fun onClick() {
        super.onClick()

        when {
            GatewayState.isRunning(this) -> GatewayForegroundService.stop(this)
            DevicePrefs.isRegistered(this) -> GatewayForegroundService.start(this)
            else -> openApp()
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return

        // 这几个状态都是持久化的，而磁贴可能在本进程从未启动过时就被系统调用
        // （用户在设置里编辑磁贴），所以先水合一次再读。
        GatewayState.ensureLoaded(this)
        DeviceStatus.ensureLoaded(this)

        val running = GatewayState.isRunning(this)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // 图标沿用应用自身那个：磁贴没有自己的图形，而系统默认那个方块
        // 在一排磁贴里完全认不出是哪个应用。
        tile.icon = Icon.createWithResource(this, R.drawable.ic_logo_mark)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                DeviceStatus.isDisabled(this) -> "已被管理员禁用"
                running -> "运行中"
                else -> "已停止"
            }
        }
        tile.updateTile()

        if (running) {
            // 待上传数要读库（挂起），所以异步补一次副标题。
            // 读失败就不补 —— 副标题停在「运行中」比显示一个错的数字好。
            scope.launch {
                val count = runCatching {
                    AppDatabase.getInstance(this@GatewayTileService)
                        .smsQueueDao().getOutstandingCountSync()
                }.getOrNull() ?: return@launch

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@launch
                val current = qsTile ?: return@launch
                current.subtitle = if (count > 0) "运行中 · 待上传 $count" else "运行中 · 全部已上传"
                current.updateTile()
            }
        }
    }

    /**
     * 打开应用（未注册时点磁贴的去处）。
     *
     * API 34 起 `startActivityAndCollapse(Intent)` 被弃用，改用 PendingIntent 那个重载 ——
     * 分版本走，否则在新系统上会抛。
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        Log.i("GatewayTile", "设备未注册，磁贴改为打开应用去扫码")
    }
}
