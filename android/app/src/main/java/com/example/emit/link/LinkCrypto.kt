package com.example.emit.link

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

/** Byte-identical with PC app msms/crypto.py (checked by LinkCryptoTest vectors). Pure JVM. */
object LinkCrypto {
    private val rng = SecureRandom()
    fun random(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
    fun genSecret(): ByteArray = random(32)
    fun publicKey(sk: ByteArray) = ByteArray(32).also { X25519.generatePublicKey(sk, 0, it, 0) }
    fun dh(sk: ByteArray, pk: ByteArray): ByteArray {
        require(pk.size == 32) { "bad public key" }
        val out = ByteArray(32)
        X25519.scalarMult(sk, 0, pk, 0, out, 0)
        require(out.any { it != 0.toByte() }) { "low-order public key" }
        return out
    }
    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, n: Int): ByteArray =
        ByteArray(n).also { HKDFBytesGenerator(SHA256Digest()).apply { init(HKDFParameters(ikm, salt, info)) }.generateBytes(it, 0, n) }
    fun hmac(key: ByteArray, msg: ByteArray): ByteArray = HMac(SHA256Digest()).run {
        init(KeyParameter(key)); update(msg, 0, msg.size); ByteArray(32).also { doFinal(it, 0) } }
    fun lp(b: ByteArray) = byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()) + b
    fun eq(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun s(x: String) = x.toByteArray()
    fun pairCommit(pubPh: ByteArray, pubPc: ByteArray, nb: ByteArray) = sha256(s("msms-commit-v1"), pubPh, pubPc, nb)
    fun pairSas(pubPc: ByteArray, pubPh: ByteArray, na: ByteArray, nb: ByteArray): String {
        val h = sha256(s("msms-sas-v1"), pubPc, pubPh, na, nb)
        val v = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        return "%06d".format(v % 1_000_000)
    }
    fun pairConfirmKey(shared: ByteArray, pubPc: ByteArray, pubPh: ByteArray, na: ByteArray, nb: ByteArray) =
        hkdf(shared, sha256(s("msms-pair-v1"), pubPc, pubPh, na, nb), s("msms-pair-confirm"), 32)
    fun transcript(helloPc: ByteArray, helloPh: ByteArray) = sha256(s("msms-hs-v1"), lp(helloPc), lp(helloPh))
    /** (pc->phone, phone->pc) */
    fun sessionKeys(ee: ByteArray, ss: ByteArray, th: ByteArray): Pair<ByteArray, ByteArray> {
        val okm = hkdf(ee + ss, th, s("msms-session-v1"), 64)
        return okm.copyOfRange(0, 32) to okm.copyOfRange(32, 64)
    }
}

/** One direction: ChaCha20-Poly1305, nonce = 4 zero bytes || 8-byte BE counter, strictly sequential. */
class AeadStream(private val key: ByteArray) {
    var counter = 0L; private set
    private fun nonce() = ByteArray(12).also { n -> for (i in 0 until 8) n[4 + i] = (counter ushr (56 - 8 * i)).toByte() }
    private fun run(enc: Boolean, input: ByteArray): ByteArray {
        if (counter == Long.MAX_VALUE) throw IllegalStateException("nonce space exhausted")
        val c = ChaCha20Poly1305(); c.init(enc, AEADParameters(KeyParameter(key), 128, nonce()))
        val out = ByteArray(c.getOutputSize(input.size))
        var n = c.processBytes(input, 0, input.size, out, 0); n += c.doFinal(out, n)
        counter++
        return if (n == out.size) out else out.copyOf(n)
    }
    @Synchronized fun seal(pt: ByteArray) = run(true, pt)
    @Synchronized fun open(ct: ByteArray) = run(false, ct)
}
