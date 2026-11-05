package com.morethan.airpipe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log

/**
 * 分享接收器 —— 手机→PC 文件传输主路径（ACTION_SEND / SEND_MULTIPLE）。
 * 读授权随 Intent 授予；这里只取元数据（名字/大小）入队，真正读取由
 * SyncService 流式 openInputStream 128 KiB 循环完成，绝不整读进内存。
 */
class ShareReceiverActivity : Activity() {

    companion object {
        const val TAG = "Airpipe"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // 总开关关闭：分享无处可送（服务停着），显式提示后放弃，不复活服务
        if (!Prefs.syncEnabled(this)) {
            Log.w(TAG, "总开关关闭，忽略分享")
            android.widget.Toast.makeText(
                this, "Airpipe 同步已关闭，请先在下拉磁贴或 App 内开启", android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        // 分享可能发生在服务未启动时（冷启动），先确保常驻服务挂起
        runCatching { startForegroundService(Intent(this, SyncService::class.java)) }

        var handled = false
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> { handleUri(stream); handled = true }
                    !text.isNullOrEmpty() -> { SyncService.enqueueText(this, text); handled = true }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!streams.isNullOrEmpty()) {
                    streams.forEach { handleUri(it) }
                    handled = true
                }
            }
        }
        if (!handled) Log.w(TAG, "分享 Intent 无可处理内容: ${intent?.action}")

        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun handleUri(uri: Uri) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "unnamed.bin"

        val size = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()

        SyncService.enqueueFile(this, uri, name, size != null, size ?: -1L)
        Log.d(TAG, "[分享入队] name=$name size=$size")
    }
}
