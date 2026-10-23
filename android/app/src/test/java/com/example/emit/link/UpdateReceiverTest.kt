package com.example.emit.link

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class UpdateReceiverTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val good = ApkInfo("com.example.emit", 21, "0.1.20", setOf("aa"))
    private fun rx(verify: (File) -> ApkInfo = { good }) = UpdateReceiver(Files.createTempDirectory("upd").toFile(), verify)
    private val data = ByteArray(2_500_000) { (it * 31).toByte() }

    @Test fun chunkedRoundTrip() {
        val r = rx(); val s = sha(data)
        var off = r.begin(s, data.size.toLong())
        while (off < data.size) off = r.chunk(s, off, data.copyOfRange(off.toInt(), minOf(data.size, off.toInt() + UpdateReceiver.CHUNK)))
        val ready = r.finish(s)
        assertArrayEquals(data, ready.file.readBytes()); assertEquals(21L, ready.info.versionCode)
    }

    @Test fun resumeAndOutOfOrder() {
        val r = rx(); val s = sha(data)
        r.begin(s, data.size.toLong()); r.chunk(s, 0, data.copyOfRange(0, 1000))
        assertEquals(1000L, r.begin(s, data.size.toLong()))            // resume point
        assertEquals(1000L, r.chunk(s, 5, byteArrayOf(1, 2)))          // wrong offset ignored
        assertThrows(RpcError::class.java) { r.chunk(s, 1000, ByteArray(data.size)) }  // overflow
    }

    @Test fun hashMismatchDiscarded() {
        val r = rx(); val s = "0".repeat(64)
        r.begin(s, 10); r.chunk(s, 0, ByteArray(10))
        val e = assertThrows(RpcError::class.java) { r.finish(s) }; assertEquals("bad_hash", e.code); assertNull(r.ready)
    }

    @Test fun badInputs() {
        val r = rx()
        assertThrows(RpcError::class.java) { r.begin("xyz", 10) }
        assertThrows(RpcError::class.java) { r.begin("a".repeat(64), 0) }
        assertThrows(RpcError::class.java) { r.begin("a".repeat(64), UpdateReceiver.MAX_APK + 1) }
        assertThrows(RpcError::class.java) { r.chunk("a".repeat(64), 0, ByteArray(1)) }
    }

    @Test fun verifierRejectionPropagates() {
        val r = rx { throw RpcError("bad_signer", "signing certificate does not match the installed app") }
        val s = sha(byteArrayOf(1, 2, 3)); r.begin(s, 3); r.chunk(s, 0, byteArrayOf(1, 2, 3))
        assertEquals("bad_signer", assertThrows(RpcError::class.java) { r.finish(s) }.code); assertNull(r.ready)
    }

    @Test fun rules() {
        UpdateRules.check(good, "com.example.emit", 20, setOf("aa"))
        fun code(a: ApkInfo?, v: Long = 20, sig: Set<String> = setOf("aa")) =
            assertThrows(RpcError::class.java) { UpdateRules.check(a, "com.example.emit", v, sig) }.code
        assertEquals("bad_apk", code(null))
        assertEquals("bad_apk", code(good.copy(packageName = "evil.app")))
        assertEquals("bad_signer", code(good.copy(signers = setOf("bb"))))
        assertEquals("bad_signer", code(good.copy(signers = setOf("aa", "bb"))))
        assertEquals("bad_signer", code(good.copy(signers = emptySet()), sig = emptySet()))
        assertEquals("not_newer", code(good, v = 21))
        assertEquals("not_newer", code(good.copy(versionCode = 5)))
    }
}
