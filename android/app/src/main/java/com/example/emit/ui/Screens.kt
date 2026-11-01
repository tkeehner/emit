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

private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)
private val dayFmt = SimpleDateFormat("MMM d", Locale.US)
private fun stamp(t: Long): String =
    if (System.currentTimeMillis() - t < 86_400_000) timeFmt.format(Date(t)) else dayFmt.format(Date(t))


@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, chips: Boolean = true, extraChip: String? = null, trailing: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null, titleColor: Color? = null) {
    val crt = LocalCrt.current
    val theme = LocalTheme.current
    val glow = crt || theme.titleGlow
    val display = theme.displayFont == FontId.OXANIUM
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 4.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().panel(line = if (theme.titleGlow) Pal.Primary.copy(alpha = 0.4f) else Pal.Line).visrBrackets(0.8f).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) Text("‹ ", color = Green, fontFamily = DisplayFont, fontSize = TypeScale.Title,
                modifier = Modifier.semantics { contentDescription = "back" }.tap { onBack() })
            leading?.invoke()
            val tc = titleColor ?: Green
            Text(title, style = if (glow) TextStyle(shadow = androidx.compose.ui.graphics.Shadow(tc, blurRadius = 12f)) else TextStyle.Default, color = tc, fontFamily = DisplayFont,
                fontSize = if (display) TypeScale.Title else TypeScale.Body, letterSpacing = if (display) 1.2.sp else 0.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).heightIn(min = Ui.Touch).wrapContentHeight(Alignment.CenterVertically))
            trailing?.invoke()
        }
        if (chips) {
            val relay by com.example.emit.link.LinkState.relay.collectAsState()
            val lan by com.example.emit.link.LinkState.lanSessions.collectAsState()
            val line = ChatLogic.statusLine(relay, lan)   // fixed-width text kept for screen readers / tests
            val sync = line.substringAfter("SYNC: ").dropLast(1)
            Row(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {
                contentDescription = "encrypted; sync " + sync.replace("●", "connected").replace("◐", "waiting").replace("○", "off") },
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("E2E", on = true)
                Chip(sync.substringBefore(' '), on = !sync.endsWith("○"), color = if (sync.endsWith("◐")) Amber else Pal.Cyan)
                extraChip?.let { Chip(it) }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun InboxScreen(
    convos: List<Conversation>, draftsCount: Int, colors: Map<Long, String>,
    onOpen: (Conversation) -> Unit, onSetColor: (Long, String?) -> Unit,
    deleting: Set<Long> = emptySet(), notice: String? = null, onDelete: (Conversation) -> Unit = {},
    onRequestRole: (() -> Unit)? = null,
    onRename: (Long, String?) -> Unit = { _, _ -> },
    onDeleteMany: (List<Long>) -> Unit = {},
    onOpenLink: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenPrime: () -> Unit = {},
) {
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val prefCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.data.ChatPrefs.load(prefCtx) } }
    val flags by com.example.emit.data.ChatPrefs.flags.collectAsState()
    var archivedView by remember { mutableStateOf(false) }
    val shown = remember(convos, flags, archivedView) { com.example.emit.data.ChatPrefs.arrange(convos, flags, archivedView) }
    val archivedCount = remember(convos, flags) { convos.count { it.threadId in flags.archived } }
    var confirmMany by remember { mutableStateOf(false) }
    LaunchedEffect(convos) { selected = BulkSelect.prune(selected, convos.map { it.threadId }) }
    androidx.activity.compose.BackHandler(enabled = selecting) { if (confirmMany) confirmMany = false else { selecting = false; selected = emptySet() } }
    var confirm by remember { mutableStateOf<Conversation?>(null) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var picking by remember { mutableStateOf<Conversation?>(null) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize().background(Black)) {
        CodeRain(alpha = rainAlpha())
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                OverlayOpen()
                val ids = convos.map { it.threadId }
                val all = ids.isNotEmpty() && selected.containsAll(ids)
                fun allIn(set: Set<Long>) = selected.isNotEmpty() && set.containsAll(selected)
                SelectionHeader(selected.size, onCancel = { selecting = false; selected = emptySet() }) {
                    SelAction(if (all) "none" else "all", if (all) "select none" else "select all") { selected = if (all) emptySet() else BulkSelect.selectAll(selected, ids) }
                    SelAction("📌", if (allIn(flags.pinned)) "unpin" else "pin", enabled = selected.isNotEmpty(), on = allIn(flags.pinned)) { com.example.emit.data.ChatPrefs.toggleAll(prefCtx, "pinned", selected) }
                    SelAction("🔕", if (allIn(flags.muted)) "unmute" else "mute", enabled = selected.isNotEmpty(), on = allIn(flags.muted)) { com.example.emit.data.ChatPrefs.toggleAll(prefCtx, "muted", selected) }
                    SelAction("🗄", if (allIn(flags.archived)) "unarchive" else "archive", enabled = selected.isNotEmpty(), on = allIn(flags.archived)) {
                        com.example.emit.data.ChatPrefs.toggleAll(prefCtx, "archived", selected); selecting = false; selected = emptySet() }
                    SelAction("🗑", "delete", enabled = selected.isNotEmpty(), danger = true) { confirmMany = true }
                }
            } else {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val running by com.example.emit.link.LinkState.running.collectAsState()
                val pcs by com.example.emit.link.LinkState.connected.collectAsState()
                val linkOn = remember(running) { running || com.example.emit.link.LinkStore(ctx).enabled }
                val upd by com.example.emit.link.AppUpdater.pending.collectAsState()
                var showUpd by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { com.example.emit.link.AppUpdater.load(ctx) }
                Header(LocalTheme.current.chrome.inbox, chips = false, trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PcButton(PcStatus.of(linkOn, pcs.size), onOpenLink)
                        UpdateButton(upd != null) { showUpd = true }
                        Box(Modifier.size(Ui.Touch).semantics { contentDescription = "settings" }.tap { onOpenSettings() }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(30.dp).panel(Pal.S2c, Pal.Line2, Pal.R8), contentAlignment = Alignment.Center) { Text("⚙", color = DimGreen, fontSize = TypeScale.Label) } }
                    }
                })
                if (showUpd) UpdateDialog(onClose = { showUpd = false })
            }
            if (draftsCount > 0) Text("[$draftsCount PRIME DRAFT(S) AWAITING APPROVAL]", color = Amber, fontFamily = UiFont,
                fontSize = TypeScale.Meta, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth()
                    .panel(Pal.WarnBg, Pal.WarnLine).padding(horizontal = 12.dp, vertical = 10.dp))
            if (archivedView) Text("< inbox   // archived chats (hidden, not deleted)", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Label,
                modifier = Modifier.fillMaxWidth().tap { archivedView = false }.padding(horizontal = Ui.ScreenPad))
            if (shown.isEmpty()) { if (archivedView) EmptyState("> nothing archived_", "long-press a chat and pick [ archive ]")
                else EmptyState("> no messages found_", "tap [+ NEW] to start a conversation, or make Emit the default app") }
            val visrSweep = rememberVisrSweep(archivedView)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                // Prime is pinned above everything and is not a thread: hidden while multi-selecting, so it can't be bulk-deleted.
                if (!archivedView && !selecting) item(key = "prime-chat") { PrimeInboxRow(onOpenPrime) }
                if (!archivedView && archivedCount > 0 && !selecting) item(key = "archived-link") {
                    Text("[ archived ($archivedCount) ]", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label,
                        modifier = Modifier.fillMaxWidth().tap { archivedView = true }.padding(horizontal = Ui.ScreenPad))
                }
                itemsIndexed(shown, key = { _, it -> it.threadId }) { idx, c ->
                  val pinnedRow = c.threadId in flags.pinned
                  val prevSame = shown.getOrNull(idx - 1)?.let { (it.threadId in flags.pinned) == pinnedRow } == true
                  val nextSame = shown.getOrNull(idx + 1)?.let { (it.threadId in flags.pinned) == pinnedRow } == true
                  if (!prevSame && !archivedView && flags.pinned.isNotEmpty()) Text(if (pinnedRow) "PINNED" else "ALL", color = FaintGreen, fontFamily = UiFont,
                      fontSize = TypeScale.Micro, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
                      modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp).background(Pal.Bg.copy(alpha = Pal.SURFACE_A), Pal.R8).padding(horizontal = 6.dp, vertical = 2.dp))
                  val rowShape = groupedShape(!prevSame, !nextSame)
                  CompositionLocalProvider(LocalAccent provides ChatPalette.byId(colors[c.threadId])) {
                    val gone = c.threadId in deleting
                    val flicker = glitchEnabled()
                    var gt by remember(gone) { mutableIntStateOf(0) }
                    LaunchedEffect(gone, flicker) { while (gone && flicker) { delay(40); gt++ } }
                    val isSel = selecting && c.threadId in selected
                    val rowLine = Pal.RowLine
                    val fixed = fixedDefaultChat()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).visrBrackets(visrSweep * (if (idx < 12) ((visrSweep * 12f - idx).coerceIn(0f, 1f)) else 1f)).background(if (isSel) Pal.SelBg else Color(if (fixed) LocalTheme.current.palette.s1 else ChatTint.s1).copy(alpha = Pal.ROW_A), rowShape)
                        .then(if (prevSame) Modifier.drawBehind { drawLine(rowLine, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) } else Modifier)
                        .graphicsLayer {
                            if (gone && flicker) { gt; translationX = (Random.nextFloat() - 0.5f) * 60f; alpha = 0.25f + Random.nextFloat() * 0.5f; scaleY = 0.6f + Random.nextFloat() * 0.4f }
                        }.combinedClickable(enabled = !gone,
                            onClick = { if (selecting) selected = BulkSelect.toggle(selected, c.threadId) else onOpen(c) },
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selecting) selected = BulkSelect.toggle(selected, c.threadId) else picking = c },
                        ).padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selecting) Box(Modifier.semantics { role = Role.Checkbox; stateDescription = if (isSel) "selected" else "not selected" }.padding(end = 10.dp).size(20.dp).background(if (isSel) Pal.Primary else Color.Transparent, LocalTheme.current.shape(6.dp))
                            .border(1.dp, if (isSel) Pal.Primary else Pal.Line2, LocalTheme.current.shape(6.dp)), contentAlignment = Alignment.Center) {
                            if (isSel) Text("✓", color = Pal.OnPrimary, fontSize = TypeScale.Meta) }
                        
                        GlyphIcon(c.glyphKey, size = 38, group = c.isGroup, label = c.displayName, hot = c.unread)
                        // 0.1.47: every name is highlighted in its chat's chosen color, like Prime's (default chats: theme primary).
                        val nameColor = Color(NameColor.argb(LocalAccent.current, LocalTheme.current.palette.primary,
                            if (isSel) LocalTheme.current.palette.selBg else if (fixed) LocalTheme.current.palette.s1 else ChatTint.s1))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                val badge = com.example.emit.data.ChatPrefs.badges(c.threadId, flags)
                                if (badge.isNotEmpty()) Text("$badge ", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Body, maxLines = 1,
                                    modifier = Modifier.semantics { contentDescription = listOfNotNull(if (c.threadId in flags.pinned) "pinned" else null, if (c.threadId in flags.muted) "muted" else null).joinToString() })
                                Text(if (LocalTheme.current.chrome.uppercaseNames) c.displayName.uppercase() else c.displayName, color = nameColor, fontFamily = DisplayFont,
                                    fontWeight = if (c.unread) androidx.compose.ui.text.font.FontWeight.Medium else null,
                                    fontSize = TypeScale.Body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(c.snippet, color = if (c.unread) Pal.Unread else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(stamp(c.date), color = if (c.unread) Pal.Cyan else FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1)
                            if (c.unread) Box(Modifier.padding(top = 6.dp).size(8.dp).background(Pal.Cyan, androidx.compose.foundation.shape.CircleShape)
                                .semantics { contentDescription = "unread" })
                        }
                    }
                  }
                }
            }
        }
        picking?.let { c ->
            ColorSheet(c.displayName, colors[c.threadId], onDismiss = { picking = null },
                subtitle = c.addresses.joinToString(", ") { com.example.emit.data.DisplayName.phone(it) },
                flags = listOf(
                    SheetFlag("pin", "📌", c.threadId in flags.pinned) { com.example.emit.data.ChatPrefs.toggle(prefCtx, "pinned", c.threadId) },
                    SheetFlag("mute", "🔕", c.threadId in flags.muted) { com.example.emit.data.ChatPrefs.toggle(prefCtx, "muted", c.threadId) },
                    SheetFlag("archive", "🗄", c.threadId in flags.archived) { com.example.emit.data.ChatPrefs.toggle(prefCtx, "archived", c.threadId) }),
                onDelete = { picking = null; confirm = c },
                onSelectMultiple = { picking = null; selecting = true; selected = setOf(c.threadId) },
                onRename = if (c.isGroup) ({ picking = null; renaming = c }) else null) { id -> onSetColor(c.threadId, id); picking = null }
        }
        renaming?.let { c -> RenameDialog(c.label.orEmpty(), onCancel = { renaming = null }) { n -> renaming = null; onRename(c.threadId, n) } }
        confirm?.let { c -> DeleteConfirm(c, onCancel = { confirm = null }) { confirm = null; onDelete(c) } }
        if (confirmMany) BulkDeleteConfirm(selected.size, onCancel = { confirmMany = false }) {
            val ids = convos.map { it.threadId }.filter { it in selected }
            confirmMany = false; selecting = false; selected = emptySet(); onDeleteMany(ids)
        }
        if (notice != null) Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp).fillMaxWidth().padding(horizontal = 12.dp)
            .panel(Pal.S2c, Amber.copy(alpha = 0.6f), Pal.R12).padding(12.dp)) {
            Text(notice, color = Amber, fontFamily = UiFont, fontSize = TypeScale.Label)
            if (onRequestRole != null) Text("[ make default ]", color = Black, fontFamily = UiFont,
                modifier = Modifier.padding(top = 8.dp).background(Amber, Pal.R8).tap { onRequestRole() }.padding(8.dp))
        }
    }
}

