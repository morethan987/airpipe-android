package com.morethan.airpipe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 常驻通知被用户划掉时的兜底：Android 13+ 允许用户手势划掉 FGS 通知
 * （setOngoing 拦不住），服务照常存活但通知没了。
 * deleteIntent 指到这里，立即重拉 SyncService，由 onStartCommand 重挂通知。
 */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 总开关关闭：可能是关闭瞬间的迟到 deleteIntent，不复活
        if (!Prefs.syncEnabled(context)) return
        Log.d("Airpipe", "常驻通知被划掉，兜底重发")
        runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
    }
}
