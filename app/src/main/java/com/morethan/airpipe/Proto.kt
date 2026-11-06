package com.morethan.airpipe

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * 协议常量与帧编解码（协议 §5）。
 * 帧 = 1B 类型 + 4B 载荷长度（大端）+ 载荷；读取方发现长度超上限判协议错误断开。
 */
object Proto {
    const val VER = 2
    const val TCP_PORT = 17580
    const val DISCOVER_PORT = 17580
    const val CHUNK = 131072                    // FILE_DATA 单帧上限 128 KiB
    const val MAX_TEXT = 4 * 1024 * 1024        // CLIP_TEXT 载荷上限
    const val MAX_IMAGE = 24 * 1024 * 1024      // CLIP_IMAGE 载荷上限
    const val MAX_FRAME = MAX_IMAGE + 64        // 帧长度合法性上限
    const val PROGRESS_EVERY = 4L * 1024 * 1024 // 接收方每收满回一帧 FILE_PROGRESS
    const val SILENCE = 45_000                  // 双向看门狗：超此时长无对端字节判死链
    const val BACKOFF_MIN = 2_000L
    const val BACKOFF_MAX = 60_000L

    // 帧类型（可打印字符便于抓包调试）
    const val F_HELLO = 'H'.code
    const val F_PING = 'P'.code
    const val F_PONG = 'p'.code
    const val F_TEXT = 'T'.code
    const val F_IMAGE = 'I'.code
    const val F_FILE_BEGIN = 'B'.code
    const val F_FILE_DATA = 'D'.code
    const val F_FILE_END = 'E'.code
    const val F_FILE_PROGRESS = 'R'.code
    const val F_FILE_RESULT = 'F'.code
    const val F_CANCEL = 'X'.code
    const val F_BATCH_BEGIN = 'G'.code
    const val F_PAIR_REQUEST = 'Q'.code
    const val F_PAIR_ACCEPT = 'A'.code
    const val F_BYE = 'Y'.code

    // B 帧 flags
    const val FLAG_SIZE_UNKNOWN = 0x01
    const val FLAG_DIRECTORY = 0x02

    // D 帧 flags（v2, §5）：载荷 = id u32 + flags u8 + 数据
    const val FLAG_DF_ZSTD = 0x01

    // F 帧 status
    const val FSTATUS_OK = 0
    const val FSTATUS_HASH_MISMATCH = 1
    const val FSTATUS_IO = 2
    const val FSTATUS_CANCELLED = 3

    /** 写一帧（调用方负责串行化/flush）。 */
    fun writeFrame(out: DataOutputStream, type: Int, payload: ByteArray = EMPTY) {
        out.write(type)
        out.writeInt(payload.size)
        out.write(payload)
    }

