package com.example.emit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import com.example.emit.link.AppLock

@Composable
private fun Toggle(label: String, on: Boolean, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10)
    .semantics(mergeDescendants = true) { role = Role.Switch; stateDescription = if (on) "on" else "off" }.tap { f() }
    .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Text(label, color = if (on) Pal.Body else DimGreen, fontFamily = Mono, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
    Spacer(Modifier.width(10.dp)); Pill(on)
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(16.dp)); Text(title.uppercase(), color = FaintGreen, fontFamily = Mono, fontSize = TypeScale.Micro,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(start = 6.dp)); Spacer(Modifier.height(6.dp))
}

@Composable
private fun NavRow(label: String, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10).tap { f() }
    .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Text(label, color = Pal.Body, fontFamily = Mono, fontSize = TypeScale.Body, modifier = Modifier.weight(1f)); Text("›", color = DimGreen, fontFamily = Mono)
}

/** One place for every toggle. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLink: () -> Unit, onOpenAbout: () -> Unit) {
    val ctx = LocalContext.current
    val kb = LocalKb.current
    val store = remember { com.example.emit.link.LinkStore(ctx) }
    var crt by remember { mutableStateOf(UiPrefs.load(ctx)) }
    var prev by remember { mutableStateOf(UiPrefs.previewsOn(ctx)) }
    var pcApproval by remember { mutableStateOf(store.allowPcApproval) }
    var botOn by remember { mutableStateOf(com.example.emit.bot.BotCommands.enabled(ctx)) }
    var lockOn by remember { mutableStateOf(AppLock.enabled(ctx)) }
    var hasPin by remember { mutableStateOf(AppLock.hasPin(ctx)) }
    var pinDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Black)) {
        Header("root@emit:~$ settings", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Ui.ScreenPad)) {
            Section("keyboard")
            Toggle("autocorrect", kb.autocorrect) { kb.toggleAutocorrectTo(!kb.autocorrect) }
            Toggle("auto caps", kb.autocap) { kb.toggleAutocapTo(!kb.autocap) }
            Toggle("word suggestions", kb.showSuggestions) { kb.toggleSuggestionsTo(!kb.showSuggestions) }
            Section("display")
            Toggle("CRT scanlines + header glow", crt) { crt = !crt; UiPrefs.setCrt(ctx, crt) }
            val rain by RainPref.level.collectAsState()
            LaunchedEffect(Unit) { RainPref.load(ctx) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("background rain", color = Pal.Body, fontFamily = Mono, fontSize = TypeScale.Body)
                    Text("behind inbox and chats", color = FaintGreen, fontFamily = Mono, fontSize = TypeScale.Meta)
                }
                listOf("off", "subtle", "more").forEachIndexed { i, l ->
                    val on = (rain ?: 1) == i
                    Box(Modifier.semantics { role = Role.RadioButton; stateDescription = if (on) "selected" else "not selected"; contentDescription = "background rain $l" }
                        .tap { RainPref.set(ctx, i) }.padding(horizontal = 2.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Chip(l, on) }
                }
            }
            Toggle("link previews (fetch title + domain; off = nothing fetched)", prev) { prev = !prev; UiPrefs.setPreviews(ctx, prev) }
            Section("PC link & Prime")
            Toggle("PC app may approve sends", pcApproval) { pcApproval = !pcApproval; store.allowPcApproval = pcApproval }
            Toggle("@bot commands (your @bot messages go to Prime)", botOn) { botOn = !botOn; com.example.emit.bot.BotCommands.setEnabled(ctx, botOn) }
            NavRow("[ link PC / AWS relay / pairing ]", onOpenLink)
            Section("security")
            Toggle("app lock (fingerprint/face, or your phone PIN; off by default)", lockOn) { lockOn = !lockOn; AppLock.setEnabled(ctx, lockOn); if (lockOn) AppLockState.unlockedAt = System.currentTimeMillis() }
            Text("  never wipes data; your phone's own PIN/pattern always unlocks it", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta)
            Row {
                NavRow(if (hasPin) "[ change app PIN ]" else "[ set app PIN (optional) ]") { pinDialog = true }
            }
            if (hasPin) Text("[ remove app PIN ]", color = DangerRed, fontFamily = Mono, fontSize = TypeScale.Label,
                modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().panel(Pal.S1c, DangerRed.copy(alpha = 0.5f), Pal.R10).tap { AppLock.clearPin(ctx); hasPin = false }.padding(horizontal = 14.dp))
            Section("notifications")
            NavRow("[ system notification settings ]") {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Text("  mute single chats: long-press a chat > [x] mute", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta)
            Section("about")
            NavRow("[ about / changelog / licenses / crash log ]", onOpenAbout)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (pinDialog) PinDialog(onClose = { pinDialog = false }) { pin -> if (AppLock.setPin(ctx, pin)) { hasPin = true; pinDialog = false } }
}

@Composable
fun PinDialog(title: String = "> set app PIN (4-12 digits)", onClose: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text(title, color = Green, fontFamily = Mono, fontSize = TypeScale.Body)
            Spacer(Modifier.height(8.dp))
            PinField(pin) { pin = it }
            ButtonRow(Btn("[ cancel ]", DimGreen) { onClose() }, Btn("[ ok ]", Green, filled = true) { if (AppLock.validPin(pin)) onPin(pin) },
                modifier = Modifier.padding(top = 12.dp))
        } }
    }
}

@Composable
fun PinField(pin: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(pin, { v -> onChange(v.filter { it.isDigit() }.take(12)) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Green, fontFamily = Mono, fontSize = TypeScale.Title),
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation('*'),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Green), singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = Ui.Touch).border(1.dp, Green, Pal.R8).padding(12.dp))
}

object AppLockState { var unlockedAt = 0L; var backgroundedAt = 0L }

/** Full-screen lock. Biometric/device-credential prompt first; app PIN as fallback. */
@Composable
fun LockScreen(onPrompt: () -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onPrompt() }
    Column(Modifier.fillMaxSize().background(Black).padding(Ui.ScreenPad), verticalArrangement = Arrangement.Center) {
        Text(">_ emit locked", color = Green, fontFamily = Mono, fontSize = TypeScale.Title)
        Spacer(Modifier.height(16.dp))
        ButtonRow(Btn("[ unlock ]", Green, filled = true) { onPrompt() })
        if (AppLock.hasPin(ctx)) {
            Spacer(Modifier.height(16.dp)); Text("> or app PIN", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Label)
            PinField(pin) { pin = it; err = null }
            ButtonRow(Btn("[ enter ]", Green) { if (AppLock.checkPin(ctx, pin)) onUnlocked() else { err = "wrong PIN"; pin = "" } }, modifier = Modifier.padding(top = 8.dp))
        }
        err?.let { Text(it, color = DangerRed, fontFamily = Mono, fontSize = TypeScale.Label) }
        Text("  your phone's PIN/pattern also works from [ unlock ]", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta, modifier = Modifier.padding(top = 12.dp))
    }
}

