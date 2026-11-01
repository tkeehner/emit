package com.example.emit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.emit.data.Message
import com.example.emit.mms.MmsTransport
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Pure helpers for copy / save / paste (unit-tested). */
object MessageText {
    private val full = SimpleDateFormat("MMM d HH:mm", Locale.US)

    /** Text copied for one message: just the body (or a media marker when there is no text). */
    fun single(m: Message): String = com.example.emit.data.MessageMeta.messagePart(m.body).ifBlank { if (m.media.isNotEmpty()) "[media]" else "" }

    /** Several messages, oldest first, as "[MMM d HH:mm] who: body" lines. */
    fun multi(ms: List<Message>, who: (Message) -> String, tz: TimeZone = TimeZone.getDefault()): String {
        val f = (full.clone() as SimpleDateFormat).apply { timeZone = tz }
        return ms.sortedWith(compareBy({ it.date }, { it.id })).joinToString("\n") { m ->
            "[${f.format(Date(m.date))}] ${who(m)}: ${single(m)}"
        }
    }

    /** Gallery folder + file extension for a received part. Null = not an image/video. */
    fun saveTarget(mime: String): Pair<String, String>? {
        val m = mime.lowercase()
        val ext = when {
            m == "image/jpeg" || m == "image/jpg" -> "jpg"
            m == "image/png" -> "png"
            m == "image/gif" -> "gif"
            m == "image/webp" -> "webp"
            m == "image/heic" || m == "image/heif" -> "heic"
            m.startsWith("image/") -> "jpg"
            m == "video/3gpp" || m == "video/3gp" -> "3gp"
            m == "video/webm" -> "webm"
            m.startsWith("video/") -> "mp4"
            else -> return null
        }
        return (if (m.startsWith("image/")) "Pictures/MatrixSMS" else "Movies/MatrixSMS") to ext
    }

    /** What a clipboard item should become: text to insert, a media attachment, or nothing. */
    sealed interface Paste { data class Text(val text: String) : Paste; data class Media(val uri: Uri, val mime: String) : Paste; object Nothing : Paste }

    fun classify(text: CharSequence?, uri: Uri?, mime: String?): Paste = when {
        uri != null && mime != null && (mime.startsWith("image/") || mime.startsWith("video/")) -> Paste.Media(uri, mime)
        !text.isNullOrEmpty() -> Paste.Text(text.toString())
        else -> Paste.Nothing
    }
}

object Clip {
    fun copy(ctx: Context, text: String, label: String = "emit") {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 shows its own clipboard confirmation; toast only on older versions.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "> copied", Toast.LENGTH_SHORT).show()
    }

    fun hasContent(ctx: Context): Boolean = runCatching { ctx.getSystemService(ClipboardManager::class.java).hasPrimaryClip() }.getOrDefault(false)

    /** Reads the primary clip. Media is copied into our cache so it survives until the MMS is sent. */
    fun read(ctx: Context): MessageText.Paste {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return MessageText.Paste.Nothing
        if (clip.itemCount == 0) return MessageText.Paste.Nothing
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = uri?.let { runCatching { ctx.contentResolver.getType(it) }.getOrNull() ?: clip.description.takeIf { d -> d.mimeTypeCount > 0 }?.getMimeType(0) }
        return when (val p = MessageText.classify(item.text ?: if (uri == null) item.coerceToText(ctx) else null, uri, mime)) {
            is MessageText.Paste.Media -> runCatching {
                val ext = MessageText.saveTarget(p.mime)?.second ?: "bin"
                val f = File(File(ctx.cacheDir, "paste").apply { mkdirs() }, "paste-${System.currentTimeMillis()}.$ext")
                ctx.contentResolver.openInputStream(p.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                MessageText.Paste.Media(FileProvider.getUriForFile(ctx, MmsTransport.AUTHORITY, f), p.mime)
            }.getOrElse { android.util.Log.w("MatrixSMS", "paste: media copy failed", it); item.coerceToText(ctx)?.toString()?.takeIf { s -> s.isNotEmpty() }?.let { MessageText.Paste.Text(it) } ?: MessageText.Paste.Nothing }
            else -> p
        }
    }
}

object MediaSaver {
    /** Copies a received MMS part into the shared gallery via MediaStore (no permission needed on Android 10+). */
    fun save(ctx: Context, src: Uri, mime: String): Result<Uri> = runCatching {
        val (dir, ext) = requireNotNull(MessageText.saveTarget(mime)) { "not an image or video" }
        val video = mime.startsWith("video/")
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                   else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "MatrixSMS_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "." + ext
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val out = requireNotNull(r.insert(coll, cv)) { "gallery insert failed" }
        try {
            r.openInputStream(src)!!.use { i -> r.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
            r.update(out, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            android.util.Log.i("MatrixSMS", "media: saved $mime to $dir/$name")
            out
        } catch (t: Throwable) { r.delete(out, null, null); throw t }
    }

    /** Saves and returns a short status line for in-app display. */
    suspend fun saveResult(ctx: Context, src: Uri, mime: String): String {
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(ctx, src, mime) }
        val dir = MessageText.saveTarget(mime)?.first ?: ""
        return res.fold({ "saved to $dir" }, { "save failed: ${it.message}" })
    }

    suspend fun saveWithToast(ctx: Context, src: Uri, mime: String) {
        val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(ctx, src, mime) }
        val dir = MessageText.saveTarget(mime)?.first ?: ""
        Toast.makeText(ctx, res.fold({ "> saved to $dir" }, { "> save failed: ${it.message}" }), Toast.LENGTH_SHORT).show()
    }
}

