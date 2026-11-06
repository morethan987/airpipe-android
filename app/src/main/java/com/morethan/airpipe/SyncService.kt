package com.morethan.airpipe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import com.github.luben.zstd.Zstd
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread


/** 配对请求（PairActivity 发起，SyncService clientLoop 消费）。 */
object PairCtl {
    @Volatile var request = false
    @Volatile var code = ""
    @Volatile var host: String? = null // 手动 IP（高级），null=UDP 发现
    @Volatile var result: Pair<Boolean, String>? = null
    val lock = Object()
}

/**
 * 常驻前台服务（手机侧全量实现）。
 *  - HyperOS 静默丢弃入站连接 → 手机只做 TCP 客户端主动出站
 *  - 退避 2s 起指数翻倍封顶 60s；用户动作 nudge 立即全速重拨
 *  - TLS 1.3 双向证书认证，指纹固定（配对窗口内放行未知指纹）
 *  - 看门狗 soTimeout=45s：无对端字节即判死链断开重拨
 *  - 出站队列 ConcurrentLinkedQueue<Job>（引用，不缓存内容）；
 *    文件整帧进 socket 且收 F(0) 且 sha256 相符才出队，失败留队补发
 */
class SyncService : Service() {

    companion object {
        private const val TAG = "Airpipe"
        private const val CHANNEL_ID = "airpipe_channel"
        private const val NOTIFICATION_ID = 1
        private const val RECEIVE_DIR = "/storage/emulated/0/Airpipe"
        private const val CAPS = 0b111 // 文本 | 图片 | 文件

        // ---- 进程级共享状态（蹦床/分享/配对页在任意线程投递） ----
        private val queue = ConcurrentLinkedQueue<Job>()
        private val fileResults = HashMap<Int, CompletableFuture<FileResult>>()
        private val resultLock = Any()

        @Volatile private var nudge = false
        @Volatile private var pcHost: String? = null
        @Volatile private var pcPort = Proto.TCP_PORT
        @Volatile private var statusText = "启动中…"
        @Volatile private var lastEvent = "收件目录 $RECEIVE_DIR"
        @Volatile private var degradedCopy: String? = null // 写剪贴板失败的待复制文本
        @Volatile private var currentSocket: Socket? = null
        @Volatile private var identity: Tls.Identity? = null


        sealed interface Job
        data class TextJob(val mime: String, val bytes: ByteArray) : Job
        data class FileJob(
            val uri: Uri,
            val name: String,
            val sizeKnown: Boolean,
            val size: Long,
        ) : Job

        class FileResult(val status: Int, val sha256: ByteArray?)

        // 文件级流水线窗口（协议 §6）：E 已发而 F 未回的文件数上限。
        // 消除逐文件 RTT+落盘串行（transfer-perf.md §4：小文件一半耗时在等 F）
        const val SEND_WINDOW = 4

        // v2 传输压缩：与 PC transfer.rs INCOMPRESSIBLE 同步的熵编码扩展名表
        // （zstd 对其纯烧 CPU，实测比率 1.000）；小写比较
        private val INCOMPRESSIBLE = setOf(
            "zip", "rar", "7z", "gz", "xz", "zst", "bz2", "br", "lz4", "sz", "tgz", "tbz2", "txz", "crx",
            "png", "jpg", "jpeg", "webp", "gif", "heic", "heif", "avif", "jxl",
            "mp4", "mov", "mkv", "webm", "avi", "m4v", "3gp", "mpeg", "mpg", "ts", "flv", "wmv",
            "mp3", "m4a", "aac", "flac", "ogg", "opus", "wma", "ape", "mka",
            "apk", "aab", "jar", "dex", "docx", "xlsx", "pptx", "epub", "cbz", "iso", "dmg", "deb", "rpm",
        )

        /** 出站文件是否值得压缩（auto 策略：仅按扩展名跳过已熵编码格式）。 */
        fun shouldCompress(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext.isEmpty() || ext !in INCOMPRESSIBLE
        }

        fun enqueueText(context: Context, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > Proto.MAX_TEXT) {
                event("文本超过 4 MiB，跳过")
                return
            }
            // 文本条目合并：剪贴板语义天然只保留最新一条（协议 §8）
            queue.removeAll { it is TextJob }
            queue.add(TextJob("text/plain;charset=utf-8", bytes))
            kick(context)
        }