/** Terminal-style bottom sheet: '> set color'. [current] null = default. Choosing null resets. */
/** Replaces the screen header while multi-selecting: × cancel, "N selected", compact action chips. */
@Composable
fun SelectionHeader(count: Int, onCancel: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(top = 4.dp, bottom = 6.dp).panel(Pal.S1c, Pal.OutLine, Pal.R10).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Ui.Touch).semantics { contentDescription = "cancel selection" }.tap { onCancel() }, contentAlignment = Alignment.Center) {
            Text("×", color = Pal.Body, fontFamily = UiFont, fontSize = TypeScale.Title) }
        Text("$count selected", color = Pal.Body, fontFamily = UiFont, fontSize = TypeScale.Body, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SelAction(label: String, desc: String, enabled: Boolean = true, on: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val c = when { !enabled -> FaintGreen; danger -> DangerRed; on -> Pal.Primary; else -> Green }
    Box(Modifier.heightIn(min = Ui.Touch).widthIn(min = 40.dp).semantics { contentDescription = desc; role = Role.Button }.tap(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center) {
        Text(label, color = c, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1, softWrap = false,
            modifier = Modifier.height(34.dp).panel(if (on) Pal.OutBg else Pal.S2c, when { danger && enabled -> DangerRed.copy(alpha = 0.6f); on -> Pal.Primary.copy(alpha = 0.5f); else -> Pal.Line2 }, Pal.R8)
                .wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 9.dp))
    }
}

/** Counts open sheets/dialogs so the inbox FAB can hide while any is up. */
object Overlay { var open by androidx.compose.runtime.mutableIntStateOf(0) }
@Composable
fun OverlayOpen() = androidx.compose.runtime.DisposableEffect(Unit) { Overlay.open++; onDispose { Overlay.open-- } }

data class SheetFlag(val label: String, val icon: String, val on: Boolean, val toggle: () -> Unit)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ColorSheet(name: String, current: String?, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onRename: (() -> Unit)? = null,
               onSelectMultiple: (() -> Unit)? = null, subtitle: String? = null, flags: List<SheetFlag> = emptyList(), onPick: (String?) -> Unit) {
    OverlayOpen()
    androidx.activity.compose.BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().background(Pal.Scrim).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() }) {
        var drag by remember { mutableStateOf(0f) }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 640.dp).graphicsLayer { translationY = drag.coerceAtLeast(0f) }
            .padding(horizontal = 8.dp).padding(top = 8.dp).navigationBarsPadding().padding(bottom = 8.dp)
            .shadow(16.dp, Pal.R12).panel(Pal.S2c, Pal.Line2, Pal.R12).clickable(enabled = false) {}) {
            // drag handle: swipe down to dismiss
            Box(Modifier.fillMaxWidth().height(28.dp).semantics { contentDescription = "drag down to close" }
                .pointerInput(Unit) { detectVerticalDragGestures(
                    onDragEnd = { if (drag > 120f) onDismiss(); drag = 0f }, onDragCancel = { drag = 0f }) { _, dy: Float -> drag += dy } },
                contentAlignment = Alignment.Center) { Box(Modifier.size(36.dp, 4.dp).background(Pal.Line2, androidx.compose.foundation.shape.CircleShape)) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                Text("chat options", color = Pal.Body, fontFamily = UiFont, fontSize = TypeScale.Title, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(name + (subtitle?.takeIf { it.isNotBlank() && it != name }?.let { " · $it" } ?: ""), color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (flags.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    flags.forEach { f ->
                        Column(Modifier.weight(1f).then(if (f.on) Modifier.shadow(10.dp, Pal.R10, ambientColor = Pal.Primary, spotColor = Pal.Primary) else Modifier)
                            .panel(if (f.on) Pal.OutBg else Pal.S1c, if (f.on) Pal.Primary.copy(alpha = 0.6f) else Pal.Line, Pal.R10)
                            .semantics(mergeDescendants = true) { role = Role.Switch; stateDescription = if (f.on) "on" else "off" }
                            .tap { f.toggle() }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f.icon, color = if (f.on) Pal.Primary else DimGreen, fontSize = TypeScale.Title)
                            Text(f.label, color = if (f.on) Pal.Primary else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                Text("COLOR", color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp))
                val sel = ChatPalette.byId(current).id
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChatPalette.ALL.forEach { n ->
                        val col = Color(n.argb); val on = n.id == sel
                        Column(Modifier.width(64.dp).semantics(mergeDescendants = true) { role = Role.RadioButton; stateDescription = if (on) "selected" else "not selected" }
                            .tap { onPick(n.id) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(34.dp).border(if (on) 2.dp else 1.dp, if (on) Pal.Body else Pal.Line2, androidx.compose.foundation.shape.CircleShape).padding(4.dp)
                                .background(col, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                                if (on) Text("✓", color = Pal.OnPrimary, fontSize = TypeScale.Label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                            Text(n.label.lowercase() + if (n.id == ChatPalette.DEFAULT.id) "*" else "", color = if (on) Pal.Body else DimGreen, fontFamily = UiFont,
                                fontSize = TypeScale.Micro, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                Text("* default", color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                Text("ACTIONS", color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp))
                @Composable fun MenuRow(label: String, c: Color = Pal.Body, line: Color = Pal.Line, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .panel(Pal.S1c, line, Pal.R10).tap { f() }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = c, fontFamily = UiFont, fontSize = TypeScale.Body, modifier = Modifier.weight(1f)); Text("›", color = c.copy(alpha = 0.7f), fontFamily = UiFont) }
                if (onSelectMultiple != null) MenuRow("select multiple") { onSelectMultiple() }
                if (onRename != null) MenuRow("rename group") { onRename() }
                MenuRow("reset color") { onPick(null) }
                if (onDelete != null) {
                    Text("DANGER ZONE", color = DangerRed.copy(alpha = 0.85f), fontFamily = UiFont, fontSize = TypeScale.Micro, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp))
                    MenuRow("delete conversation", DangerRed, DangerRed.copy(alpha = 0.6f)) { onDelete() }
                }
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).tap { onDismiss() }, contentAlignment = Alignment.Center) {
                    Text("cancel", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label) }
            }
        }
    }
}

val DangerRed: Color @Composable get() = Color(LocalTheme.current.palette.danger)

/** '> name group' input; empty = remove custom name. Stored locally only. */
@Composable
fun RenameDialog(current: String, onCancel: () -> Unit, onSave: (String?) -> Unit) {
    OverlayOpen()
    var name by remember { mutableStateOf(current) }
    Box(Modifier.fillMaxSize().background(Pal.ScrimStrong), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.padding(20.dp).padding(top = 60.dp).fillMaxWidth().panel(Pal.S2c, Pal.Line2, Pal.R12).padding(16.dp)) {
            Text("> name group", color = Green, fontFamily = UiFont, fontSize = TypeScale.Body)
            Spacer(Modifier.height(8.dp))
            TerminalField(name, { name = it.take(40) }, "$ ", "group name", Modifier.fillMaxWidth(), singleLine = true)
            ButtonRow(Btn("[ cancel ]", DimGreen) { onCancel() }, Btn("[ save ]", Green, filled = true) { onSave(name.ifBlank { null }) },
                modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
fun DeleteConfirm(c: Conversation, onCancel: () -> Unit, onConfirm: () -> Unit) {
    OverlayOpen()
    Box(Modifier.fillMaxSize().background(Pal.ScrimStrong).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).fillMaxWidth().panel(Pal.S2c, DangerRed.copy(alpha = 0.6f), Pal.R12).clickable(enabled = false) {}.padding(16.dp)) {
            Text(deletePrompt(c.displayName, c.messageCount), color = DangerRed, fontFamily = UiFont, fontSize = TypeScale.Body)
            ButtonRow(Btn("[ cancel ]", Green) { onCancel() }, Btn("[ delete ]", DangerRed, filled = true) { onConfirm() },
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
fun BulkDeleteConfirm(n: Int, onCancel: () -> Unit, onConfirm: () -> Unit) {
    OverlayOpen()
    Box(Modifier.fillMaxSize().background(Pal.ScrimStrong).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).fillMaxWidth().panel(Pal.S2c, DangerRed.copy(alpha = 0.6f), Pal.R12).clickable(enabled = false) {}.padding(16.dp)) {
            Text(BulkSelect.confirmPrompt(n), color = DangerRed, fontFamily = UiFont, fontSize = TypeScale.Body)
            ButtonRow(Btn("[ cancel ]", Green) { onCancel() }, Btn("[ delete ]", DangerRed, filled = true) { onConfirm() },
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

/** Pure (unit-tested) confirm text. */
fun deletePrompt(name: String, count: Int): String =
    "> rm -rf thread // $name?\nthis permanently deletes all $count message${if (count == 1) "" else "s"} on this phone"

/** Dashed amber outline used ONLY for Prime drafts, so they never look like an amber-colored chat. */
@Composable
fun Modifier.dashedDraftBorder(): Modifier {
    val fill = Pal.Mark
    val line = Amber
    return drawBehind {
        drawRect(fill)
        drawRect(line, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ThreadScreen(
    threadId: Long, title: String, participants: List<String>, canSend: Boolean, notice: String?,
    participantNames: List<String> = participants, groupLabel: String? = null, canApprove: Boolean = canSend,
    messages: List<Message>?, hasMore: Boolean, loadingOlder: Boolean, onNearTop: () -> Unit, drafts: List<PrimeDraft>,
    onBack: () -> Unit, onForward: (String) -> Unit = {}, onSend: (String, List<Attachment>, () -> Unit) -> Unit, onApprove: (PrimeDraft) -> Unit, onReject: (PrimeDraft) -> Unit,
) {
    val draftCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val draftMem = remember { com.example.emit.data.DraftMemory(draftCtx) }
    var text by remember(threadId) { mutableStateOf(draftMem.get(threadId)) }
    LaunchedEffect(threadId, text) { delay(400); draftMem.set(threadId, text) }
    var searching by remember(threadId) { mutableStateOf(false) }
    var query by remember(threadId) { mutableStateOf("") }
    var cur by remember(threadId) { mutableIntStateOf(-1) }
    var scheduling by remember(threadId) { mutableStateOf(false) }
    val scheduled by com.example.emit.sms.Scheduler.items.collectAsState()
    LaunchedEffect(threadId) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.sms.Scheduler.load(draftCtx); UiPrefs.previewsOn(draftCtx) } }
    val previewsOn by UiPrefs.previews.collectAsState()
    var infoFor by remember(threadId) { mutableStateOf<Message?>(null) }
    var deleteFor by remember(threadId) { mutableStateOf<Message?>(null) }
    var attachments by remember(threadId) { mutableStateOf(emptyList<Attachment>()) }
    var tray by remember(threadId) { mutableStateOf(false) }
    var viewing by remember(threadId) { mutableStateOf<com.example.emit.data.MediaRef?>(null) }
    var viewingLabel by remember(threadId) { mutableStateOf("") }
    var actionFor by remember(threadId) { mutableStateOf<Message?>(null) }
    var selectTextOf by remember(threadId) { mutableStateOf<Message?>(null) }
    var picked by remember(threadId) { mutableStateOf<Set<String>>(emptySet()) }
    var txSeq by remember(threadId) { mutableIntStateOf(0) }
    val selecting = picked.isNotEmpty()
    androidx.activity.compose.BackHandler(enabled = selecting) { picked = emptySet() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uiScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    fun saveMedia(md: com.example.emit.data.MediaRef) { uiScope.launch { MediaSaver.saveWithToast(ctx, md.uri, md.mime) } }
    val listState = remember(threadId) { androidx.compose.foundation.lazy.LazyListState() }
    val msgs = messages.orEmpty()
    val hv = androidx.compose.ui.platform.LocalView.current
    val still = rememberReduceMotion()
    val hits = remember(msgs, query) { ThreadSearch.matches(msgs.map { it.body }, query) }
    LaunchedEffect(cur, hits) { hits.getOrNull(cur)?.let { listState.animateScrollToItem((if (hasMore || loadingOlder) 1 else 0) + it) } }
    val botEntries by com.example.emit.bot.BotCommands.entries.collectAsState()
    LaunchedEffect(threadId) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.bot.BotCommands.load(ctx) } }
    val kbCtl = LocalKb.current
    val fixedChat = fixedDefaultChat()
    val header = if (hasMore || loadingOlder) 1 else 0
    // Jump to bottom when the newest message changes (first load / new message), not when older pages prepend.
    LaunchedEffect(threadId, msgs.lastOrNull()?.key, drafts.size) {
        val n = header + msgs.size + drafts.size; if (n > 0) listState.scrollToItem(n - 1)
    }
    val nearTop by remember(threadId) { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }
    LaunchedEffect(threadId, nearTop, msgs.size) { if (nearTop && hasMore && msgs.isNotEmpty()) onNearTop() }
    // Layering: opaque tint background -> rain -> content with NO background (bubbles/bar are 92% panels).
    // (0.1.36 bug: an 80% tint was drawn ABOVE the rain, leaving ~5% of it visible = none.)
    Box(Modifier.fillMaxSize().background(Color(if (fixedChat) LocalTheme.current.palette.bg else ChatTint.bg)).testTag("thread-root")) { CodeRain(alpha = rainAlpha())
    Column(Modifier.fillMaxSize().testTag("thread-content")) {
        val isGroup = participants.size > 1
        val threadTitle = if (isGroup) (groupLabel?.takeIf { it.isNotBlank() } ?: com.example.emit.data.DisplayName.chat(null, participantNames, participants)) else com.example.emit.data.DisplayName.person(title, participants.firstOrNull().orEmpty())
        Header(if (LocalTheme.current.chrome.uppercaseNames) threadTitle.uppercase() else threadTitle, onBack,
            extraChip = (if (isGroup) "MMS · ${participants.size}" else "SMS"),
            titleColor = Color(NameColor.argb(LocalAccent.current, LocalTheme.current.palette.primary, if (fixedChat) LocalTheme.current.palette.bg else ChatTint.bg)),
            leading = if (LocalTheme.current.avatar == AvatarStyle.HOLIDAY) {{
                HolidayBadge(participants.ifEmpty { listOf(title) }.joinToString(","), 30.dp, isGroup); Spacer(Modifier.width(8.dp))
            }} else if (LocalTheme.current.avatar == AvatarStyle.GLYPH) {{
                GlyphBadge(participants.ifEmpty { listOf(title) }, 28.dp); Spacer(Modifier.width(8.dp))
            }} else if (LocalTheme.current.avatar == AvatarStyle.CALLSIGN) {{
                CallsignBadge(threadTitle, 30.dp, hot = true); Spacer(Modifier.width(8.dp))
            }} else null,
            trailing = {
            Text("[/]", color = if (searching) Amber else Green, fontFamily = UiFont, fontSize = TypeScale.Body,
                modifier = Modifier.semantics { contentDescription = "search in chat" }.tap { searching = !searching; if (!searching) { query = ""; cur = -1 } }.padding(horizontal = 8.dp))
        })
        if (searching) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TerminalField(query, { query = it; cur = -1 }, "/ ", "search this chat", Modifier.weight(1f), singleLine = true)
            Text(if (hits.isEmpty()) (if (query.isBlank()) "" else " 0/0") else " ${cur + 1}/${hits.size}", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1)
            Text("[↑]", color = Green, fontFamily = UiFont, modifier = Modifier.semantics { contentDescription = "previous match" }.padding(3.dp).panel(Pal.S2c, Pal.Line2, Pal.R8).tap { cur = ThreadSearch.step(cur, hits.size, false) }.padding(horizontal = 6.dp))
            Text("[↓]", color = Green, fontFamily = UiFont, modifier = Modifier.semantics { contentDescription = "next match" }.padding(3.dp).panel(Pal.S2c, Pal.Line2, Pal.R8).tap { cur = ThreadSearch.step(cur, hits.size, true) }.padding(horizontal = 6.dp))
        }
        fun whoOf(m: Message) = if (m.outgoing) "you" else if (isGroup) com.example.emit.data.ThreadMapping.senderName(m.address, participants, participantNames) else com.example.emit.data.DisplayName.person(title, participants.firstOrNull().orEmpty())
        val chatRows = remember(msgs, participants) { ChatLogic.rows(msgs.map { ChatLogic.Entry(if (it.outgoing) "\u0000me" else it.address, it.date) }, System.currentTimeMillis()) }
        if (selecting) SelectionHeader(picked.size, onCancel = { picked = emptySet() }) {
            val keys = msgs.map { it.key }; val all = keys.isNotEmpty() && picked.containsAll(keys)
            SelAction(if (all) "none" else "all", if (all) "select none" else "select all") { picked = if (all) emptySet() else keys.toSet() }
            SelAction("copy", "copy selected") { Clip.copy(ctx, MessageText.multi(msgs.filter { it.key in picked }, ::whoOf)); picked = emptySet() }
        }
        if (messages == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Text("  loading messages...", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
                SkeletonLines()
            }
        } else LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)
            .pointerInput(Unit) { detectTapGestures { kbCtl.hide() } }, state = listState) {
            if (header == 1) item(key = "older") {
                Text(if (loadingOlder) "> fetching older packets..." else "> scroll up for older_", color = DimGreen,
                    fontFamily = UiFont, fontSize = TypeScale.Meta, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (msgs.isEmpty() && drafts.isEmpty()) item(key = "empty") {
                EmptyState("> no messages_", "type below and press SEND to start this thread")
            }
            itemsIndexed(msgs, key = { _, it -> it.key }) { idx, m ->
                val row = chatRows.getOrNull(idx) ?: ChatLogic.Row(null, true, true)
                val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
                val who = whoOf(m)
                val on = m.key in picked
                Column(Modifier.fillMaxWidth().then(if (still) Modifier else Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.tween(120), fadeOutSpec = androidx.compose.animation.core.tween(120)))) {
                row.divider?.let { DateDivider(it) }
                var dragX by remember(m.key) { mutableStateOf(0f) }
                val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("reply") { text = com.example.emit.data.MessageMeta.withQuote(com.example.emit.data.MessageMeta.quote(who, m.body), text); true })
                    }
                    .graphicsLayer { translationX = dragX }
                    .pointerInput(m.key, selecting) { if (!selecting) detectHorizontalDragGestures(
                        onDragEnd = { if (dragX > swipePx) { hv.confirm(); text = com.example.emit.data.MessageMeta.withQuote(com.example.emit.data.MessageMeta.quote(who, m.body), text) }; dragX = 0f },
                        onDragCancel = { dragX = 0f }) { _, dx -> dragX = (dragX + dx).coerceIn(0f, swipePx * 1.3f) } }
                    .then(if (on) Modifier.background(Green.copy(alpha = 0.15f)).border(1.dp, FaintGreen, Pal.R8) else Modifier)
                    .combinedClickable(
                        onClick = {
                            when {
                                selecting -> picked = if (on) picked - m.key else picked + m.key
                                m.retryLocation != null -> retryMms(appCtx, m)
                                else -> kbCtl.hide()
                            }
                        },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (selecting) picked = picked + m.key else actionFor = m },
                    ).padding(top = if (row.first) Ui.BubbleGapBetween else 2.dp).padding(start = if (m.outgoing) Ui.OutgoingIndent else 0.dp), horizontalAlignment = Alignment.Start) {
                    val tint = ChatTint
                    val pal = LocalTheme.current.palette
                    val mc = Color(when {
                        m.outgoing -> pal.sent
                        fixedChat -> pal.incoming
                        else -> tint.incoming
                    })
                    // 0.1.47: a chosen chat color shows at full strength on sender names; default chats keep their label color.
                    val accentNow = LocalAccent.current
                    val nameColor = if (m.outgoing) Pal.OutLabel else if (!NameColor.isDefault(accentNow)) Color(NameColor.argb(accentNow, pal.primary, tint.bg))
                        else if (fixedChat) Pal.Cyan else Color(tint.name)
                    val comms = LocalTheme.current.comms
                    if (row.first) Text(if (comms) Comms.header(who, stamp(m.date), m.outgoing) else "> " + if (LocalTheme.current.chrome.uppercaseNames) who.uppercase() else who, color = nameColor, fontFamily = DisplayFont, fontSize = TypeScale.Meta,
                        maxLines = 1, modifier = Modifier.padding(bottom = 3.dp).scrim())
                    val qp = remember(m.body) { com.example.emit.data.MessageMeta.parseQuote(m.body) }
                    val shownBody = qp?.body ?: m.body
                    if (qp != null) {
                        val qc = if (qp.who == "you" || qp.who == "me") Pal.OutLabel else nameColor
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).padding(end = 24.dp).panel(Pal.S1c, Pal.Line, LocalTheme.current.shape(9.dp))
                            .height(androidx.compose.foundation.layout.IntrinsicSize.Min).semantics(mergeDescendants = true) { contentDescription = "quoting ${qp.who}: ${qp.quote}" }) {
                            Box(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp).width(3.dp).fillMaxHeight().background(qc, LocalTheme.current.shape(2.dp)))
                            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                                Text(">> ${qp.who}:", color = qc, fontFamily = UiFont, fontSize = TypeScale.Micro, maxLines = 1)
                                Text(qp.quote, color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (shownBody.isNotBlank() || (qp == null && (m.media.isEmpty() || m.retryLocation != null)) || (qp != null && m.retryLocation != null)) Text(LinkFinder.annotate("", shownBody, Pal.Cyan, if (searching) query else "", hits.getOrNull(cur) == idx, Pal.SearchBg, Pal.SearchFg),
                        color = mc, fontFamily = UiFont, fontSize = TypeScale.Body, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().panel((if (m.outgoing) Pal.OutBg else Color(if (fixedChat) pal.inBg else tint.s1)).copy(alpha = Pal.BUBBLE_A), if (m.outgoing) Pal.OutLine else Color(if (fixedChat) pal.inLine else tint.line), Pal.R8)
                            .then(if (comms) Modifier.commsStripe(if (m.outgoing) Pal.Primary else Pal.Cyan, m.outgoing) else Modifier)
                            .padding(horizontal = 10.dp, vertical = 7.dp))
                    if (previewsOn == true) LinkFinder.find(m.body).firstOrNull { it.target.startsWith("https://") }?.let { LinkPreviewLine(it.target) }
                    m.media.forEach { md ->
                        CompositionLocalProvider(LocalAccent provides if (m.outgoing) ChatPalette.DEFAULT else LocalAccent.current) {
                            MediaThumb(md.uri, md.mime, 180, Modifier.padding(top = 4.dp).combinedClickable(
                                onClick = { if (selecting) picked = if (on) picked - m.key else picked + m.key else { viewingLabel = whoOf(m) + " · " + stamp(m.date); viewing = md } },
                                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); actionFor = m }))
                        }
                    }
                    if (m.outgoing) botEntries.let { be ->
                        com.example.emit.bot.BotLogic.matchFor(be, threadId, m.body, m.date, true, { it.threadId }, { it.body }, { it.created })
                    }?.let { e -> com.example.emit.bot.BotLogic.tag(e.status)?.let { tg -> Row(Modifier.align(Alignment.End).scrim(), verticalAlignment = Alignment.CenterVertically) {
                        Text(tg, color = if (e.status == "queued") Amber else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
                        if (e.status == "queued") Text(" [ cancel ]", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Meta,
                            modifier = Modifier.tap { uiScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.bot.BotCommands.cancel(appCtx, e.id) } }.padding(4.dp))
                    } } }
                    val failed = ChatLogic.canRetry(m.delivery, m.isMms)
                    if (row.last || m.delivery == ChatLogic.Delivery.FAILED || m.delivery == ChatLogic.Delivery.PENDING)
                        Row(Modifier.align(Alignment.End).padding(top = 2.dp).scrim(), verticalAlignment = Alignment.CenterVertically) {
                            if (m.delivery != ChatLogic.Delivery.NONE) Text(m.delivery.label + if (failed) " tap to retry" else "",
                                color = if (m.delivery == ChatLogic.Delivery.FAILED) DangerRed else FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1,
                                modifier = Modifier.semantics { contentDescription = m.delivery.spoken }.then(if (failed) Modifier.tap {
                                    hv.confirm(); uiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val res = com.example.emit.sms.SmsSender.retryFailed(appCtx, m.id)
                                        if (res is com.example.emit.sms.SmsSender.Result.Refused) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(appCtx, "retry: " + res.reason, android.widget.Toast.LENGTH_SHORT).show() }
                                    } } else Modifier))
                            if (row.last) Text((if (m.delivery != ChatLogic.Delivery.NONE) " " else "") + stamp(m.date), color = FaintGreen, fontFamily = UiFont,
                                fontSize = TypeScale.Meta, maxLines = 1)
                        }
                }
                }
            }
            items(drafts, key = { "draft:" + it.id }) { d ->
                val hv = androidx.compose.ui.platform.LocalView.current
                Column(Modifier.fillMaxWidth().padding(vertical = Ui.BubbleGap).dashedDraftBorder().padding(Ui.CardPad)) {
                    Text("[PRIME DRAFT] // NOT SENT - NEEDS YOUR APPROVAL", color = Black, fontFamily = UiFont, fontSize = TypeScale.Meta,
                        modifier = Modifier.background(Amber, Pal.R8).padding(horizontal = 4.dp))
                    Text("> ${d.body}", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Body)
                    if (!canApprove) Row(Modifier.padding(top = 8.dp)) {
                        Text("[approve disabled in groups]", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Meta, modifier = Modifier.weight(1f))
                        Text("[ REJECT ]", color = Amber, fontFamily = UiFont, modifier = Modifier.panel(Pal.S2c, Amber.copy(alpha = 0.6f), Pal.R8).tap { hv.confirm(); onReject(d) }.padding(8.dp))
                    }
                    if (canApprove) ButtonRow(Btn("[ APPROVE ]", Amber, filled = true, confirm = true) { onApprove(d) }, Btn("[ REJECT ]", Amber, confirm = true) { onReject(d) },
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (notice != null) Text(notice, color = Amber, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).scrim())
        // the user's typing area is always terminal green regardless of chat color.
        val mine = scheduled.filter { it.threadId == threadId }
        if (mine.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            mine.forEach { sc -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⏱ ${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(Date(sc.at))}  ${sc.body.take(40)}", color = Amber, fontFamily = UiFont,
                    fontSize = TypeScale.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("[ cancel ]", color = Amber, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1, softWrap = false,
                    modifier = Modifier.tap { uiScope.launch(kotlinx.coroutines.Dispatchers.IO) { com.example.emit.sms.Scheduler.cancel(draftCtx, sc.id) } }.padding(start = 8.dp))
            } }
        }
        if (scheduling) ScheduleDialog(onClose = { scheduling = false }) { at ->
            scheduling = false
            val addr = if (participants.size == 1) participants.first() else ""
            val body = text.trim()
            uiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val err = com.example.emit.sms.Scheduler.add(draftCtx, threadId, addr, body, at)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (err == null) { text = ""; draftMem.set(threadId, ""); hv.confirm() }
                    android.widget.Toast.makeText(draftCtx, err ?: ("scheduled" + if (!com.example.emit.sms.Scheduler.exactAllowed(draftCtx)) " (approximate: exact alarms not allowed)" else ""), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (canSend) CompositionLocalProvider(LocalAccent provides ChatPalette.DEFAULT) {
            if (tray) AttachTray(onPick = { attachments = (attachments + it).distinctBy { a -> a.uri }.take(5); tray = false }, onClose = { tray = false })
            AttachmentStrip(attachments) { a -> attachments = attachments - a }
            ComposeBar(text, { text = it }, hasAttachments = attachments.isNotEmpty(), onAttach = { tray = !tray; kbCtl.hide() },
                onSchedule = if (participants.size == 1 && text.isNotBlank() && attachments.isEmpty()) ({ scheduling = true; kbCtl.hide() }) else null,
                onPasteMedia = { u, mt -> attachments = (attachments + Attachment(u, mt)).distinctBy { a -> a.uri }.take(5) }) {
                val t = text.trim(); val atts = attachments
                onSend(t, atts) { text = ""; draftMem.set(threadId, ""); attachments = emptyList(); txSeq++ }
            }
        }
        infoFor?.let { m -> MessageInfoSheet(m) { infoFor = null } }
        deleteFor?.let { m -> DeleteMessageConfirm(onCancel = { deleteFor = null }) {
            deleteFor = null
            uiScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val ok = com.example.emit.data.SmsRepository(ctx).deleteMessage(m)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, if (ok) "message deleted" else "couldn't delete (default SMS app required)", android.widget.Toast.LENGTH_SHORT).show()
                    if (ok) com.example.emit.sms.IncomingBus.ping()
                }
            }
        } }
        viewing?.let { md ->
            if (md.isImage) FullscreenImage(md.uri, viewingLabel, onSave = { MediaSaver.saveResult(ctx, md.uri, md.mime) }) { viewing = null }
            else if (md.mime.startsWith("video/")) FullscreenVideo(md.uri, viewingLabel, onSave = { MediaSaver.saveResult(ctx, md.uri, md.mime) }) { viewing = null }
            else viewing = null
        }
        actionFor?.let { m ->
            MessageSheet(m, whoOf(m), onDismiss = { actionFor = null },
                onReply = { text = com.example.emit.data.MessageMeta.withQuote(com.example.emit.data.MessageMeta.quote(whoOf(m), m.body), text); actionFor = null },
                onForward = { actionFor = null; onForward(com.example.emit.data.MessageMeta.messagePart(m.body)) },
                onDelete = { deleteFor = m; actionFor = null },
                onInfo = { infoFor = m; actionFor = null },
                onCopy = { Clip.copy(ctx, MessageText.single(m)); actionFor = null },
                onSelectText = { selectTextOf = m; actionFor = null },
                onMulti = { picked = setOf(m.key); actionFor = null },
                onView = { md -> viewingLabel = whoOf(m) + " · " + stamp(m.date); viewing = md; actionFor = null },
                onSave = { md -> saveMedia(md); actionFor = null })
        }
        selectTextOf?.let { m -> SelectTextDialog("> ${whoOf(m)}  [${stamp(m.date)}]", com.example.emit.data.MessageMeta.messagePart(m.body)) { selectTextOf = null } }
    }
    TransmissionOverlay(txSeq)
    }
}

/**
 * Terminal input. Uses the in-app Matrix keyboard (field is readOnly to the system IME, with our own block cursor);
 * the ⌨ key switches to the system keyboard as a fallback until the field loses focus.
 */
@Composable
fun TerminalField(
    value: String, onChange: (String) -> Unit, prompt: String, placeholder: String,
    modifier: Modifier = Modifier, singleLine: Boolean = false, focusRequester: FocusRequester = remember { FocusRequester() },
    keyboardType: KeyboardType = KeyboardType.Text,
    onPasteMedia: ((android.net.Uri, String) -> Unit)? = null,
    bare: Boolean = false,
    textSize: androidx.compose.ui.unit.TextUnit = TypeScale.Body,
    maxLinesMulti: Int = 5,
    /** Receives the cursor position whenever it changes (used by voice input to insert at the cursor). */
    onCursor: ((Int) -> Unit)? = null,
) {
    val kb = LocalKb.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pasteScope = rememberCoroutineScope()
    var pasteMenu by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val id = remember { Any() }
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // Single source of truth is tfv (text + selection). External changes (e.g. cleared after send) are reconciled
    // with a clamped selection.
    if (tfv.text != value) tfv = FieldSync.reconcile(tfv, value)
    val latestCursor by rememberUpdatedState(onCursor)
    LaunchedEffect(tfv.selection.start) { latestCursor?.invoke(tfv.selection.start) }
    val latestChange by rememberUpdatedState(onChange)
    val phone = keyboardType == KeyboardType.Phone
    val latestMedia by rememberUpdatedState(onPasteMedia)
    val doPaste: () -> Unit = {
        pasteScope.launch {
            val p = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Clip.read(ctx) }
            when (p) {
                is MessageText.Paste.Text -> {
                    val t = if (phone) p.text.filter { it.isDigit() || it in "+#*" } else if (singleLine) p.text.replace('\n', ' ') else p.text
                    val n = tfv.text.length
                    val es = EditState(tfv.text, tfv.selection.start.coerceIn(0, n), tfv.selection.end.coerceIn(0, n)).insert(t)
                    val v = TextFieldValue(es.text, TextRange(es.start)); tfv = v; latestChange(v.text)
                    if (kb.target?.id == id) { kb.lastCorrection = null; kb.version++ }
                }
                is MessageText.Paste.Media -> latestMedia?.invoke(p.uri, p.mime)
                    ?: android.widget.Toast.makeText(ctx, "> can't paste media here", android.widget.Toast.LENGTH_SHORT).show()
                MessageText.Paste.Nothing -> android.widget.Toast.makeText(ctx, "> clipboard empty", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Unit
    }
    val target = remember(id, phone, singleLine) {
        KbTarget(id, phone, singleLine, get = { tfv }, set = { v -> val c = FieldSync.clamp(v); tfv = c; latestChange(c.text) }, paste = doPaste)
    }
    var focused by remember { mutableStateOf(false) }
    val active = focused && kb.target?.id == id
    val custom = !kb.useSystem
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(active) { while (active) { delay(530); blink = !blink } }
    // Keyboard collapsed elsewhere (tap outside / back) -> drop focus so the next tap reopens it.
    LaunchedEffect(kb.target) { if (focused && kb.target == null) focusManager.clearFocus() }
    LaunchedEffect(kb.useSystem, active) { if (active) { if (kb.useSystem) keyboard?.show() else keyboard?.hide() } }

    BasicTextField(
        tfv, { v -> val c = FieldSync.clamp(v); tfv = c; latestChange(c.text) },
        readOnly = custom,
        textStyle = FieldText.style(Green, textSize),
        cursorBrush = SolidColor(Green), singleLine = singleLine, minLines = 1, maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = modifier.focusRequester(focusRequester).onFocusChanged { st ->
            focused = st.isFocused
            if (st.isFocused) { kb.target = target; if (!kb.useSystem) keyboard?.hide() }
            else if (kb.target?.id == id) { kb.target = null; kb.useSystem = false }
        },
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(if (bare) Modifier else Modifier.panel(Pal.S1c, Pal.Line2, Pal.R8))
                .pointerInput(Unit) { detectLongPressPassive { pasteMenu = true } }
                .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pasteMenu) androidx.compose.ui.window.Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, -140),
                    onDismissRequest = { pasteMenu = false }, properties = androidx.compose.ui.window.PopupProperties(focusable = true)) {
                    Row(Modifier.panel(Pal.S2c, Pal.Line2, Pal.R12)) {
                        Text("[ paste ]", color = Green, fontFamily = UiFont, modifier = Modifier.tap { pasteMenu = false; doPaste() }.padding(12.dp))
                        if (tfv.text.isNotEmpty()) Text("[ copy all ]", color = Green, fontFamily = UiFont,
                            modifier = Modifier.tap { pasteMenu = false; Clip.copy(ctx, tfv.text) }.padding(12.dp))
                    }
                }
                Text(prompt, color = Green, fontFamily = UiFont, fontSize = textSize)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        if (!active) BlinkCursor()
                        Text(placeholder.trimEnd('_'), color = DimGreen, fontFamily = UiFont, fontSize = textSize, maxLines = 1,
                            modifier = Modifier.padding(start = if (active) 10.dp else 0.dp))
                    }
                    val green = Green
                    val cursorBox = Modifier.drawWithContent {
                        drawContent()
                        val l = layout
                        // Only draw from a layout of the CURRENT text; otherwise wait a frame.
                        if (active && custom && blink && l != null && l.layoutInput.text.text == tfv.text) {
                            val r = CursorMath.rect(l, tfv.selection.start)
                            val cw = 5.dp.toPx()  // ▌ half block
                            if (singleLine) {
                                val pos = CursorMath.fit(r.left, r.top, r.height, cw, size.width, size.height, l.size.width.toFloat(), l.size.height.toFloat())
                                clipRect { drawRect(green, Offset(pos.first, pos.second), Size(cw, r.height.coerceAtMost(size.height))) }
                            } else {
                                // 0.1.45: multi-line text is never scrolled internally (we own the scroll below), so layout
                                // coordinates == our coordinates and the cursor is drawn exactly where the selection is.
                                drawRect(green, Offset(r.left.coerceIn(0f, (size.width - cw).coerceAtLeast(0f)), r.top), Size(cw, r.height))
                            }
                        }
                    }
                    if (singleLine) Box(Modifier.clipToBounds().then(cursorBox)) { inner() }
                    else {
                        // Own vertical scroll capped at maxLinesMulti lines; keeps the cursor line visible.
                        val scroll = androidx.compose.foundation.rememberScrollState()
                        val l = layout
                        val cap = l?.let { CursorMath.viewportHeight(it, maxLinesMulti) }
                        LaunchedEffect(tfv.selection, l) {
                            if (l != null && cap != null && l.layoutInput.text.text == tfv.text) {
                                val r = CursorMath.rect(l, tfv.selection.start)
                                val to = CursorMath.scrollToShow(scroll.value, cap, r.top, r.bottom)
                                if (to != scroll.value) scroll.scrollTo(to)
                            }
                        }
                        val capMod = if (cap != null) Modifier.heightIn(max = with(androidx.compose.ui.platform.LocalDensity.current) { cap.toDp() }) else Modifier
                        Box(capMod.clipToBounds().verticalScroll(scroll)) { Box(cursorBox) { inner() } }
                    }
                }
                if (active && kb.useSystem) Text(" [mx]", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label,
                    modifier = Modifier.tap { kb.useSystem = false })
            }
        },
    )
}

