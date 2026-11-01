package com.example.emit.link

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Same vectors file as PC app tests/vectors/crypto_vectors.json (RFC KATs + Python-generated interop). */
class CryptoVectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("crypto_vectors.json")!!.readText())

    @Test fun rfc7748() {
        val x = v.getJSONObject("x25519_rfc7748")
        assertEquals(x.getString("alice_pk"), hx(LinkCrypto.publicKey(h(x.getString("alice_sk")))))
        assertEquals(x.getString("bob_pk"), hx(LinkCrypto.publicKey(h(x.getString("bob_sk")))))
        assertEquals(x.getString("shared"), hx(LinkCrypto.dh(h(x.getString("alice_sk")), h(x.getString("bob_pk")))))
        assertEquals(x.getString("shared"), hx(LinkCrypto.dh(h(x.getString("bob_sk")), h(x.getString("alice_pk")))))
    }

    @Test fun rfc8439() {
        val a = v.getJSONObject("chacha20poly1305_rfc8439"); val pt = h(a.getString("pt"))
        val c = ChaCha20Poly1305(); c.init(true, AEADParameters(KeyParameter(h(a.getString("key"))), 128, h(a.getString("nonce")), h(a.getString("aad"))))
        val out = ByteArray(c.getOutputSize(pt.size)); var n = c.processBytes(pt, 0, pt.size, out, 0); n += c.doFinal(out, n)
        assertEquals(a.getString("ct_tag"), hx(out))
    }

    @Test fun rfc5869_and_rfc4231() {
        val k = v.getJSONObject("hkdf_rfc5869_case1")
        assertEquals(k.getString("okm"), hx(LinkCrypto.hkdf(h(k.getString("ikm")), h(k.getString("salt")), h(k.getString("info")), k.getInt("len"))))
        val m = v.getJSONObject("hmac_sha256_rfc4231_case1")
        assertEquals(m.getString("mac"), hx(LinkCrypto.hmac(h(m.getString("key")), h(m.getString("data")))))
    }

    @Test fun interopWithPython() {
        val i = v.getJSONObject("interop"); val pc = h(i.getString("pub_pc")); val ph = h(i.getString("pub_ph"))
        assertEquals(i.getString("pub_pc"), hx(LinkCrypto.publicKey(h(i.getString("sk_pc")))))
        val ss = LinkCrypto.dh(h(i.getString("sk_ph")), pc); assertEquals(i.getString("static_shared"), hx(ss))
        assertEquals(i.getString("commit"), hx(LinkCrypto.pairCommit(ph, pc, h(i.getString("nb")))))
        assertEquals(i.getString("sas"), LinkCrypto.pairSas(pc, ph, h(i.getString("na")), h(i.getString("nb"))))
        val ck = LinkCrypto.pairConfirmKey(ss, pc, ph, h(i.getString("na")), h(i.getString("nb")))
        assertEquals(i.getString("confirm_pc_ok"), hx(LinkCrypto.hmac(ck, "pc-ok".toByteArray())))
        assertEquals(i.getString("confirm_phone_ok"), hx(LinkCrypto.hmac(ck, "phone-ok".toByteArray())))
        val th = LinkCrypto.transcript(i.getString("hello").toByteArray(), i.getString("hello_ok").toByteArray())
        assertEquals(i.getString("transcript"), hx(th))
        val (k1, k2) = LinkCrypto.sessionKeys(LinkCrypto.dh(h(i.getString("e_ph")), LinkCrypto.publicKey(h(i.getString("e_pc")))), ss, th)
        assertEquals(i.getString("k_pc2ph"), hx(k1)); assertEquals(i.getString("k_ph2pc"), hx(k2))
        val rx = AeadStream(k1)
        assertEquals("frame-0", String(rx.open(h(i.getString("pc_frame0"))))); assertEquals("frame-1", String(rx.open(h(i.getString("pc_frame1")))))
        assertEquals(i.getString("ph_frame0"), hx(AeadStream(k2).seal("""{"t":"auth_ok"}""".toByteArray())))
        assertEquals(i.getString("room_id"), RelayWire.roomId(ss))
    }

    private val k = ByteArray(32) { 5 }

    @Test fun everyBitFlipRejected() {
        val ct = AeadStream(k).seal("secret message 123".toByteArray())
        for (byte in ct.indices) for (bit in 0 until 8) {
            val bad = ct.copyOf(); bad[byte] = (bad[byte].toInt() xor (1 shl bit)).toByte()
            assertThrows(Exception::class.java) { AeadStream(k).open(bad) }
        }
    }

    @Test fun replayReorderTruncateWrongKey() {
        val tx = AeadStream(k); val a = tx.seal("a".toByteArray()); val b = tx.seal("b".toByteArray())
        val rx = AeadStream(k); rx.open(a)
        assertThrows(Exception::class.java) { rx.open(a) }
        assertThrows(Exception::class.java) { AeadStream(k).open(b) }
        for (n in 0 until a.size) assertThrows(Exception::class.java) { AeadStream(k).open(a.copyOf(n)) }
        assertThrows(Exception::class.java) { AeadStream(ByteArray(32) { 6 }).open(a) }
    }

    @Test fun noncesUnique() {
        val s = AeadStream(k); val seen = HashSet<String>()
        repeat(5000) { seen += hx(s.seal(ByteArray(0))) }   // same plaintext -> all-different tags because nonce changes
        assertEquals(5000, seen.size); assertEquals(5000L, s.counter)
    }

    @Test fun lowOrderPointRejected() { assertThrows(IllegalArgumentException::class.java) { LinkCrypto.dh(ByteArray(32) { 1 }, ByteArray(32)) } }

    @Test fun frameParserFuzz() {
        val r = Random(1234)
        repeat(3000) {
            val blob = ByteArray(r.nextInt(40)).also { r.nextBytes(it) }
            val io = FrameIO(ByteArrayInputStream(blob), ByteArrayOutputStream())
            try { while (true) io.read() } catch (e: java.io.IOException) { /* EOF or too large: expected */ }
        }
        val big = byteArrayOf(0x7f, 0, 0, 0)
        assertThrows(ProtocolException::class.java) { FrameIO(ByteArrayInputStream(big), ByteArrayOutputStream()).read() }
        val neg = byteArrayOf(0xff.toByte(), 0, 0, 0)
        assertThrows(ProtocolException::class.java) { FrameIO(ByteArrayInputStream(neg), ByteArrayOutputStream()).read() }
    }

    @Test fun downgradeAndTranscriptRejected() {
        val skPh = ByteArray(32) { 3 }
        val hello = """{"t":"hello","v":0,"pc_id":"x","e":"${b64(ByteArray(32){9})}","n":"AA=="}""".toByteArray()
        val out = ByteArrayOutputStream(); val io = FrameIO(ByteArrayInputStream(ByteArray(0)), out)
        assertThrows(ProtocolException::class.java) { PhoneProtocol.sessionResponder(io, hello, "ph", skPh, { ByteArray(32) { 1 } }) }
        assertTrue(String(out.toByteArray()).contains("unsupported version"))
    }
}