        fun enqueueFile(context: Context, uri: Uri, name: String, sizeKnown: Boolean, size: Long) {
            queue.add(FileJob(uri, name, sizeKnown, size))
            kick(context)
        }

        fun startPairing(context: Context, code: String, host: String?) {
            synchronized(PairCtl.lock) {
                PairCtl.code = code
                PairCtl.host = host?.takeIf { it.isNotBlank() }
                PairCtl.result = null
                PairCtl.request = true
            }
            kick(context, hard = true)
        }

        /** nudge：立即全速重拨；hard=true 时先掐断现有连接（配对必须重建 TLS）。 */
        private fun kick(context: Context, hard: Boolean = false) {
            // 总开关关闭时任何入队/nudge 都不拉服务（分享侧有 Toast 显式提示）
            if (!Prefs.syncEnabled(context)) return
            nudge = true
            if (hard) runCatching { currentSocket?.close() }
            runCatching { context.startForegroundService(Intent(context, SyncService::class.java)) }
        }

        /**
         * 轮询总开关（磁贴 / App 按钮共用）：开 = 拉起常驻服务；关 = 停服务，
         * 轮询与常驻通知一并消失。开机自启/通知划掉兜底/分享冷启动均先查 Prefs，不复活。
         */
        fun setSyncEnabled(ctx: Context, enabled: Boolean) {
            Log.i(TAG, "总开关切换 → $enabled")
            Prefs.setSyncEnabled(ctx, enabled)
            if (enabled) {
                runCatching { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) }
            } else {
                statusText = "同步已关闭"
                runCatching { ctx.stopService(Intent(ctx, SyncService::class.java)) }
                // FGS 通知随服务销毁移除；兜底再撤一次（时序残影）
                runCatching {
                    ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
            }
        }

        @Volatile private var notifCtx: Context? = null

        fun status(): String = statusText
        fun event(): String = lastEvent
        fun degradedPending(): Boolean = degradedCopy != null
        fun degradedCopyText(): String? = degradedCopy

        fun event(msg: String) {
            Log.i(TAG, msg)
            lastEvent = msg
            notifCtx?.let { refreshNotification(it) }
        }

        class Prog(val label: String, val total: Long, val done: Long)
        @Volatile private var transfer: Prog? = null
        @Volatile private var lastProgNotif = 0L