    /** 读一帧：返回 (类型, 载荷)。长度超限抛 IOException 由上层断开。 */
    fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
        val type = input.read()
        if (type < 0) throw IOException("stream closed")
        val len = input.readInt()
        if (len < 0 || len > MAX_FRAME) throw IOException("frame length $len out of range")
        val payload = ByteArray(len)
        input.readFully(payload)
        return type to payload
    }

    // ---- 载荷构造器（全部大端小整数） ----

    fun helloPayload(name: String, caps: Int): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        require(n.size <= 255)
        return buildPayload(2 + 1 + n.size + 4) {
            writeShort(VER); writeByte(n.size); write(n); writeInt(caps)
        }
    }

    fun clipPayload(mime: String, data: ByteArray): ByteArray {
        val m = mime.toByteArray(Charsets.UTF_8)
        require(m.size <= 255)
        return buildPayload(1 + m.size + data.size) {
            writeByte(m.size); write(m); write(data)
        }
    }

    fun fileBeginPayload(id: Int, flags: Int, relPath: String, size: Long): ByteArray {
        val p = relPath.toByteArray(Charsets.UTF_8)
        require(p.size <= 65535)
        return buildPayload(4 + 1 + 2 + p.size + 8) {
            writeInt(id); writeByte(flags); writeShort(p.size); write(p); writeLong(size)
        }
    }

    fun idPayload(id: Int): ByteArray = buildPayload(4) { writeInt(id) }

    fun progressPayload(id: Int, bytes: Long): ByteArray =
        buildPayload(12) { writeInt(id); writeLong(bytes) }
    /** 写 D 帧（v2）：直接流式写出 id+flags+数据，避免 128KiB 载荷二次拷贝。 */
    fun writeFileData(out: DataOutputStream, id: Int, zstd: Boolean, data: ByteArray) {
        out.write(F_FILE_DATA)
        out.writeInt(4 + 1 + data.size)
        out.writeInt(id)
        out.writeByte(if (zstd) FLAG_DF_ZSTD else 0)
        out.write(data)
    }

    fun fileResultPayload(id: Int, status: Int, sha256: ByteArray?): ByteArray =
        buildPayload(4 + 1 + 32) {
            writeInt(id); writeByte(status); write(sha256 ?: ByteArray(32))
        }

    fun pairRequestPayload(code: String, name: String): ByteArray {
        val c = code.toByteArray(Charsets.UTF_8)
        val n = name.toByteArray(Charsets.UTF_8)
        require(c.size <= 255 && n.size <= 255)
        return buildPayload(1 + c.size + 1 + n.size) {
            writeByte(c.size); write(c); writeByte(n.size); write(n)
        }
    }

    fun byePayload(reason: String): ByteArray {
        val r = reason.toByteArray(Charsets.UTF_8)
        require(r.size <= 255)
        return buildPayload(1 + r.size) { writeByte(r.size); write(r) }
    }

    // ---- 载荷解析器 ----

    fun parseHello(p: ByteArray): Triple<Int, String, Int> {
        val d = java.io.ByteArrayInputStream(p).let { java.io.DataInputStream(it) }
        val ver = d.readShort().toInt() and 0xFFFF
        val nameLen = d.readUnsignedByte()
        val name = ByteArray(nameLen).also { d.readFully(it) }.toString(Charsets.UTF_8)
        val caps = d.readInt()
        return Triple(ver, name, caps)
    }

    /** 解析 `nameLen u8, name`（A）。 */
    fun parseName(p: ByteArray): String {
        val d = java.io.ByteArrayInputStream(p).let { java.io.DataInputStream(it) }
        val nameLen = d.readUnsignedByte()
        return ByteArray(nameLen).also { d.readFully(it) }.toString(Charsets.UTF_8)
    }

    fun parseClip(p: ByteArray): Pair<String, ByteArray> {
        val d = java.io.ByteArrayInputStream(p).let { java.io.DataInputStream(it) }
        val mimeLen = d.readUnsignedByte()
        val mime = ByteArray(mimeLen).also { d.readFully(it) }.toString(Charsets.UTF_8)
        return mime to p.copyOfRange(1 + mimeLen, p.size)
    }

    fun parseFileBegin(p: ByteArray): BeginInfo {
        val d = java.io.ByteArrayInputStream(p).let { java.io.DataInputStream(it) }
        val id = d.readInt()
        val flags = d.readUnsignedByte()
        val pathLen = d.readShort().toInt() and 0xFFFF
        val relPath = ByteArray(pathLen).also { d.readFully(it) }.toString(Charsets.UTF_8)
        val size = d.readLong()
        return BeginInfo(id, flags, relPath, size)
    }

    data class BeginInfo(val id: Int, val flags: Int, val relPath: String, val size: Long)
    data class BatchInfo(val items: Int, val files: Int, val bytes: Long)

    /** 解析 `G`：items u32, files u32, bytes u64。 */
    fun parseBatch(p: ByteArray): BatchInfo {
        val d = java.io.ByteArrayInputStream(p).let { java.io.DataInputStream(it) }
        return BatchInfo(d.readInt(), d.readInt(), d.readLong())
    }

    private val EMPTY = ByteArray(0)

    private inline fun buildPayload(size: Int, block: java.io.DataOutputStream.() -> Unit): ByteArray {
        val bos = java.io.ByteArrayOutputStream(size)
        java.io.DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }
}
