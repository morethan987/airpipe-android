package com.morethan.airpipe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机自启 SyncService（HyperOS 还需用户开「自启动」，PairActivity 有引导）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("Airpipe", "开机自启 SyncService")
        runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
    }
}