/** Pinned terminal-style compose bar: '> ' prompt, field, [SEND]. */
@Composable
fun ComposeBar(text: String, onChange: (String) -> Unit, hasAttachments: Boolean = false, onAttach: (() -> Unit)? = null,
               onPasteMedia: ((android.net.Uri, String) -> Unit)? = null, onSchedule: (() -> Unit)? = null, onSend: () -> Unit) {
    val ready = text.isNotBlank() || hasAttachments
    val hv = androidx.compose.ui.platform.LocalView.current
    val fr = remember { FocusRequester() }
    val quoted = text.startsWith(">> ") && text.contains('\n')
    val quoteLine = if (quoted) text.substringBefore('\n') else ""
    val bodyText = if (quoted) text.substringAfter('\n') else text
    val edit: (String) -> Unit = { onChange(if (quoted) quoteLine + "\n" + it else it) }
    val curBody by rememberUpdatedState(bodyText); val curEdit by rememberUpdatedState(edit)
    var cursor by remember { mutableIntStateOf(Int.MAX_VALUE) }  // MAX = end (never tapped)
    val voice = rememberVoiceInput { spoken -> curEdit(VoiceText.insertAt(curBody, cursor, spoken).first) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (voice.listening) Text("● listening… " + voice.partial.ifEmpty { "speak now" }, color = Green, fontFamily = UiFont, fontSize = TypeScale.Meta,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        if (quoted) {
            val q = quoteLine.removePrefix(">> ")
            val who = q.substringBefore(": ", ""); val what = if (who.isEmpty()) q else q.substringAfter(": ")
            val accent = Color(ChatTint.name)
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).panel(Pal.S1c, Pal.Line, LocalTheme.current.shape(9.dp))
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .semantics(mergeDescendants = true) { contentDescription = "replying to $who: $what" }, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp).width(3.dp).fillMaxHeight().background(accent, LocalTheme.current.shape(2.dp)))
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = accent)); append(">> " + (if (who.isEmpty()) "" else "$who: ")); pop()
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = DimGreen)); append(what); pop()
                }, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp))
                Box(Modifier.size(Ui.Touch, 36.dp).semantics { contentDescription = "cancel reply" }.tap { onChange(bodyText) }, contentAlignment = Alignment.Center) {
                    Text("[x]", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta) }
            }
        }
        // 0.1.44: field gets the full width; compact action row beneath it.
        Column(Modifier.fillMaxWidth().shadow(12.dp, Pal.R12, ambientColor = Color.Black, spotColor = Color.Black)
            .panel(Pal.S2c.copy(alpha = Pal.BUBBLE_A), if (LocalTheme.current.titleGlow) Pal.Primary.copy(alpha = 0.33f) else Pal.Line2, Pal.R12)
            .padding(horizontal = 4.dp, vertical = 2.dp)) {
            TerminalField(bodyText, edit, "> ", "type message_", Modifier.fillMaxWidth().testTag("compose_field"), focusRequester = fr, onPasteMedia = onPasteMedia, bare = true,
                textSize = FieldText.SIZE, maxLinesMulti = FieldText.MAX_LINES, onCursor = { cursor = it })
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(Pal.Line))
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val shape = Pal.R8
                @Composable fun Icon(label: String, desc: String, on: Boolean = false, bg: Color = Color.Transparent, act: () -> Unit) =
                    Box(Modifier.size(44.dp).semantics { contentDescription = desc }.tap { act() }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(34.dp).background(bg, shape).border(1.dp, if (on) Green else Pal.Line2, shape), contentAlignment = Alignment.Center) {
                            Text(label, color = if (on) Green else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label) } }
                if (onAttach != null) Icon("+", "attach") { onAttach() }
                val on = voice.listening
                Icon(if (on) "■" else "🎙", if (on) "stop dictation" else "dictate message, voice to text", on,
                    if (on) Green.copy(alpha = 0.18f + 0.4f * voice.level) else Color.Transparent) { hv.confirm(); voice.tap() }
                if (onSchedule != null) Icon("⏱", "schedule send") { onSchedule() }
                Spacer(Modifier.weight(1f))
                if (text.isNotEmpty()) Text(ChatLogic.segments(text).label, color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Micro,
                    maxLines = 1, modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.heightIn(min = 40.dp).background(if (ready) Pal.Primary else Pal.S1c, Pal.R8).border(1.dp, if (ready) Pal.Primary else Pal.Line2, Pal.R8)
                    .clickable(enabled = ready) { hv.confirm(); onSend() }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text("SEND", color = if (!ready) FaintGreen else Pal.OnPrimary, fontFamily = DisplayFont, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = TypeScale.Label, modifier = Modifier.semantics { contentDescription = if (ready) "send" else "send, nothing to send" })
                }
            }
        }
    }
}

