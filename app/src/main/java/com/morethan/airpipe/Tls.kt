package com.morethan.airpipe

import android.content.Context
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 身份与上下文（协议 §3）：TLS 1.3 + 双向证书认证 + 指纹固定。
 *
 * 框架没有 X509 证书生成 API，这里手写极简 DER X.509 v3 自签证书：
 *   TBSCertificate(v3, serial, sigAlg=ecdsa-with-SHA256, CN=airpipe/<model>,
 *                  validity 10 年, SPKI) → SHA256withECDSA 签名 → Certificate
 *   SEQUENCE(TBSCertificate, sigAlg, BIT STRING signature)
 * 生成后立刻用 CertificateFactory 回读 + cert.verify(pub) 自检，失败即抛（宁可崩不裸奔）。
 * 私钥+证书存 files/identity.p12（PKCS12，密码常量 —— 见偏离项：Android PKCS12
 * 实现对空密码兼容性差，文件在应用私有目录，密码仅是容器格式要求）。
 */
object Tls {
    private const val TAG = "Airpipe"
    private const val KEYSTORE_FILE = "identity.p12"
    private const val ALIAS = "airpipe"
    private const val P12_PASS = "airpipe" // 容器口令，非安全边界（文件在私有目录）

    class Identity(val cert: X509Certificate, val fingerprint: String, val keyStore: KeyStore)

    /**
     * 指纹固定 TrustManager：对端证书 SHA-256(DER) ∈ 已配对列表 → 通过；
     * 不在列表 → 仅当 [allowUnknown]（配对窗口）放行，并由 [onPeer] 上报指纹。
     */
    class PinTrustManager(
        private val ctx: Context,
        private val allowUnknown: () -> Boolean,
        private val onPeer: (String) -> Unit,
    ) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String?) {
            val fp = Store.fingerprintOf(chain[0])
            onPeer(fp)
            if (!Store.isPaired(ctx, fp) && !allowUnknown()) {
                throw CertificateException("unpaired peer fingerprint $fp")
            }
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String?) {
            // 本端只做 TCP 客户端；服务器证书校验在 checkServerTrusted。
            // （TLS 互认中本端证书由 KeyManager 呈递，由 PC 侧校验。）
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 加载（或首次生成）长期身份证书。任何自检失败都会抛出。 */
    @Synchronized
    fun loadOrCreate(ctx: Context, commonName: String): Identity {
        val f = File(ctx.filesDir, KEYSTORE_FILE)
        if (f.exists()) {
            runCatching { return open(ctx, f) }.onFailure {
                Log.w(TAG, "identity.p12 回读失败（${it.message}），重新生成")
            }
        }
        val cn = commonName.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val cert = buildSelfSigned(kp, cn)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, kp.private, P12_PASS.toCharArray(), arrayOf(cert))
        File(ctx.filesDir, KEYSTORE_FILE).outputStream().use { ks.store(it, P12_PASS.toCharArray()) }
        Log.i(TAG, "身份证书已生成 CN=$cn fp=${Store.fingerprintOf(cert)}")
        return open(ctx, f)
    }

    private fun open(ctx: Context, f: File): Identity {
        val ks = KeyStore.getInstance("PKCS12")
        f.inputStream().use { ks.load(it, P12_PASS.toCharArray()) }
        val cert = ks.getCertificate(ALIAS) as? X509Certificate
            ?: throw CertificateException("identity.p12 missing entry")
        cert.checkValidity()
        return Identity(cert, Store.fingerprintOf(cert), ks)
    }

    /** TLS1.3 客户端上下文：KeyManager 挂身份，TrustManager 指纹固定。 */
    fun newClientContext(identity: Identity, trust: PinTrustManager): SSLContext {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.keyStore, P12_PASS.toCharArray())
        val sc = SSLContext.getInstance("TLSv1.3")
        sc.init(kmf.keyManagers, arrayOf<TrustManager>(trust), SecureRandom())
        return sc
    }

    // ---------------------------------------------------------------- DER X.509

    private fun buildSelfSigned(kp: KeyPair, cn: String): X509Certificate {
        val ecdsaSha256 = oid("1.2.840.10045.4.3.2")
        val cnOid = oid("2.5.4.3")
        val dn = der(0x30, der(0x31, der(0x30, cnOid, der(0x0C, utf8(cn)))))

        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_MONTH, -1) // 容忍设备时钟轻微超前
        val notBefore = utcTime(cal)
        cal.add(Calendar.YEAR, 10)
        val notAfter = utcTime(cal)

        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))), // [0] EXPLICIT version v3 (= INTEGER 2)
            der(0x02, BigInteger(120, SecureRandom()).setBit(0)), // serial, 正数
            der(0x30, ecdsaSha256),                // signature AlgorithmIdentifier
            dn,                                    // issuer（自签 = subject）
            der(0x30, der(0x17, notBefore), der(0x17, notAfter)), // validity UTCTime
            dn,                                    // subject
            kp.public.encoded,                     // SubjectPublicKeyInfo（JCE 已产 DER）
        )

        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(kp.private); update(tbs)
        }.sign()

        val certDer = der(0x30, tbs, der(0x30, ecdsaSha256), der(0x03, byteArrayOf(0) + sig))

        // 回读自检：能解析 + 签名可验 + 域正确，任一失败即抛
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        cert.verify(kp.public)
        cert.checkValidity()
        return cert
    }

    private fun utcTime(cal: Calendar): ByteArray {
        val s = "%02d%02d%02d%02d%02d%02dZ".format(
            cal.get(Calendar.YEAR) % 100, cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
        return s.toByteArray(Charsets.US_ASCII)
    }

    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    // ---- 极简 DER 编码 ----
    private fun derLen(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val body = ByteArray(4)
        var l = len
        var n = 0
        val bos = ByteArrayOutputStream()
        while (l > 0) { body[n++] = (l and 0xFF).toByte(); l = l shr 8 }
        val out = DataOutputStream(bos)
        out.write(0x80 or n)
        for (i in n - 1 downTo 0) out.write(body[i].toInt())
        return bos.toByteArray()
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val len = parts.sumOf { it.size }
        val out = ByteArrayOutputStream(len + 8)
        out.write(tag)
        out.write(derLen(len))
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun der(tag: Int, n: BigInteger): ByteArray = der(tag, n.toByteArray())

    /** OID "1.2.840..." → DER（首两弧 40*a+b，其余 base-128）。 */
    private fun oid(s: String): ByteArray {
        val arcs = s.split('.').map { it.toInt() }
        val bos = ByteArrayOutputStream()
        bos.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var v = arcs[i]
            val stack = ArrayDeque<Int>()
            stack.addFirst(v and 0x7F)
            v = v shr 7
            while (v > 0) { stack.addFirst((v and 0x7F) or 0x80); v = v shr 7 }
            while (stack.isNotEmpty()) bos.write(stack.removeFirst())
        }
        return der(0x06, bos.toByteArray())
    }
}
