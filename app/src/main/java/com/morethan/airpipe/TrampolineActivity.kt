package com.morethan.airpipe

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * 透明蹦床 —— 探针验证路径：Android 10+ 只有焦点窗口能读剪贴板，
 * onWindowFocusChanged(hasFocus=true) 是唯一可靠读取时点；读完成入队即自毁。
 * 另承担降级「复制」：把 PC 推来但自动写入失败的文本写进剪贴板（写免焦点）。
 * 以及通知「收件目录」动作：拉起系统文件管理器直达收件目录（免焦点，onCreate 即办）。
 */
class TrampolineActivity : Activity() {

    companion object {
        const val TAG = "Airpipe"
        const val EXTRA_WRITE_CLIP = "write_clip"
        const val EXTRA_OPEN_DIR = "open_dir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        // 通知「收件目录」动作：直达文件管理器，不读剪贴板，无需等焦点
        if (intent?.getBooleanExtra(EXTRA_OPEN_DIR, false) == true) {
            openReceiveDir()
            finish()
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
            return
        }
        // 冷启动兜底：确保服务挂着（总开关关闭时不拉，避免复活）
        if (Prefs.syncEnabled(this)) {
            runCatching { startForegroundService(Intent(this, SyncService::class.java)) }
        }
    }

    /**
     * 收件目录 → 系统文件管理器：SAF 文档 URI（ExternalStorageProvider）+
     * MIME_TYPE_DIR，直达 DocumentsUI。免存储权限、免 FileProvider；
     * 不用 file:// + resource/folder（多数文件管理器不支持，Syncthing #838）。
     */
    private fun openReceiveDir() {
        val dir = File(SyncService.RECEIVE_DIR)
        runCatching { dir.mkdirs() }
        runCatching {
            val rel = dir.absolutePath
                .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
                .trim('/')
            val uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel")
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Log.w(TAG, "打开收件目录失败: ${it.message}")
            Toast.makeText(this, "未找到可打开收件目录的文件管理器", Toast.LENGTH_LONG).show()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (intent?.getBooleanExtra(EXTRA_WRITE_CLIP, false) == true) writeDegraded() else dispatchClip()
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    /** 降级路径：写剪贴板免焦点，这里直接写（探针 v0.2 验证）。 */
    private fun writeDegraded() {
        val text = SyncService.degradedCopyText()
        if (text != null) {
            runCatching {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("airpipe", text))
                Log.i(TAG, "降级复制完成 ${text.length} 字符")
            }
        }
    }

    private fun dispatchClip() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = runCatching { clip.getItemAt(0).coerceToText(this) }.getOrNull()
        if (!text.isNullOrEmpty()) {
            SyncService.enqueueText(this, text.toString())
            Log.d(TAG, "蹦床读到文本 ${text.length} 字符，入队")
            return
        }
        val uri = runCatching { clip.getItemAt(0).uri }.getOrNull()
        if (uri != null && uri.scheme == "content") {
            dispatchUri(uri)
        } else {
            Log.d(TAG, "剪贴板为空或不可读")
        }
    }

    /** content:// 文件 URI：读授权随「有焦点地读剪贴板」授予本包，趁还活着取元数据入队。 */
    private fun dispatchUri(uri: Uri) {
        val name = queryColumn(uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment ?: "clipboard.bin"
        val size = queryColumn(uri, OpenableColumns.SIZE)?.toLongOrNull()
        SyncService.enqueueFile(this, uri, name, size != null, size ?: -1L)
        Log.d(TAG, "蹦床读到文件 URI name=$name size=$size，入队")
    }

    private fun queryColumn(uri: Uri, column: String): String? = runCatching {
        contentResolver.query(uri, arrayOf(column), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