/** Field text style: word-boundary wrapping, never hyphenate/char-break mid-word (unless a single word exceeds the line). */
object FieldText {
    val SIZE = 13.sp
    const val MAX_LINES = 5
    @Composable fun style(color: Color, size: androidx.compose.ui.unit.TextUnit) = TextStyle(color = color, fontFamily = UiFont, fontSize = size,
        lineBreak = androidx.compose.ui.text.style.LineBreak(
            strategy = androidx.compose.ui.text.style.LineBreak.Strategy.Simple,
            strictness = androidx.compose.ui.text.style.LineBreak.Strictness.Normal,
            wordBreak = androidx.compose.ui.text.style.LineBreak.WordBreak.Default),
        hyphens = androidx.compose.ui.text.style.Hyphens.None)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NewMessageScreen(
    prefillAddress: String, onBack: () -> Unit, prefillBody: String = "", onSend: (List<String>, String) -> Unit,
    search: suspend (String) -> List<Pair<String, String>> = { emptyList() },
) {
    var recipients by remember { mutableStateOf(if (prefillAddress.isBlank()) emptyList() else listOf(prefillAddress to prefillAddress)) }
    var to by remember { mutableStateOf("") }
    var body by remember { mutableStateOf(prefillBody) }
    var phonePad by remember { mutableStateOf(false) }
    val toFr = remember { FocusRequester() }
    val suggestions by produceState(emptyList<Pair<String, String>>(), to) {
        value = if (to.trim().length < 2) emptyList() else { delay(120); search(to.trim()) }
    }
    fun add(name: String, number: String) {
        val n = number.trim(); if (n.isBlank()) return
        if (recipients.none { com.example.emit.data.ThreadMapping.sameAddress(it.second, n) }) recipients = recipients + (name to n)
        to = ""
    }
    LaunchedEffect(Unit) { if (prefillAddress.isBlank()) runCatching { toFr.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Black)) {
        Header(if (recipients.size > 1) "new_group(${recipients.size})" else "new_message", onBack)
        if (recipients.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            recipients.forEach { r ->
                Text("[${r.first}] x", color = Black, fontFamily = UiFont, fontSize = TypeScale.Label,
                    modifier = Modifier.padding(4.dp).background(Green, Pal.R8).tap { recipients = recipients - r }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            key(phonePad) {
                TerminalField(to, { v -> if (v.endsWith(",") || v.endsWith("\n")) add(v.trimEnd(',', '\n'), v.trimEnd(',', '\n')) else to = v },
                    "to: ", if (recipients.isEmpty()) "name or number" else "add another", Modifier.weight(1f),
                    singleLine = true, focusRequester = toFr, keyboardType = if (phonePad) KeyboardType.Phone else KeyboardType.Text)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.heightIn(min = 48.dp).border(1.dp, Green, Pal.R8).tap { add(to, to) }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("[+]", color = Green, fontFamily = UiFont)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.heightIn(min = 48.dp).border(1.dp, DimGreen, Pal.R8).tap { phonePad = !phonePad }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text(if (phonePad) "abc" else "#", color = DimGreen, fontFamily = UiFont)
            }
        }
        suggestions.forEach { (n, num) ->
            Text("> $n  $num", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label,
                modifier = Modifier.fillMaxWidth().tap { add(n, num) }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (recipients.size > 1) Text("> sends as ONE group MMS to all ${recipients.size}", color = DimGreen, fontFamily = UiFont,
            fontSize = TypeScale.Meta, modifier = Modifier.padding(horizontal = 12.dp))
        val kbCtl = LocalKb.current
        Spacer(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { kbCtl.hide() } })
        ComposeBar(body, { body = it }) {
            val all = recipients.map { it.second } + listOfNotNull(to.trim().ifBlank { null })
            if (all.isNotEmpty() && body.isNotBlank()) onSend(all, body.trim())
        }
    }
}

/** Re-requests a never-downloaded incoming MMS from the carrier (download only; nothing is sent). */
private fun retryMms(context: android.content.Context, m: com.example.emit.data.Message) {
    val loc = m.retryLocation ?: return
    android.util.Log.i("MatrixSMS", "mms retry tapped row=${m.id}")
    android.widget.Toast.makeText(context, "retrying download...", android.widget.Toast.LENGTH_SHORT).show()
    com.example.emit.mms.MmsTransport.download(context,
        com.example.emit.mms.MmsNotification(null, loc, m.address.ifBlank { null }, null),
        placeholderRow = "content://mms/${m.id}")
}


/** Long-press detector that observes without consuming, so the text field still gets taps/focus. */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectLongPressPassive(onLong: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        }
        if (up == null) onLong()
    }
}

/** Terminal bottom sheet of actions for one message. */
@Composable
fun MessageSheet(
    m: Message, who: String, onDismiss: () -> Unit, onCopy: () -> Unit, onSelectText: () -> Unit, onMulti: () -> Unit,
    onReply: () -> Unit, onForward: () -> Unit, onDelete: () -> Unit, onInfo: () -> Unit,
    onView: (com.example.emit.data.MediaRef) -> Unit, onSave: (com.example.emit.data.MediaRef) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Pal.Scrim).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onDismiss() }) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).shadow(16.dp, Pal.R12).panel(Pal.S2c, Pal.Line2, Pal.R12)
           .clickable(enabled = false) {}.padding(16.dp)) {
            Text("> msg from $who", color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
            if (m.body.isNotBlank()) Text("  " + m.body, color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            // [ copy | reply | forward | delete | info ]
            Row(Modifier.fillMaxWidth().border(1.dp, FaintGreen, Pal.R8)) {
                @Composable fun A(label: String, c: Color, f: () -> Unit) = Text(label, color = c, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1, softWrap = false,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 3.dp).panel(Pal.S1c, if (c == DangerRed) DangerRed.copy(alpha = 0.5f) else Pal.Line2, Pal.R8).tap { f() })
                A("copy", Green, onCopy); A("reply", Green, onReply); A("fwd", Green, onForward); A("del", DangerRed, onDelete); A("info", Green, onInfo)
            }
            Spacer(Modifier.height(4.dp))
            @Composable fun Opt(label: String, f: () -> Unit) = Text(label, color = Green, fontFamily = UiFont, fontSize = TypeScale.Body,
                modifier = Modifier.fillMaxWidth().tap { f() }.padding(vertical = 12.dp))
            if (m.body.isNotBlank()) { Opt("[ copy text ]", onCopy); Opt("[ select text... ]", onSelectText) }
            Opt("[ select multiple ]", onMulti)
            m.media.forEachIndexed { i, md ->
                val kind = if (md.isImage) "image" else if (md.mime.startsWith("video/")) "video" else null
                if (kind != null) {
                    val n = if (m.media.size > 1) " ${i + 1}" else ""
                    Opt("[ view $kind$n ]") { onView(md) }
                    Opt("[ save $kind$n to gallery ]") { onSave(md) }
                }
            }
            Text("[ cancel ]", color = DimGreen, fontFamily = UiFont, modifier = Modifier.padding(top = 8.dp).panel(Pal.S2c, Pal.Line2, Pal.R8).tap { onDismiss() }.padding(8.dp))
        }
    }
}

