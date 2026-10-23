package com.example.emit.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Over-the-link app updates: receive (via LinkRpc), verify signer + version, and install only on the user's tap. */
object AppUpdater {
    data class Pending(val versionName: String, val versionCode: Long, val size: Long, val sha256: String)
    val pending = MutableStateFlow<Pending?>(null)
    val status = MutableStateFlow<String?>(null)
    @Volatile private var recv: UpdateReceiver? = null

    fun receiver(c: Context): UpdateReceiver = recv ?: synchronized(this) {
        recv ?: UpdateReceiver(File(c.filesDir, "updates")) { verify(c, it) }.also { r ->
            recv = r; restore(c, r) }
    }

    /** SHA-256 of the CURRENT signing certificate(s) (key rotation history ignored on both sides). */
    private fun sigs(pi: PackageInfo?): Set<String> {
        val si = pi?.signingInfo ?: return emptySet()
        val certs = if (si.hasMultipleSigners()) si.apkContentsSigners.toList() else listOfNotNull(si.signingCertificateHistory?.lastOrNull())
        return certs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    fun verify(c: Context, apk: File): ApkInfo {
        val pm = c.packageManager
        val pi = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val info = pi?.let { ApkInfo(it.packageName, it.longVersionCode, it.versionName ?: "?", sigs(it)) }
        val me = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        UpdateRules.check(info, c.packageName, me.longVersionCode, sigs(me))
        return info!!
    }

    fun onReady(c: Context, r: UpdateReceiver.Ready) {
        val p = Pending(r.info.versionName, r.info.versionCode, r.size, r.sha256); pending.value = p; status.value = null
        c.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().putString("ready", JSONObject().put("name", p.versionName)
            .put("code", p.versionCode).put("size", p.size).put("sha", p.sha256).toString()).apply()
    }
    private fun restore(c: Context, r: UpdateReceiver) {
        val s = c.getSharedPreferences("app_update", Context.MODE_PRIVATE).getString("ready", null) ?: return
        val j = JSONObject(s); val f = File(c.filesDir, "updates/update.apk")
        val me = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)
        if (!f.exists() || j.getLong("code") <= me) { clear(c); return }
        val info = ApkInfo(c.packageName, j.getLong("code"), j.getString("name"), emptySet())
        r.restore(UpdateReceiver.Ready(f, j.getString("sha"), j.getLong("size"), info))
        pending.value = Pending(info.versionName, info.versionCode, j.getLong("size"), j.getString("sha"))
    }
    fun load(c: Context) { receiver(c) }
    fun clear(c: Context) {
        recv?.clear(); File(c.filesDir, "updates/update.apk").delete(); pending.value = null
        c.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().remove("ready").apply()
    }

    /** Called ONLY from the [ install ] tap. Re-hashes and re-verifies the file right before handing it to the system installer. */
    fun install(c: Context): String {
        val r = receiver(c).ready ?: return "no update waiting"
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "allow 'Install unknown apps' for Emit, then tap [ install ] again"
        }
        if (receiver(c).sha256File(r.file) != r.sha256) { clear(c); return "file changed on disk; discarded" }
        try { verify(c, r.file) } catch (e: Exception) { clear(c); return "rejected: ${e.message}" }
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(c.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, r.file.length()).use { out -> r.file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val pIntent = PendingIntent.getBroadcast(c, 41, Intent(c, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pIntent.intentSender)
        }
        status.value = "installing ${r.info.versionName}... confirm on the system prompt"
        return status.value!!
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { c.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> { AppUpdater.clear(c); AppUpdater.status.value = "updated" }
            else -> { AppUpdater.status.value = "install failed ($st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                Log.w("MatrixSMS", "update: install failed $st") }
        }
    }
}
