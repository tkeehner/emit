package com.example.emit.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.emit.mms.Attachment
import com.example.emit.mms.MediaPrep
import com.example.emit.mms.MmsTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion

/** Async, downsampled bitmap from any content Uri (cached per composition). */
@Composable
fun rememberBitmap(uri: Uri, maxDim: Int): Bitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, maxDim) {
        value = withContext(Dispatchers.IO) { runCatching { MediaPrep.decodeSampled(ctx, uri, maxDim) }.getOrNull() }
    }
    return bmp
}

@Composable
fun MediaThumb(uri: Uri, mime: String, size: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).panel(Pal.S2c, Pal.Line2, Pal.R8), contentAlignment = Alignment.Center) {
        if (mime.startsWith("image/")) {
            val b = rememberBitmap(uri, size * 3)
            if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("…", color = DimGreen, fontFamily = Mono)
        } else Text("▶ vid", color = Green, fontFamily = Mono, fontSize = TypeScale.Label)
    }
}

/** Slim translucent top bar for the fullscreen viewers: back, label, save chip; plus an in-app snackbar. */
@Composable
private fun ViewerChrome(visible: Boolean, label: String, onSave: (suspend () -> String)?, onClose: () -> Unit, content: @Composable BoxScope.(topPad: androidx.compose.ui.unit.Dp) -> Unit) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(msg) { if (msg != null) { kotlinx.coroutines.delay(2200); msg = null } }
    val reduce = rememberReduceMotion()
    Box(Modifier.fillMaxSize().background(Black)) {
        val barH = 56.dp
        val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        content(if (visible) barH + inset else 0.dp)
        androidx.compose.animation.AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter),
            enter = if (reduce) androidx.compose.animation.EnterTransition.None else androidx.compose.animation.fadeIn(),
            exit = if (reduce) androidx.compose.animation.ExitTransition.None else androidx.compose.animation.fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Color(0xB3000000))) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(barH).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(Ui.Touch).androidSemantics("close").tap { onClose() }, contentAlignment = Alignment.Center) {
                        Text("‹", color = Pal.Body, fontFamily = Mono, fontSize = TypeScale.Title) }
                    Text(label, color = Pal.Body, fontFamily = Mono, fontSize = TypeScale.Label, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                    if (onSave != null) Box(Modifier.heightIn(min = Ui.Touch).androidSemantics("save to gallery").tap(enabled = !saving) {
                        saving = true; scope.launch { msg = onSave(); saving = false } }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(if (saving) "saving…" else "save", color = Green, fontFamily = Mono, fontSize = TypeScale.Label,
                            modifier = Modifier.height(36.dp).panel(Pal.S2c, Pal.OutLine, Pal.R8).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 12.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line2))
            }
        }
        msg?.let { t ->
            Text(t, color = if (t.startsWith("saved")) Pal.Body else Amber, fontFamily = Mono, fontSize = TypeScale.Label,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)
                    .panel(Pal.S2c, if (t.startsWith("saved")) Pal.OutLine else Amber.copy(alpha = 0.6f), Pal.R12)
                    .androidLive().padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

private fun Modifier.androidSemantics(desc: String) = this.then(Modifier.semantics { contentDescription = desc; role = androidx.compose.ui.semantics.Role.Button })
private fun Modifier.androidLive() = this.then(Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })

/** Fullscreen image viewer; single tap toggles the top bar. */
@Composable
fun FullscreenImage(uri: Uri, label: String = "", onSave: (suspend () -> String)? = null, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var scale by remember(uri) { mutableStateOf(1f) }
        var offset by remember(uri) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        var chrome by remember { mutableStateOf(true) }
        val tf = androidx.compose.foundation.gestures.rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
        ViewerChrome(chrome, label, onSave, onClose) { top ->
            val b = rememberBitmap(uri, 4096)
            // Pinch to zoom (1x-6x), drag to pan, double-tap to reset / 2.5x, single tap shows/hides the bar.
            Box(Modifier.fillMaxSize().padding(top = top), contentAlignment = Alignment.Center) {
                if (b != null) Image(b.asImageBitmap(), "image, pinch to zoom", Modifier.fillMaxSize()
                    .pointerInput(uri) { detectTapGestures(onTap = { chrome = !chrome },
                        onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f }) }
                    .transformable(tf)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                else Text("> decrypting image...", color = Green, fontFamily = Mono)
            }
        }
    }
}