/** Shows a message with native text selection: drag the handles to highlight part of it, then tap Copy. */
@Composable
fun SelectTextDialog(title: String, body: String, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text(title, color = Green, fontFamily = UiFont, fontSize = TypeScale.Label)
            Text("  long-press + drag to highlight, then Copy", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.heightIn(max = 420.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(body, color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
                }
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ButtonRow(Btn("[ copy all ]", Green, filled = true) { Clip.copy(ctx, body); onClose() }, Btn("[ close ]", DimGreen) { onClose() },
                modifier = Modifier.padding(top = 12.dp))
        }
    } }
}

/** Sits directly under the PC button; badge when a verified update pushed from the PC is waiting. */
@Composable
fun UpdateButton(waiting: Boolean, onClick: () -> Unit) {
    Row(Modifier.panel(Pal.S2c, if (waiting) Amber.copy(alpha = 0.6f) else Pal.Line2, Pal.R8).tap { onClick() }
        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (waiting) { Box(Modifier.size(8.dp).background(Amber, androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(8.dp)) }
        Text("⇩ UPD", color = if (waiting) Amber else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label)
    }
}

@Composable
fun UpdateDialog(onClose: () -> Unit) {
    OverlayOpen()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p by com.example.emit.link.AppUpdater.pending.collectAsState()
    val st by com.example.emit.link.AppUpdater.status.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text("> APP UPDATE", color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
            Spacer(Modifier.height(8.dp))
            val cur = com.example.emit.BuildConfig.VERSION_NAME
            val u = p
            if (u == null) Text("installed: $cur\nno update waiting.\nSend one from the PC: msms update push <apk>", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label)
            else {
                Text("installed: $cur\navailable: ${u.versionName} (code ${u.versionCode})\nsize: ${"%.1f".format(u.size / 1048576.0)} MB\nsha256: ${u.sha256.take(16)}…\nsigner: matches installed app ✓",
                    color = Green, fontFamily = UiFont, fontSize = TypeScale.Label)
                Spacer(Modifier.height(12.dp))
                ButtonRow(Btn("[ install ]", Green, filled = true, confirm = true) { msg = com.example.emit.link.AppUpdater.install(ctx) },
                    Btn("[ decline ]", DangerRed) { com.example.emit.link.AppUpdater.clear(ctx); onClose() }, fontSp = 15)
            }
            (st ?: msg)?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Amber, fontFamily = UiFont, fontSize = TypeScale.Label) }
            Spacer(Modifier.height(8.dp))
            Text("[ close ]", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.tap { onClose() }.padding(4.dp))
        }
    } }
}

