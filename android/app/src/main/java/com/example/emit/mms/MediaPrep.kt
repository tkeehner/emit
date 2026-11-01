package com.example.emit.mms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** Picked item before sending. */
data class Attachment(val uri: Uri, val mime: String)

/** Prepares attachments under the carrier MMS size limit (Verizon ~1.2MB; we target 1.1MB total). */
object MediaPrep {
    const val TOTAL_BUDGET = 1_100_000

    /** Pure: per-image byte budget. */
    fun perItemBudget(count: Int, textBytes: Int): Int = if (count <= 0) 0 else ((TOTAL_BUDGET - textBytes - 2_000) / count).coerceAtLeast(40_000)

    fun prepare(context: Context, items: List<Attachment>, textBytes: Int): Result<List<PduPart>> = runCatching {
        val budget = perItemBudget(items.size, textBytes)
        items.mapIndexed { i, a ->
            when {
                a.mime.startsWith("image/gif") -> { val b = readAll(context, a.uri); require(b.size <= budget) { "GIF too large for MMS" }; PduPart("image/gif", b, "image$i.gif") }
                a.mime.startsWith("image/") -> PduPart("image/jpeg", compressImage(context, a.uri, budget), "image$i.jpg")
                a.mime.startsWith("video/") -> {
                    val size = sizeOf(context, a.uri)
                    require(size in 1..budget) { "video is ${size / 1024}KB; MMS limit is ~${budget / 1024}KB (trim it first)" }
                    PduPart(a.mime, readAll(context, a.uri), "video$i." + (if (a.mime.contains("3gp")) "3gp" else "mp4"))
                }
                else -> error("unsupported type ${a.mime}")
            }
        }
    }

    private fun sizeOf(context: Context, uri: Uri): Long = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L

    private fun readAll(context: Context, uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    fun decodeSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= maxDim || o.outHeight / (s * 2) >= maxDim) s *= 2
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
    }

    private fun compressImage(context: Context, uri: Uri, budget: Int): ByteArray {
        var bmp = decodeSampled(context, uri, 1600) ?: error("can't read image")
        val maxSide = 1600
        if (maxOf(bmp.width, bmp.height) > maxSide) {
            val k = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        while (true) {
            for (q in intArrayOf(85, 70, 55, 40)) {
                val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= budget) return out.toByteArray()
            }
            require(bmp.width > 200) { "image can't be compressed enough" }
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.75f).toInt(), (bmp.height * 0.75f).toInt(), true)
        }
    }
}
