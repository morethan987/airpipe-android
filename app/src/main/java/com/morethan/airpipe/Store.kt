package com.morethan.airpipe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.cert.Certificate
import java.time.OffsetDateTime

/**
 * 已配对设备持久化（协议 §4，files/paired.json，org.json 框架自带）。
 * 纯静态 + 进程内缓存：TrustManager 在 TLS 握手线程热读，加载一次即可。
 */
object Store {
    data class Device(
        val name: String,
        val fingerprint: String, // 64 hex = SHA-256(证书 DER)
        val pairedAt: String,
        var lastIp: String,
        var lastSeen: String,
    )

    private val lock = Any()
    private var devices: MutableList<Device> = mutableListOf()
    private var loaded = false

    fun load(ctx: Context): List<Device> {
        synchronized(lock) {
            if (!loaded) {
                loaded = true
                val f = File(ctx.filesDir, "paired.json")
                devices = runCatching {
                    val arr = JSONObject(f.readText()).optJSONArray("devices") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        Device(
                            name = o.optString("name", "pc"),
                            fingerprint = o.optString("fingerprint"),
                            pairedAt = o.optString("paired_at", ""),
                            lastIp = o.optString("last_ip", ""),
                            lastSeen = o.optString("last_seen", ""),
                        )
                    }.toMutableList()
                }.getOrDefault(mutableListOf())
            }
            return devices.toList()
        }
    }

    fun save(ctx: Context) {
        synchronized(lock) {
            val arr = JSONArray()
            devices.forEach {
                arr.put(
                    JSONObject()
                        .put("name", it.name)
                        .put("fingerprint", it.fingerprint)
                        .put("paired_at", it.pairedAt)
                        .put("last_ip", it.lastIp)
                        .put("last_seen", it.lastSeen)
                )
            }
            File(ctx.filesDir, "paired.json").writeText(JSONObject().put("devices", arr).toString())
        }
    }

    fun add(ctx: Context, name: String, fingerprint: String, ip: String) {
        synchronized(lock) {
            devices.removeAll { it.fingerprint == fingerprint }
            devices.add(
                Device(
                    name = name,
                    fingerprint = fingerprint,
                    pairedAt = OffsetDateTime.now().toString(),
                    lastIp = ip,
                    lastSeen = OffsetDateTime.now().toString(),
                )
            )
        }
        save(ctx)
    }

    fun remove(ctx: Context, fingerprint: String) {
        synchronized(lock) {
            devices.removeAll { it.fingerprint == fingerprint }
        }
        save(ctx)
    }

    fun isPaired(ctx: Context, fingerprint: String): Boolean =
        load(ctx).any { it.fingerprint == fingerprint }

    fun updateSeen(ctx: Context, fingerprint: String, ip: String) {
        synchronized(lock) {
            devices.find { it.fingerprint == fingerprint }?.let {
                it.lastIp = ip
                it.lastSeen = OffsetDateTime.now().toString()
            }
        }
        runCatching { save(ctx) }
    }

    // ---- 指纹 ----

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    fun fingerprintOf(cert: Certificate): String = sha256Hex(cert.encoded)

    fun short(fingerprint: String): String = fingerprint.take(8)
}