enum class PcStatus { OFF, WAITING, CONNECTED;
    companion object { fun of(linkOn: Boolean, connected: Int) = if (connected > 0) CONNECTED else if (linkOn) WAITING else OFF } }

@Composable
fun PcButton(status: PcStatus, onClick: () -> Unit) {
    val dot = when (status) { PcStatus.CONNECTED -> Green; PcStatus.WAITING -> Amber; PcStatus.OFF -> FaintGreen }
    Row(Modifier.panel(Pal.S2c, if (status == PcStatus.CONNECTED) Pal.OutLine else Pal.Line2, Pal.R8)
        .tap { onClick() }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(dot, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(8.dp))
        Text("⇄ PC", color = if (status == PcStatus.OFF) DimGreen else Green, fontFamily = UiFont, fontSize = TypeScale.Label)
    }
}

@Composable
fun DateDivider(label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).semantics(mergeDescendants = true) { contentDescription = label.lowercase() },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Pal.Line))
        Text(" ${LocalTheme.current.chrome.dayPrefix}$label ", modifier = Modifier.scrim(), color = FaintGreen, letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp), fontFamily = DisplayFont, fontSize = TypeScale.Meta, maxLines = 1)
        Box(Modifier.weight(1f).height(1.dp).background(Pal.Line))
    }
}

