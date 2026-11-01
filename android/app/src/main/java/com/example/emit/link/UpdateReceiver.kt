package com.example.emit.link

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Info pulled from an APK by the platform verifier. */
data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String, val signers: Set<String>)

/** Rules for accepting a pushed APK; pure so it can be unit-tested. */
object UpdateRules {
    fun check(apk: ApkInfo?, installedPackage: String, installedVersion: Long, installedSigners: Set<String>) {
        if (apk == null) throw RpcError("bad_apk", "not a valid APK")
        if (apk.packageName != installedPackage) throw RpcError("bad_apk", "APK is ${apk.packageName}, not Emit")
        if (apk.signers.isEmpty() || installedSigners.isEmpty() || apk.signers != installedSigners)
            throw RpcError("bad_signer", "signing certificate does not match the installed app")
        if (apk.versionCode <= installedVersion) throw RpcError("not_newer", "versionCode ${apk.versionCode} is not newer than installed $installedVersion")
    }
}

/**
 * Phone side of `msms update push`: chunked + resumable, SHA-256 checked, then [verify] (signer + versionCode).
 * Mirrors PC app msms/update.py Receiver. Nothing installs until the user taps [ install ].
 */
class UpdateReceiver(private val dir: File, private val verify: (File) -> ApkInfo) {
    companion object { const val MAX_APK = 200L * 1024 * 1024; const val CHUNK = 1024 * 1024 }
    data class Ready(val file: File, val sha256: String, val size: Long, val info: ApkInfo)
    private var cur: Pair<String, Long>? = null
    @Volatile var ready: Ready? = null; private set
    init { dir.mkdirs() }
    private fun part(sha: String) = File(dir, "$sha.part")
    private fun hexOk(s: String) = s.length == 64 && s.all { it in "0123456789abcdef" }

    @Synchronized fun begin(sha256: String, size: Long): Long {
        val sha = sha256.lowercase()
        if (!hexOk(sha)) throw RpcError("bad_request", "bad sha256")
        if (size <= 0 || size > MAX_APK) throw RpcError("bad_request", "bad size")
        cur?.let { if (it.first != sha) part(it.first).delete() }
        dir.listFiles()?.filter { it.name.endsWith(".part") && it.name != "$sha.part" }?.forEach { it.delete() }
        cur = sha to size
        val f = part(sha); if (f.length() > size) f.delete()
        return f.length()
    }
    @Synchronized fun chunk(sha256: String, offset: Long, data: ByteArray): Long {
        val (sha, size) = cur ?: throw RpcError("bad_request", "update_begin first")
        if (sha != sha256.lowercase()) throw RpcError("bad_request", "update_begin first")
        val f = part(sha); val have = f.length()
        if (offset != have) return have
        if (have + data.size > size || data.size > 2 * CHUNK) throw RpcError("bad_request", "chunk exceeds declared size")
        FileOutputStream(f, true).use { it.write(data) }
        return have + data.size
    }
    @Synchronized fun finish(sha256: String): Ready {
        val (sha, size) = cur ?: throw RpcError("bad_request", "update_begin first")
        if (sha != sha256.lowercase()) throw RpcError("bad_request", "update_begin first")
        val f = part(sha)
        if (f.length() != size) throw RpcError("bad_request", "incomplete")
        if (sha256File(f) != sha) { f.delete(); cur = null; throw RpcError("bad_hash", "sha256 mismatch, discarded") }
        val apk = File(dir, "update.apk"); apk.delete()
        if (!f.renameTo(apk)) throw RpcError("error", "could not store update")
        val info = try { verify(apk) } catch (e: Exception) { apk.delete(); cur = null; throw e }
        cur = null
        return Ready(apk, sha, size, info).also { ready = it }
    }
    @Synchronized fun clear() { ready?.file?.delete(); ready = null }
    @Synchronized fun restore(r: Ready?) { if (r != null && r.file.exists()) ready = r }

    fun sha256File(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
