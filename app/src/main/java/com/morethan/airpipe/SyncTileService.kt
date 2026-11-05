package com.morethan.airpipe

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

private const val TAG = "Airpipe"

/**
 * 快捷设置磁贴（Clash 式总开关）：开 = 后台轮询 + 常驻通知；关 = 停 SyncService，
 * 轮询与常驻通知一并消失（开机自启 / 划掉通知复活 / 分享冷启动均不再拉服务）。
 * 与 App 内按钮同走 SyncService.setSyncEnabled，状态持久化（Prefs）。
 */
class SyncTileService : TileService() {

    override fun onStartListening() = update()

    override fun onClick() {
        // 点击时系统持有本服务绑定，属前台交互窗口，允许 startForegroundService
        val target = !Prefs.syncEnabled(this)
        Log.i(TAG, "磁贴点击 → syncEnabled=$target")
        SyncService.setSyncEnabled(this, target)
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (Prefs.syncEnabled(this)) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
