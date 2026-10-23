package com.example.emit

import android.app.Application
import android.content.Context
import java.io.File

/** On-device crash log (stack traces + app/OS version only; never message text). Viewable/exportable in About. */
object CrashLog {
    const val MAX_BYTES = 256 * 1024
    fun file(c: Context) = File(c.filesDir, "logs/crash.log")
    /** Pure: one crash entry. */
    fun entry(time: String, version: String, sdk: Int, thread: String, trace: String) =
        "=== $time  v$version  sdk$sdk  thread=$thread\n${trace.trimEnd()}\n\n"
    /** Pure: keep the newest [max] bytes, cut at an entry boundary. */
    fun trim(text: String, max: Int = MAX_BYTES): String {
        if (text.length <= max) return text
        val tail = text.takeLast(max); val i = tail.indexOf("=== ")
        return if (i > 0) tail.substring(i) else tail
    }
    fun append(c: Context, t: Thread, e: Throwable) = runCatching {
        val f = file(c); f.parentFile?.mkdirs()
        val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).format(java.util.Date())
        f.writeText(trim((if (f.exists()) f.readText() else "") + entry(time, BuildConfig.VERSION_NAME, android.os.Build.VERSION.SDK_INT, t.name, sw.toString())))
    }
    fun read(c: Context): String = runCatching { file(c).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    fun clear(c: Context) { runCatching { file(c).writeText("") } }
    fun install(c: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> append(c.applicationContext, t, e); prev?.uncaughtException(t, e) }
    }
}

class MatrixApp : Application() {
    override fun onCreate() { super.onCreate(); CrashLog.install(this) }
}