/** ASCII empty / error state with a next step. */
@Composable
fun EmptyState(title: String, next: String, color: Color = DimGreen) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp).panel(Pal.S1c, Pal.Line, Pal.R12).padding(20.dp)
        .semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).panel(Pal.S2c, Pal.Line2, Pal.R12), contentAlignment = Alignment.Center) { Text("∅", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Title) }
        Spacer(Modifier.height(12.dp))
        Text(title.removePrefix("> ").removeSuffix("_"), color = if (color == DimGreen) Pal.Body else color, fontFamily = UiFont, fontSize = TypeScale.Body)
        Text(next, color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

/** ░░░ skeleton lines while a thread loads. */
@Composable
fun SkeletonLines(n: Int = 6) {
    val still = rememberReduceMotion()
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(still) { while (!still) { delay(400); phase++ } }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = "loading messages" }) {
        repeat(n) { i ->
            val out = i % 3 == 1; val len = listOf(18, 26, 12, 30, 16, 22)[i % 6]
            Text("░".repeat(len), color = FaintGreen.copy(alpha = if ((i + phase) % 3 == 0) 0.9f else 0.5f), fontFamily = UiFont, fontSize = TypeScale.Body,
                maxLines = 1, softWrap = false, modifier = Modifier.padding(start = if (out) Ui.OutgoingIndent else 0.dp, top = 8.dp))
        }
    }
}

