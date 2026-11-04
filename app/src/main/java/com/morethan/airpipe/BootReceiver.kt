package com.morethan.airpipe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 开机 & 覆盖安装后自启 SyncService（HyperOS 还需用户开「自启动」，PairActivity 有引导）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED：覆盖安装会杀进程，这里立即拉起新版本服务
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        Log.i("Airpipe", "自启 SyncService (${intent.action})")
        runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
    }
}
