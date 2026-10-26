package com.example.emit.ui
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emit.data.Conversation
import com.example.emit.data.Message
import com.example.emit.drafts.PrimeDraft
import com.example.emit.mms.Attachment
import java.text.SimpleDateFormat
import java.util.*

/** Pinned row at the very top of the inbox. Not a real thread: never in multi-select, no delete/archive. */
@Composable
fun PrimeInboxRow(onOpen: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val msgs by com.example.emit.prime.PrimeChat.messages.collectAsState()
    val unread by com.example.emit.prime.PrimeChat.unread.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.prime.PrimeChat.load(ctx) } }
    val last = msgs.lastOrNull()
    val thinking = com.example.emit.prime.PrimeChat.thinking(msgs)
    val snippet = when { last == null -> "talk to me directly - not SMS"; thinking -> "thinking…"; else -> last.text }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
        .panel(Pal.PrimeBg, Pal.Line2, Pal.R12)
        .semantics(mergeDescendants = true) { contentDescription = "Prime assistant chat, pinned" + if (unread) ", new reply" else "" }
        .tap { onOpen() }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).border(1.dp, Green, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Text("P>", color = Green, fontFamily = DisplayFont, fontSize = TypeScale.Label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📌 ", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Body)
                Text("Prime", color = Green, fontFamily = DisplayFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
            }
            Text(snippet, color = if (unread) Pal.Unread else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (last != null) Text(stampPrime(last.ts), color = if (unread) Pal.Cyan else FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1)
            if (unread) Box(Modifier.padding(top = 6.dp).size(8.dp).background(Pal.Cyan, androidx.compose.foundation.shape.CircleShape))
        }
    }
}

private fun stampPrime(t: Long): String {
    val now = System.currentTimeMillis()
    val f = if (now - t < 20 * 3600_000L) "h:mm a" else "MMM d"
    return java.text.SimpleDateFormat(f, java.util.Locale.US).format(Date(t))
}

@Composable
fun PrimeScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val msgs by com.example.emit.prime.PrimeChat.messages.collectAsState()
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val kbCtl = LocalKb.current
    DisposableEffect(Unit) { com.example.emit.prime.PrimeChat.screenOpen = true; onDispose { com.example.emit.prime.PrimeChat.screenOpen = false } }
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.prime.PrimeChat.load(ctx) } }
    LaunchedEffect(msgs.size) { com.example.emit.prime.PrimeChat.markRead(ctx); if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(450); tick++ } }
    val thinking = com.example.emit.prime.PrimeChat.thinking(msgs)
    Column(Modifier.fillMaxSize()) {
        Header(LocalTheme.current.chrome.prime, onBack, extraChip = "NOT SMS")
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp).pointerInput(Unit) { detectTapGestures { kbCtl.hide() } }, state = listState) {
            if (msgs.isEmpty()) item(key = "empty") {
                EmptyState("> prime online_", "type below to talk to Prime directly. goes over your PC link, never SMS.") }
            items(msgs, key = { it.id }) { m ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (m.outgoing) Alignment.End else Alignment.Start) {
                    Text(m.text, color = if (m.outgoing) Pal.Body else Green, fontFamily = UiFont, fontSize = TypeScale.Body,
                        modifier = Modifier.padding(start = if (m.outgoing) Ui.OutgoingIndent else 0.dp, end = if (m.outgoing) 0.dp else Ui.OutgoingIndent)
                            .panel(if (m.outgoing) Pal.S2c else Pal.PrimeBg, if (m.outgoing) Pal.Line2 else FaintGreen)
                            .combinedClickableCopy(ctx, m.text).padding(horizontal = 12.dp, vertical = 9.dp))
                    val meta = stampPrime(m.ts) + if (m.outgoing) when (m.status) { "queued" -> "  · waiting for PC link"; "sent" -> "  · sent"; "delivered" -> "  · ✓"; else -> "" } else "  · prime"
                    Text(meta, color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp))
                }
            }
            if (thinking) item(key = "thinking") {
                Text("prime is thinking" + ".".repeat(tick % 4), color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label,
                    modifier = Modifier.padding(vertical = 6.dp).semantics { contentDescription = "Prime is thinking" })
            }
        }
        CompositionLocalProvider(LocalAccent provides ChatPalette.DEFAULT) {
            ComposeBar(text, { text = it }) {
                val t = text.trim(); if (t.isEmpty()) return@ComposeBar
                text = ""
                scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.prime.PrimeChat.send(ctx, t) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCopy(ctx: android.content.Context, text: String): Modifier =
    this.combinedClickable(onClick = {}, onLongClick = { Clip.copy(ctx, text) })