/** Selected attachments above the compose bar, each with a remove [x]. */
@Composable
fun AttachmentStrip(items: List<Attachment>, onRemove: (Attachment) -> Unit) {
    if (items.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
        items.forEach { a ->
            Box(Modifier.padding(end = 8.dp)) {
                MediaThumb(a.uri, a.mime, 64)
                Text("[x]", color = Black, fontFamily = Mono, fontSize = TypeScale.Meta,
                    modifier = Modifier.align(Alignment.TopEnd).background(Green, Pal.R8).tap { onRemove(a) }.padding(horizontal = 2.dp))
            }
        }
    }
}

/** Terminal tray: gallery (Photo Picker), camera, recent images strip. */
@Composable
fun AttachTray(onPick: (List<Attachment>) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) onPick(uris.map { Attachment(it, ctx.contentResolver.getType(it) ?: "image/jpeg") })
    }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = camUri; if (ok && u != null) onPick(listOf(Attachment(u, "image/jpeg")))
    }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val recent by produceState(emptyList<Uri>(), granted) {
        if (granted) value = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
                    null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 24")?.use { c ->
                    buildList { while (c.moveToNext()) add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())) }
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxWidth().panel(Pal.S2c, Pal.Line2, Pal.R12).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("> attach", color = Green, fontFamily = Mono, modifier = Modifier.weight(1f))
            Text("[x]", color = DimGreen, fontFamily = Mono, modifier = Modifier.tap { onClose() }.padding(4.dp))
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            TrayButton("[ gallery ]") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            Spacer(Modifier.width(8.dp))
            TrayButton("[ camera ]") {
                val f = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "cam-${System.currentTimeMillis()}.jpg")
                val u = FileProvider.getUriForFile(ctx, MmsTransport.AUTHORITY, f); camUri = u; camera.launch(u)
            }
        }
        if (!granted) Text("> grant photo access for recent images_", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Label,
            modifier = Modifier.tap { perm.launch(Manifest.permission.READ_MEDIA_IMAGES) }.padding(vertical = 8.dp))
        else Row(Modifier.horizontalScroll(rememberScrollState())) {
            recent.forEach { u -> MediaThumb(u, "image/jpeg", 72, Modifier.padding(end = 8.dp).tap { onPick(listOf(Attachment(u, "image/jpeg"))) }) }
        }
    }
}

@Composable
private fun TrayButton(label: String, onClick: () -> Unit) =
    Text(label, color = Green, fontFamily = Mono, modifier = Modifier.border(1.dp, Green, Pal.R8).tap { onClick() }.padding(12.dp))

/** Fullscreen video player (in-app VideoView, reads the MMS part directly). */
@Composable
fun FullscreenVideo(uri: Uri, label: String = "", onSave: suspend () -> String, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var chrome by remember { mutableStateOf(true) }
        ViewerChrome(chrome, label, onSave, onClose) { top ->
            var err by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().padding(top = top).pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }) }, contentAlignment = Alignment.Center) {
                androidx.compose.ui.viewinterop.AndroidView(factory = { c ->
                    android.widget.VideoView(c).apply {
                        val mc = android.widget.MediaController(c); mc.setAnchorView(this); setMediaController(mc)
                        setOnPreparedListener { it.isLooping = false; start() }
                        setOnErrorListener { _, w, x -> android.util.Log.w("MatrixSMS", "video: error $w/$x"); err = true; true }
                        setVideoURI(uri)
                    }
                }, modifier = Modifier.fillMaxWidth())
                if (err) Text("> can't play this video_", color = Amber, fontFamily = Mono)
            }
        }
    }
}