object Changelog {
    val entries = listOf(
        "0.1.44" to "roomier compose box: full-width text field that grows to 5 lines, wraps on whole words, slightly smaller text; +, mic, timer and SEND moved to a compact row underneath",
        "0.1.43" to "voice to text: mic button in every compose bar (threads + Prime). Dictates into the box only - never auto-sends; edit, then tap SEND. Mic permission asked on first tap",
        "0.1.42" to "relay hardening: TLS 1.3 only, shorter log retention",
        "0.1.41" to "pinned Prime chat at the top of the inbox: talk to your assistant directly over the PC link (never SMS); replies show up as messages with a notification",
        "0.1.40" to "renamed to Emit (app name, headers, About, lock screen, notifications); same app id and signing key, so updates and data carry over",
        "0.1.39" to "chat header shows just the name (number moved to chat options / info); SMS or MMS · N chip",
        "0.1.38" to "real contact names everywhere (spaces and capitals kept), formatted numbers as fallback, groups as 'Mom, Devin, Jonathan'",
        "0.1.37" to "fix: code rain now actually shows inside chats (was hidden under the chat tint); stronger 'more' level; static rain with reduced motion",
        "0.1.36" to "multi-select: slim selection header with all/none, bulk pin, mute, archive and delete; same header for message selection; faint code rain behind inbox and chats (Settings > display: off / subtle / more)",
        "0.1.35" to "chat options sheet restyled (toggle cards, color swatches, menu rows, danger zone, swipe down to close); NEW button hides behind sheets; image/video viewer: slim top bar (back, sender + time, save), tap to hide, in-app save notice",
        "0.1.34" to "replies show the quoted message in its own small box above the bubble; copy/select/forward skip the quote",
        "0.1.33" to "reply strip matches the mockup, keyboard options as a sheet with toggles, new style across dialogs, sheets, menus, empty states and splash",
        "0.1.32" to "new look: rounded panels, chips, card inbox, floating input; chat colors are now subtle tints, your messages stay green",
        "0.1.31" to "settings screen, about + licenses, crash log, app lock, release build",
        "0.1.30" to "search in chat, pin/mute/archive, quick reply, scheduled send, link previews (off)",
        "0.1.29" to "message menu, swipe to reply, message info, skeleton loading, zoomable images, draft memory",
        "0.1.28" to "keyboard toggle row fix",
        "0.1.27" to "date dividers, grouping, delivery status + retry, input bar, splash, icon, accessibility",
        "0.1.26" to "JetBrains Mono, type scale, spacing grid, 48dp targets, contrast, haptics, reduced motion",
        "0.1.25" to "consistent message alignment",
        "0.1.24" to "dialog buttons, quieter link notification",
        "0.1.23" to "relay keepalive fix, clickable links",
    )
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var crash by remember { mutableStateOf(com.example.emit.CrashLog.read(ctx)) }
    var showOfl by remember { mutableStateOf(false) }
    val ofl = remember { runCatching { ctx.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().readText() }.getOrDefault("(license file missing)") }
    Column(Modifier.fillMaxSize().background(Black)) {
        Header("root@emit:~$ about", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Ui.ScreenPad)) {
            Spacer(Modifier.height(12.dp))
            Text(">_ Emit v${com.example.emit.BuildConfig.VERSION_NAME} (${com.example.emit.BuildConfig.VERSION_CODE}) ${com.example.emit.BuildConfig.BUILD_TYPE}",
                color = Green, fontFamily = Mono, fontSize = TypeScale.Body)
            Section("changelog")
            Changelog.entries.forEach { (v, t) -> Text("$v  $t", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta, modifier = Modifier.padding(vertical = 2.dp)) }
            Section("licenses")
            Text("JetBrains Mono — SIL Open Font License 1.1", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Label)
            Text(if (showOfl) "[ hide license ]" else "[ show license ]", color = Green, fontFamily = Mono, fontSize = TypeScale.Label, modifier = Modifier.tap { showOfl = !showOfl })
            if (showOfl) Text(ofl, color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta)
            Section("crash log (on this phone only)")
            if (crash.isBlank()) Text("  no crashes recorded", color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta)
            else {
                Text(crash.takeLast(6000), color = DimGreen, fontFamily = Mono, fontSize = TypeScale.Meta,
                    modifier = Modifier.fillMaxWidth().border(1.dp, FaintGreen, Pal.R8).padding(8.dp))
                ButtonRow(Btn("[ export ]", Green) {
                    val f = java.io.File(ctx.cacheDir, "logs/emit-crash.txt").apply { parentFile?.mkdirs(); writeText(crash) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, com.example.emit.mms.MmsTransport.AUTHORITY, f)
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "export crash log")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Btn("[ clear ]", DangerRed) { com.example.emit.CrashLog.clear(ctx); crash = "" }, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