        /** 传输进度上报（发送/接收共用），300ms 节流刷新常驻通知进度条。 */
        fun reportProgress(label: String, total: Long, done: Long) {
            transfer = Prog(label, total, done)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgNotif > 300) {
                lastProgNotif = now
                notifCtx?.let { refreshNotification(it) }
            }
        }

        fun clearTransfer() {
            if (transfer != null) {
                transfer = null
                notifCtx?.let { refreshNotification(it) }
            }
        }

        fun updateStatus(ctx: Context, s: String) {
            if (statusText == s && notifCtx != null) return
            statusText = s
            refreshNotification(ctx)
        }

        private fun refreshNotification(ctx: Context) {
            // 总开关已关：静态入口（enqueue/event）不得复活通知
            if (!Prefs.syncEnabled(ctx)) return
            runCatching {
                (ctx.getSystemService(NotificationManager::class.java))
                    .notify(NOTIFICATION_ID, buildNotif(ctx))
            }
        }

        private fun buildNotif(ctx: Context): Notification {
            // 点通知 = 同步（蹦床读剪贴板入队 + nudge），不开 App
            val sync = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, TrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val copy = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, TrampolineActivity::class.java)
                    .putExtra(TrampolineActivity.EXTRA_WRITE_CLIP, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val openApp = PendingIntent.getActivity(
                ctx, 3,
                Intent(ctx, PairActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val delete = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, DismissReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val b = Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle("Airpipe — $statusText")
                .setContentText(lastEvent)
                .setSmallIcon(R.drawable.ic_stat_airpipe)
                .setContentIntent(sync)
                .setOngoing(true)
                .setDeleteIntent(delete)
                .setOnlyAlertOnce(true)
            val t = transfer
            if (t != null) {
                if (t.total > 0) {
                    val pct = (t.done * 100 / t.total).toInt().coerceIn(0, 100)
                    b.setContentText("${t.label}  $pct%")
                    b.setProgress(100, pct, false)
                } else {
                    b.setContentText("${t.label}  传输中…")
                    b.setProgress(0, 0, true)
                }
            } else {
                b.setContentText(lastEvent)
            }
            if (Store.load(ctx).isEmpty()) {
                b.addAction(android.R.drawable.ic_menu_add, "配对", openApp)
            } else {
                b.addAction(android.R.drawable.ic_menu_compass, "打开", openApp)
            }
            if (degradedCopy != null) b.addAction(android.R.drawable.ic_menu_edit, "复制", copy)
            return b.build()
        }
    }

    @Volatile private var alive = false
    @Volatile private var sessionReady = false
    @Volatile private var allowUnknownPeer = false
    @Volatile private var peerFingerprint: String? = null
    @Volatile private var peerName: String? = null
    private val sendLock = Any()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // startForegroundService() 后必须尽快 startForeground()
        notifCtx = applicationContext
        startForeground(NOTIFICATION_ID, buildNotif(this))
        // 总开关关闭（START_STICKY 复活/残留 start）：满足 FGS 契约后立即自灭
        if (!Prefs.syncEnabled(this)) {
            stopSelf()
            return
        }
        alive = true
        thread(name = "airpipe-loop") { clientLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs.syncEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 13+ 用户可划掉 FGS 通知而服务存活；onStartCommand 每次重挂通知
        startForeground(NOTIFICATION_ID, buildNotif(this))
        nudge = true
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        runCatching { currentSocket?.close() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- loop

    private fun clientLoop() {
        // 身份证书：首次运行生成 + DER 自签自检，失败即抛（日志留证）
        try {
            identity = Tls.loadOrCreate(this, "airpipe/${android.os.Build.MODEL}")
            Log.i(TAG, "身份就绪 fp=${identity!!.fingerprint}")
        } catch (e: Exception) {
            Log.e(TAG, "身份证书生成/自检失败，服务停摆", e)
            updateStatus(this, "身份错误")
            return
        }
        Store.load(this)

        var backoffMs = 0L
        while (alive) {
            val pairReq = synchronized(PairCtl.lock) {
                if (PairCtl.request) { PairCtl.request = false; true } else false
            }
            if (Store.load(this).isEmpty() && !pairReq) {
                updateStatus(this, "未配对")
                Thread.sleep(500)
                continue
            }
            if (backoffMs > 0) {
                updateStatus(this, "退避等待中…")
                val deadline = System.nanoTime() + backoffMs * 1_000_000
                while (alive && !nudge && System.nanoTime() < deadline) Thread.sleep(200)
                if (nudge) Log.i(TAG, "nudge：立即全速重拨")
                nudge = false
            }

            // 目标地址：配对手动 IP > 缓存地址 > UDP 发现
            var target: String? = if (pairReq) PairCtl.host else pcHost
            if (target == null) {
                discoverPc()?.let { pcHost = it.first; pcPort = it.second }
                target = pcHost
            }
            if (target == null) {
                if (pairReq) finishPair(false, "未发现 PC（广播不可达时用手动 IP）")
                backoffMs = nextBackoff(backoffMs)
                continue
            }
            val port = if (pairReq && PairCtl.host != null) Proto.TCP_PORT else pcPort
            try {
                connectAndRun(target, port, pairReq)
                backoffMs = 0
            } catch (e: PairDone) {
                // 配对连接由对端关闭（成功/失败都已上报），正常退避
            } catch (e: IOException) {
                Log.d(TAG, "连接结束(${e.message})")
                if (pairReq) finishPair(false, "连接失败：${e.message}")
                pcHost = null // 地址可能已失效（DHCP），下轮重新发现
            }
            backoffMs = nextBackoff(backoffMs)
        }
    }

    private class PairDone : IOException()

    private fun nextBackoff(cur: Long): Long =
        if (cur < 500) Proto.BACKOFF_MIN else minOf(cur * 2, Proto.BACKOFF_MAX)

    private fun connectAndRun(host: String, port: Int, pairReq: Boolean) {
        updateStatus(this, "连接中…")
        val trust = Tls.PinTrustManager(this, { allowUnknownPeer }) { fp ->
            peerFingerprint = fp
        }
        allowUnknownPeer = pairReq
        val sslContext: SSLContext = Tls.newClientContext(identity!!, trust)

        val raw = Socket().apply { tcpNoDelay = true }
        currentSocket = raw
        raw.connect(InetSocketAddress(host, port), 5_000)
        peerFingerprint = null
        val sock = raw.startHandshakeSsl(sslContext)
        sock.soTimeout = Proto.SILENCE
        // 握手已同步完成（startHandshakeSsl 内 startHandshake）；指纹从会话
        // 证书链确定性读取，不依赖 TrustManager 回调时序。
        peerFingerprint = runCatching {
            Store.fingerprintOf(sock.session.peerCertificates[0])
        }.getOrNull()
        Log.i(TAG, "TLS 已建立 peer=$host fp=${peerFingerprint?.let { Store.short(it) }}")

        val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
        val input = DataInputStream(BufferedInputStream(sock.getInputStream()))

        // ---- 配对交换：Q → 等 A（成功后补发 HELLO 进入正常会话，协议 §4） ----
        var helloDuringPairing: String? = null
        if (pairReq) {
            sock.soTimeout = 65_000
            val code = synchronized(PairCtl.lock) { PairCtl.code }
            synchronized(sendLock) {
                Proto.writeFrame(out, Proto.F_PAIR_REQUEST,
                    Proto.pairRequestPayload(code, android.os.Build.MODEL))
                out.flush()
            }
            // PC 在 TLS 完成后立即发 HELLO（会话建立），配对 A 在其后；收到 H 就
            // 当场消化（校验版本 + 记下会话状态），避免配对完成后再空等 10 s。
            var accepted = false
            while (!accepted) {
                val (type, payload) = Proto.readFrame(input)
                when (type) {
                    Proto.F_PAIR_ACCEPT -> {
                        val pcName = Proto.parseName(payload)
                        val fp = peerFingerprint!!
                        Store.add(this, pcName, fp, host)
                        event("配对成功：$pcName (${Store.short(fp)})")
                        finishPair(true, "已配对 $pcName")
                        pcHost = host
                        pcPort = port
                        updateSeen(fp, host)
                        accepted = true
                    }
                    Proto.F_HELLO -> {
                        val (ver, name, _) = Proto.parseHello(payload)
                        if (ver != Proto.VER) {
                            finishPair(false, "PC 版本不符")
                            throw PairDone()
                        }
                        helloDuringPairing = name
                        Log.i(TAG, "配对中收到 HELLO：$name")
                    }
                    Proto.F_BYE -> {
                        finishPair(false, "被拒：${String(payload.copyOfRange(1, payload.size))}")
                        throw PairDone()
                    }
                    else -> { finishPair(false, "配对应答异常 0x${type.toString(16)}"); throw PairDone() }
                }
            }
        } else {
            val fp = peerFingerprint!!
            if (!Store.isPaired(this, fp)) {
                Log.w(TAG, "未配对指纹 ${Store.short(fp)}，断开")
                runCatching { sock.close() }
                throw IOException("unpaired peer")
            }
            updateSeen(fp, host)
        }

        // ---- HELLO 互发（ver 不符收 BYE，协议 §5） ----
        // 重置会话状态，再应用配对阶段已消化的对端 HELLO（若有）。
        sessionReady = false
        peerName = null
        helloDuringPairing?.let {
            peerName = it
            sessionReady = true
        }
        synchronized(sendLock) {
            Proto.writeFrame(out, Proto.F_HELLO, Proto.helloPayload(android.os.Build.MODEL, CAPS))
            out.flush()
        }
        val reader = thread(name = "airpipe-reader") {
            readLoop(sock, out, host)
            // 读流终结 = 连接已死：促 writer 退出去重拨
            runCatching { sock.close() }
            synchronized(resultLock) {
                fileResults.values.forEach { it.completeExceptionally(EOF()) }
                fileResults.clear()
            }
        }
        try {
            writerLoop(sock, out)
        } finally {
            clearTransfer()
            runCatching { sock.close() }
            runCatching { reader.interrupt() }
        }
    }

    private fun Socket.startHandshakeSsl(sc: SSLContext): SSLSocket =
        (sc.socketFactory.createSocket(this, this.inetAddress.hostAddress, this.port, true) as SSLSocket)
            .also { it.startHandshake() }

    private class EOF : IOException("connection closed")

    private fun updateSeen(fp: String, host: String) = runCatching { Store.updateSeen(this, fp, host) }

    private fun finishPair(ok: Boolean, msg: String) {
        event(msg)
        synchronized(PairCtl.lock) {
            PairCtl.result = ok to msg
            PairCtl.host = null
            (PairCtl.lock as Object).notifyAll()
        }
    }

    /** UDP 广播发现（协议 §2）：应答源地址即 PC IP，返回 (host, tcpPort)。 */
    private fun discoverPc(): Pair<String, Int>? = runCatching {
        updateStatus(this, "发现中…")
        DatagramSocket().use { ds ->
            ds.broadcast = true
            ds.soTimeout = 800
            val msg = "AIRPIPE_DISCOVER ${Proto.VER}".toByteArray()
            repeat(3) {
                runCatching {
                    ds.send(DatagramPacket(msg, msg.size,
                        InetAddress.getByName("255.255.255.255"), Proto.DISCOVER_PORT))
                }
                try {
                    val buf = ByteArray(128)
                    val resp = DatagramPacket(buf, buf.size)
                    ds.receive(resp)
                    val parts = String(resp.data, 0, resp.length).trim().split(" ")
                    if (parts.size == 4 && parts[0] == "AIRPIPE_PC" && parts[1].toInt() == Proto.VER) {
                        val host = resp.address?.hostAddress ?: return@runCatching null
                        val port = parts[2].toIntOrNull() ?: Proto.TCP_PORT
                        Log.i(TAG, "UDP 发现 PC $host:$port fp=${parts[3]}")
                        return host to port
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
            null
        }
    }.getOrNull()

    // ---------------------------------------------------------------- writer

    private fun writerLoop(sock: SSLSocket, out: DataOutputStream) {
        // 等 HELLO 对端帧到达（reader 校验 ver 后置位）
        val deadline = System.nanoTime() + 10_000_000_000L
        while (alive && !sessionReady && System.nanoTime() < deadline) Thread.sleep(50)
        if (!sessionReady) throw IOException("peer HELLO timeout")
        updateStatus(this, "已连接 ${peerName ?: pcHost ?: ""}")

        var nextId = 1
        val pending = ArrayDeque<PendingSend>() // 在途：E 已发，F 未回
        val retryQ = ArrayDeque<FileJob>()      // sha 不符，待整文件重发一次
        try {
            while (alive && !sock.isClosed) {
                // 1) 收割完成的结果（readLoop 收 F 后补全 future）
                val iter = pending.iterator()
                while (iter.hasNext()) {
                    val inf = iter.next()
                    if (!inf.future.isDone) continue
                    val res = try {
                        inf.future.get()
                    } catch (e: Exception) {
                        throw IOException("file result wait failed: ${e.message}")
                    }
                    synchronized(resultLock) { fileResults.remove(inf.id) }
                    when {
                        res.status == Proto.FSTATUS_OK &&
                            res.sha256?.contentEquals(inf.localSha) == true -> {
                            iter.remove()
                            event("文件已送达：${inf.job.name}")
                        }
                        res.status == Proto.FSTATUS_IO || res.status == Proto.FSTATUS_CANCELLED -> {
                            iter.remove()
                            event("对端拒收(status=${res.status})：${inf.job.name}")
                        }
                        inf.attempt == 0 -> { // sha 不符整文件重发一次（协议 §6）
                            iter.remove()
                            Log.w(TAG, "sha256 不符，整文件重发：${inf.job.name}")
                            retryQ.addLast(inf.job)
                        }
                        else -> {
                            iter.remove()
                            event("文件发送失败（校验不符）：${inf.job.name}")
                        }
                    }
                }
                if (pending.isEmpty() && retryQ.isEmpty() && queue.isEmpty()) clearTransfer()

                // 2) 派发：重发优先，随后队头。文本随时穿插（单写线程，发送仍保序），
                //    文件受 SEND_WINDOW 约束；不可读（URI 授权失效/文件被删）终态放弃不堵队列
                dispatch@ while (pending.size < SEND_WINDOW) {
                    val retryJob = retryQ.removeFirstOrNull()
                    if (retryJob != null) {
                        val inf = try {
                            startFile(out, retryJob, nextId++, 1)
                        } catch (e: IOException) {
                            retryQ.addFirst(retryJob); throw e
                        }
                        pending.addLast(inf)
                        continue@dispatch
                    }
                    when (val head = queue.peek() ?: break@dispatch) {
                        is TextJob -> {
                            synchronized(sendLock) {
                                Proto.writeFrame(out, Proto.F_TEXT, Proto.clipPayload(head.mime, head.bytes))
                                out.flush()
                            }
                            queue.poll()
                            event("已推送剪贴板文本 ${head.bytes.size}B")
                        }
                        is FileJob -> {
                            if (!runCatching {
                                    contentResolver.openInputStream(head.uri)?.close()
                                    true
                                }.getOrDefault(false)) {
                                queue.poll()
                                event("文件不可读，已跳过：${head.name}")
                                continue@dispatch
                            }
                            queue.poll()
                            val inf = try {
                                startFile(out, head, nextId++, 0)
                            } catch (e: IOException) {
                                queue.add(head); throw e
                            }
                            pending.addLast(inf)
                        }
                    }
                }
                // 3) 窗口满：阻塞等最老结果（120s 上限）；全空闲：小睡
                if (pending.size >= SEND_WINDOW) {
                    try {
                        pending.first().future.get(120, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        throw IOException("file result wait failed: ${e.message}")
                    }
                    continue
                }
                if (pending.isEmpty() && retryQ.isEmpty() && queue.isEmpty()) Thread.sleep(50)
            }
        } finally {
            // 连接级失败/断开：在途与待重发任务重新入队，重拨后补发（顺序可能变化）
            pending.forEach { queue.add(it.job) }
            retryQ.forEach { queue.add(it) }
        }
    }

    /** 一个已派发待收割的出站文件（writerLoop 窗口项）。 */
    private class PendingSend(
        val job: FileJob,
        val id: Int,
        val attempt: Int,
        val future: CompletableFuture<FileResult>,
        val localSha: ByteArray,
    )

    /** 发送单文件 B → D… → E，不等待 F（窗口由 writerLoop 收割）。连接级错误抛 IOException（任务留队重拨补发）。 */
    private fun startFile(out: DataOutputStream, job: FileJob, id: Int, attempt: Int): PendingSend {
        val future = CompletableFuture<FileResult>()
        synchronized(resultLock) { fileResults[id] = future }
        try {
            val flags = if (job.sizeKnown) 0 else Proto.FLAG_SIZE_UNKNOWN
            synchronized(sendLock) {
                Proto.writeFrame(out, Proto.F_FILE_BEGIN,
                    Proto.fileBeginPayload(id, flags, job.name, if (job.sizeKnown) job.size else 0L))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val compress = shouldCompress(job.name)
            val buf = ByteArray(Proto.CHUNK)
            var sent = 0L
            contentResolver.openInputStream(job.uri)?.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        digest.update(buf, 0, n)
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        // v2 传输压缩：每块独立 zstd(level 1)，压不小则回退原样（协议 §5）
                        var wire = chunk
                        var z = false
                        if (compress) {
                            val c = Zstd.compress(chunk, 1)
                            if (c.size < chunk.size) { wire = c; z = true }
                        }
                        synchronized(sendLock) { Proto.writeFileData(out, id, z, wire) }
                        sent += n
                        reportProgress(job.name, if (job.sizeKnown) job.size else -1L, sent)
                    }
                }
            } ?: throw IOException("openInputStream failed: ${job.uri}")
            synchronized(sendLock) {
                Proto.writeFrame(out, Proto.F_FILE_END, Proto.idPayload(id))
                out.flush()
            }
            return PendingSend(job, id, attempt, future, digest.digest())
        } catch (e: Exception) {
            synchronized(resultLock) { fileResults.remove(id) }
            throw e
        }
    }


    // ---------------------------------------------------------------- reader

    private class Inbound(
        val id: Int,
        val file: File,
        val stream: FileOutputStream,
        val digest: java.security.MessageDigest,
        var total: Long = 0,
        var bytes: Long = 0,
        var sinceProgress: Long = 0,
        var failed: Boolean = false,
    )

    private fun readLoop(sock: SSLSocket, out: DataOutputStream, host: String) {
        val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
        val transfers = HashMap<Int, Inbound>()
        try {
            while (true) {
                val (type, payload) = Proto.readFrame(input)
                when (type) {
                    Proto.F_HELLO -> {
                        val (ver, name, _) = Proto.parseHello(payload)
                        if (ver != Proto.VER) {
                            synchronized(sendLock) {
                                Proto.writeFrame(out, Proto.F_BYE, Proto.byePayload("version mismatch"))
                                out.flush()
                            }
                            Log.w(TAG, "对端 HELLO ver=$ver 不符，BYE")
                            return
                        }
                        peerName = name
                        sessionReady = true
                        Log.i(TAG, "对端 HELLO ok: $name")
                    }
                    Proto.F_PING -> synchronized(sendLock) {
                        Proto.writeFrame(out, Proto.F_PONG); out.flush()
                    }
                    Proto.F_TEXT -> handleClipText(payload)
                    Proto.F_IMAGE -> handleClipImage(payload)
                    Proto.F_FILE_BEGIN -> beginInbound(payload, transfers)
                    Proto.F_FILE_DATA -> {
                        if (payload.size < 5) continue
                        val t = transfers[payload.readInt0()] ?: continue
                        if (t.failed) continue
                        try {
                            // 契约 §5（v2）：载荷 = id u32 + flags u8 + 数据；zstd 块解压后落盘，
                            // 解压失败视同写入失败：只废此文件，不断连接
                            val data: ByteArray
                            var off: Int
                            var len: Int
                            if (payload[4].toInt() and Proto.FLAG_DF_ZSTD != 0) {
                                val src = payload.copyOfRange(5, payload.size)
                                val cap = Zstd.getFrameContentSize(src)
                                    .let { if (it in 1..Proto.CHUNK.toLong()) it.toInt() else Proto.CHUNK }
                                val dst = ByteArray(cap)
                                off = 0
                                len = Zstd.decompress(dst, src).toInt()
                                data = dst
                            } else {
                                // raw：数据在载荷偏移 5 处，直接零拷贝引用 payload
                                data = payload
                                off = 5
                                len = payload.size - 5
                            }
                            t.stream.write(data, off, len)
                            t.digest.update(data, off, len)
                            t.bytes += len
                            t.sinceProgress += len
                            reportProgress(t.file.name, t.total, t.bytes)
                            if (t.sinceProgress >= Proto.PROGRESS_EVERY) {
                                t.sinceProgress = 0
                                synchronized(sendLock) {
                                    Proto.writeFrame(out, Proto.F_FILE_PROGRESS,
                                        Proto.progressPayload(t.id, t.bytes)); out.flush()
                                }
                            }
                        } catch (e: Throwable) {
                            t.failed = true
                            runCatching { t.stream.close() }; runCatching { t.file.delete() }
                            event("写入失败：${t.file.name}（${e.message}）")
                            synchronized(sendLock) {
                                Proto.writeFrame(out, Proto.F_FILE_RESULT,
                                    Proto.fileResultPayload(t.id, Proto.FSTATUS_IO, null))
                                Proto.writeFrame(out, Proto.F_CANCEL, Proto.idPayload(t.id))
                                out.flush()
                            }
                        }
                    }
                    Proto.F_FILE_END -> endInbound(payload, transfers, out)
                    Proto.F_FILE_RESULT -> {
                        val id = payload.readInt0()
                        val status = payload[4].toInt() and 0xFF
                        val sha = payload.copyOfRange(5, 37)
                        val fut = synchronized(resultLock) { fileResults[id] }
                        fut?.complete(FileResult(status, sha))
                    }
                    Proto.F_CANCEL -> {
                        val t = transfers[payload.readInt0()] ?: continue
                        t.failed = true
                        runCatching { t.stream.close() }; runCatching { t.file.delete() }
                        transfers.remove(t.id)
                    }
                    Proto.F_BYE -> {
                        Log.i(TAG, "对端 BYE：${payload.drop(1).toByteArray().toString(Charsets.UTF_8)}")
                        return
                    }
                    Proto.F_FILE_PROGRESS -> {
                        // 对端接收进度（每 4MiB 一发，兼存活探针）；无 UI 消费，静默
                    }
                    else -> {
                        Log.w(TAG, "未知帧 0x${type.toString(16)}，忽略")
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "看门狗触发：${Proto.SILENCE / 1000}s 无对端字节，断开死链")
        } catch (e: IOException) {
            Log.d(TAG, "入站连接结束: ${e.message}")
        } finally {
            clearTransfer()
            transfers.values.forEach {
                runCatching { it.stream.close() }
                // 传输中断：.part 半截文件永不暴露正式名，直接清理
                runCatching { File(it.file.parentFile, it.file.name + ".part").delete() }
            }
        }
    }

    private fun ByteArray.readInt0(): Int =
        ((this[0].toInt() and 0xFF) shl 24) or ((this[1].toInt() and 0xFF) shl 16) or
            ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)

    private fun beginInbound(payload: ByteArray, transfers: HashMap<Int, Inbound>) {
        val b = Proto.parseFileBegin(payload)
        val clean = sanitizeRelPath(b.relPath) ?: return run {
            Log.w(TAG, "B 帧路径消毒后为空，忽略")
        }
        val target = uniqueFile(File(RECEIVE_DIR, clean))
        try {
            if (b.flags and Proto.FLAG_DIRECTORY != 0) {
                target.mkdirs()
                event("已创建目录 ${target.name}")
                return
            }
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            transfers[b.id] = Inbound(
                b.id, target,
                FileOutputStream(part),
                MessageDigest.getInstance("SHA-256"),
                total = if (b.flags and Proto.FLAG_SIZE_UNKNOWN != 0) -1L else b.size,
            )
        } catch (e: IOException) {
            Log.w(TAG, "B 帧建文件失败: ${e.message}")
        }
    }

    private fun endInbound(payload: ByteArray, transfers: HashMap<Int, Inbound>, out: DataOutputStream) {
        val id = payload.readInt0()
        val t = transfers.remove(id) ?: return
        clearTransfer()
        val part = File(t.file.parentFile, t.file.name + ".part")
        runCatching { t.stream.close() }
        if (t.failed) return
        val sha = t.digest.digest()
        try {
            if (!part.renameTo(t.file)) throw IOException("rename failed")
            MediaScannerConnectionCompat.scan(this, t.file)
            event("收到文件：${t.file.name}（${t.bytes}B）")
            synchronized(sendLock) {
                Proto.writeFrame(out, Proto.F_FILE_RESULT,
                    Proto.fileResultPayload(id, Proto.FSTATUS_OK, sha)); out.flush()
            }
        } catch (e: Exception) {
            runCatching { part.delete() }
            event("收件失败：${t.file.name}（${e.message}）")
            synchronized(sendLock) {
                Proto.writeFrame(out, Proto.F_FILE_RESULT,
                    Proto.fileResultPayload(id, Proto.FSTATUS_IO, null)); out.flush()
            }
        }
    }

    /** relPath 消毒（协议 §7）：剥前导 /，弃 .. 与空段，其余保留。 */
    private fun sanitizeRelPath(relPath: String): String? =
        relPath.split('/').map { it.trimStart('\\') }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/").ifEmpty { null }

    /** 目标已存在 → 追加 " (1)"、"(2)"，不覆盖。 */
    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val dot = target.name.lastIndexOf('.')
        val base = if (dot > 0) target.name.substring(0, dot) else target.name
        val ext = if (dot > 0) target.name.substring(dot) else ""
        var i = 1
        while (true) {
            val cand = File(target.parentFile, "$base ($i)$ext")
            if (!cand.exists()) return cand
            i++
        }
    }

    private object MediaScannerConnectionCompat {
        fun scan(ctx: Context, f: File) {
            runCatching {
                android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
            }
        }
    }

    // ---------------------------------------------------------------- clipboard

    private fun clipboard(): android.content.ClipboardManager =
        getSystemService(android.content.ClipboardManager::class.java)

    private fun handleClipText(payload: ByteArray) {
        val (_, data) = Proto.parseClip(payload)
        val text = data.toString(Charsets.UTF_8)
        try {
            clipboard().setPrimaryClip(ClipData.newPlainText("airpipe", text))
            event("已写入剪贴板（${data.size}B 文本）")
            degradedCopy = null
        } catch (e: Exception) {
            Log.w(TAG, "写剪贴板失败，降级通知「复制」: ${e.message}")
            degradedCopy = text
            event("PC 文本到达，点通知「复制」写入")
            refreshNotification(this)
        }
    }

    private fun handleClipImage(payload: ByteArray) {
        val (mime, data) = Proto.parseClip(payload)
        if (payload.size > Proto.MAX_IMAGE) {
            Log.w(TAG, "I 帧 ${payload.size}B 超 MAX_IMAGE，忽略")
            return
        }
        try {
            val ext = when {
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                else -> "jpg"
            }
            val name = "airpipe-${System.currentTimeMillis()}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, if (mime.startsWith("image/")) mime else "image/$ext")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(data) }
                ?: throw IOException("openOutputStream failed")
            val cd = ClipData(ClipDescription("airpipe", arrayOf(mime)), ClipData.Item(uri))
            clipboard().setPrimaryClip(cd)
            event("已写入剪贴板（图片 ${data.size}B）")
        } catch (e: Exception) {
            Log.w(TAG, "图片写剪贴板失败: ${e.message}")
            event("PC 图片到达但写入剪贴板失败")
        }
    }


    // ---------------------------------------------------------------- notification

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Airpipe 同步守护", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