@Composable
fun MessageInfoSheet(m: Message, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rows by produceState<List<Pair<String, String>>?>(null, m.key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fmt = java.text.SimpleDateFormat("EEE MMM d yyyy, h:mm:ss a", java.util.Locale.US)
            com.example.emit.data.MessageMeta.info(m, com.example.emit.data.SmsRepository(ctx).dateSent(m),
                if (m.isMms) null else com.example.emit.data.DeliveryTimes(ctx).get(m.id)) { fmt.format(Date(it)) }
        }
    }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text("> message info", color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
            Spacer(Modifier.height(8.dp))
            val r = rows
            if (r == null) SkeletonLines(3)
            else r.forEach { (k, v) -> Row(Modifier.padding(vertical = 2.dp)) {
                Text(k.padEnd(10), color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label, maxLines = 1, softWrap = false)
                Text(v, color = Green, fontFamily = UiFont, fontSize = TypeScale.Label)
            } }
            ButtonRow(Btn("[ close ]", DimGreen) { onClose() }, modifier = Modifier.padding(top = 12.dp))
        } }
    }
}

@Composable
fun DeleteMessageConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.ScrimStrong).clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onCancel() },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).fillMaxWidth().panel(Pal.S2c, DangerRed.copy(alpha = 0.6f), Pal.R12).clickable(enabled = false) {}.padding(16.dp)) {
            Text("> delete this message? it is removed from the phone and can't be undone.", color = DangerRed, fontFamily = UiFont, fontSize = TypeScale.Body)
            ButtonRow(Btn("[ cancel ]", Green) { onCancel() }, Btn("[ delete ]", DangerRed, filled = true, confirm = true) { onConfirm() },
                modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
fun LinkPreviewLine(url: String) {
    val p by produceState<LinkPreview.Preview?>(null, url) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LinkPreview.fetch(url) } }
    p?.let { Text("  ⧉ ${it.title} — ${it.domain}", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp)) }
}

/** Pick when to send: presets or the system date + time pickers. Only that one message is scheduled. */
@Composable
fun ScheduleDialog(onClose: () -> Unit, onPick: (Long) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun at(daysAhead: Int, h: Int, m: Int) = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, daysAhead); set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, m); set(java.util.Calendar.SECOND, 0) }.timeInMillis
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text("> schedule send", color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
            Text("  sends this one message once, at the time you pick", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Spacer(Modifier.height(8.dp))
            val now = System.currentTimeMillis()
            ButtonRow(Btn("[ +1 hour ]", Green) { onPick(now + 3_600_000) }, Btn("[ tonight 9pm ]", Green) { onPick(at(if (at(0, 21, 0) > now + 60_000) 0 else 1, 21, 0)) })
            Spacer(Modifier.height(8.dp))
            ButtonRow(Btn("[ tmrw 8am ]", Green) { onPick(at(1, 8, 0)) }, Btn("[ pick... ]", Green) {
                val c = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(ctx, { _, y, mo, d ->
                    android.app.TimePickerDialog(ctx, { _, h, mi ->
                        onPick(java.util.Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }.timeInMillis)
                    }, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), false).show()
                }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH)).show()
            })
            ButtonRow(Btn("[ cancel ]", DimGreen) { onClose() }, modifier = Modifier.padding(top = 12.dp))
        } }
    }
}
