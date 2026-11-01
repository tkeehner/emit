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
private fun Toggle(label: String, on: Boolean, subtitle: String? = null, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10)
    .semantics(mergeDescendants = true) { role = Role.Switch; stateDescription = if (on) "on" else "off" }.tap { f() }
    .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    if (subtitle == null) Text(label, color = if (on) Pal.Body else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Body, modifier = Modifier.weight(1f))
    else Column(Modifier.weight(1f)) {
        Text(label, color = if (on) Pal.Body else DimGreen, fontFamily = UiFont, fontSize = TypeScale.Body)
        Text(subtitle, color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
    }
    Spacer(Modifier.width(10.dp)); Pill(on)
}

@Composable
private fun Section(title: String) {
    val slash = LocalTheme.current.chrome.sectionSlash
    Spacer(Modifier.height(16.dp)); Text((if (slash) "// " else "") + title.uppercase(), color = if (slash) Pal.Cyan else FaintGreen, fontFamily = DisplayFont, fontSize = TypeScale.Micro,
        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(start = 6.dp)); Spacer(Modifier.height(6.dp))
}

@Composable
private fun NavRow(label: String, f: () -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10).tap { f() }
    .padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Text(label, color = Pal.Body, fontFamily = UiFont, fontSize = TypeScale.Body, modifier = Modifier.weight(1f)); Text("›", color = DimGreen, fontFamily = UiFont)
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
        Header(LocalTheme.current.chrome.settings, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Ui.ScreenPad)) {
            Section("theme")
            ThemePicker()
            Section("keyboard")
            Toggle("autocorrect", kb.autocorrect) { kb.toggleAutocorrectTo(!kb.autocorrect) }
            Toggle("auto caps", kb.autocap) { kb.toggleAutocapTo(!kb.autocap) }
            Toggle("word suggestions", kb.showSuggestions) { kb.toggleSuggestionsTo(!kb.showSuggestions) }
            Section("effects")
            val rain by RainPref.level.collectAsState()
            LaunchedEffect(Unit) { RainPref.load(ctx) }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).panel(Pal.S1c, Pal.Line, Pal.R10).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(LocalTheme.current.chrome.rainLabel, color = Pal.Body, fontFamily = UiFont, fontSize = TypeScale.Body)
                    Text("behind inbox and chats", color = FaintGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
                }
                listOf("off", "subtle", "more").forEachIndexed { i, l ->
                    val on = (rain ?: 1) == i
                    Box(Modifier.semantics { role = Role.RadioButton; stateDescription = if (on) "selected" else "not selected"; contentDescription = "background rain $l" }
                        .tap { RainPref.set(ctx, i) }.padding(horizontal = 2.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Chip(l, on) }
                }
            }
            var glitch by remember { mutableStateOf(EffectPrefs.glitchOn(ctx)) }
            var keyGlow by remember { mutableStateOf(EffectPrefs.keyGlowOn(ctx)) }
            Toggle("glitch effects", glitch, "screen-change glitch, avatar flicker") { glitch = !glitch; EffectPrefs.setGlitch(ctx, glitch) }
            Toggle("scanlines + glow", crt, "CRT lines and title glow") { crt = !crt; UiPrefs.setCrt(ctx, crt) }
            Toggle("key press glow", keyGlow, "glow around a pressed key") { keyGlow = !keyGlow; EffectPrefs.setKeyGlow(ctx, keyGlow) }
            Toggle("link previews (fetch title + domain; off = nothing fetched)", prev) { prev = !prev; UiPrefs.setPreviews(ctx, prev) }
            Section("PC link & Prime")
            Toggle("PC app may approve sends", pcApproval) { pcApproval = !pcApproval; store.allowPcApproval = pcApproval }
            Toggle("@bot commands (your @bot messages go to Prime)", botOn) { botOn = !botOn; com.example.emit.bot.BotCommands.setEnabled(ctx, botOn) }
            NavRow("[ link PC / AWS relay / pairing ]", onOpenLink)
            Section("security")
            Toggle("app lock (fingerprint/face, or your phone PIN; off by default)", lockOn) { lockOn = !lockOn; AppLock.setEnabled(ctx, lockOn); if (lockOn) AppLockState.unlockedAt = System.currentTimeMillis() }
            Text("  never wipes data; your phone's own PIN/pattern always unlocks it", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Row {
                NavRow(if (hasPin) "[ change app PIN ]" else "[ set app PIN (optional) ]") { pinDialog = true }
            }
            if (hasPin) Text("[ remove app PIN ]", color = DangerRed, fontFamily = UiFont, fontSize = TypeScale.Label,
                modifier = Modifier.padding(vertical = 3.dp).fillMaxWidth().panel(Pal.S1c, DangerRed.copy(alpha = 0.5f), Pal.R10).tap { AppLock.clearPin(ctx); hasPin = false }.padding(horizontal = 14.dp))
            Section("notifications")
            NavRow("[ system notification settings ]") {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Text("  mute single chats: long-press a chat > [x] mute", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Section("about")
            NavRow("[ about / changelog / licenses / crash log ]", onOpenAbout)
            Spacer(Modifier.height(32.dp))
        }
    }
    if (pinDialog) PinDialog(onClose = { pinDialog = false }) { pin -> if (AppLock.setPin(ctx, pin)) { hasPin = true; pinDialog = false } }
}

/** Live theme choice. The card previews that theme's type, corners, and swatches. */
@Composable
private fun ThemePicker() {
    val ctx = LocalContext.current
    val saved = remember { ThemePref.load(ctx) }
    val current by ThemePref.id.collectAsState()
    val active = Themes.byId(current ?: saved)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Themes.all.forEach { theme ->
            val on = theme.id == active.id
            val shape = theme.shape(12.dp)
            Column(Modifier.fillMaxWidth()
                .border(if (on) 2.dp else 1.dp, if (on) Pal.Primary else Pal.Line2, shape)
                .background(androidx.compose.ui.graphics.Color(theme.palette.bg), shape)
                .semantics { contentDescription = theme.name + if (on) ", active" else ", tap to use" }
                .tap { ThemePref.set(ctx, theme.id) }
                .padding(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(if (theme.chrome.uppercaseNames) theme.name.uppercase() else theme.name,
                        color = androidx.compose.ui.graphics.Color(theme.palette.primary), fontFamily = theme.displayFont.family(),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = TypeScale.Body,
                        letterSpacing = if (theme.displayFont == FontId.OXANIUM) androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp) else androidx.compose.ui.unit.TextUnit.Unspecified,
                        modifier = Modifier.weight(1f))
                    if (on) Text("ACTIVE", color = androidx.compose.ui.graphics.Color(theme.palette.onPrimary), fontFamily = UiFont, fontSize = TypeScale.Micro,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.background(androidx.compose.ui.graphics.Color(theme.palette.primary), LocalTheme.current.shape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    else Text("tap to use", color = Pal.Faint, fontFamily = UiFont, fontSize = TypeScale.Meta)
                }
                Text(theme.blurb, color = androidx.compose.ui.graphics.Color(theme.palette.dim), fontFamily = UiFont, fontSize = TypeScale.Meta,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth().height(22.dp).border(1.dp, androidx.compose.ui.graphics.Color(theme.palette.line2))) {
                    theme.swatches.forEach { sw -> Box(Modifier.weight(1f).fillMaxHeight().background(androidx.compose.ui.graphics.Color(sw))) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    "qwe".forEachIndexed { j, ch ->
                        val pressed = j == 1
                        val keyShape = theme.shape(4.dp)
                        val neon = theme.keyStyle != KeyStyle.MATRIX
                        Box(Modifier.size(24.dp, 26.dp)
                            .background(when {
                                pressed && neon -> androidx.compose.ui.graphics.Color(theme.palette.primary)
                                pressed -> androidx.compose.ui.graphics.Color(theme.palette.primary).copy(alpha = 0.35f)
                                neon -> androidx.compose.ui.graphics.Color(theme.palette.s2)
                                else -> androidx.compose.ui.graphics.Color(theme.palette.bg)
                            }, keyShape)
                            .border(1.dp, androidx.compose.ui.graphics.Color(if (pressed) theme.palette.primary else if (neon) theme.palette.line2 else theme.palette.dim), keyShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text(ch.toString(), color = when {
                                pressed && neon -> androidx.compose.ui.graphics.Color(theme.palette.onPrimary)
                                pressed && !neon -> androidx.compose.ui.graphics.Color.White
                                else -> androidx.compose.ui.graphics.Color(theme.palette.primary)
                            }, fontFamily = theme.keyFont.family(), fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PinDialog(title: String = "> set app PIN (4-12 digits)", onClose: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogEnter { Column(Modifier.padding(horizontal = 20.dp).dialogBox(Green)) {
            Text(title, color = Green, fontFamily = UiFont, fontSize = TypeScale.Body)
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
        textStyle = androidx.compose.ui.text.TextStyle(color = Green, fontFamily = UiFont, fontSize = TypeScale.Title),
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
        Text(">_ emit locked", color = Green, fontFamily = UiFont, fontSize = TypeScale.Title)
        Spacer(Modifier.height(16.dp))
        ButtonRow(Btn("[ unlock ]", Green, filled = true) { onPrompt() })
        if (AppLock.hasPin(ctx)) {
            Spacer(Modifier.height(16.dp)); Text("> or app PIN", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label)
            PinField(pin) { pin = it; err = null }
            ButtonRow(Btn("[ enter ]", Green) { if (AppLock.checkPin(ctx, pin)) onUnlocked() else { err = "wrong PIN"; pin = "" } }, modifier = Modifier.padding(top = 8.dp))
        }
        err?.let { Text(it, color = DangerRed, fontFamily = UiFont, fontSize = TypeScale.Label) }
        Text("  your phone's PIN/pattern also works from [ unlock ]", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, modifier = Modifier.padding(top = 12.dp))
    }
}

object Changelog {
    val entries = listOf(
        "0.1.50" to "themes: new Holiday theme (candle-lit night, drifting bats, cobwebs, candles, tombstones and skulls, a shaded holiday character for every contact). Emit and Beacon now switch themes together.",
        "0.1.49" to "themes: new ODST theme (rainy night city, amber + steel comms panels with callsign headers, hex callsign badges, VISR outlines, armor-plate keys, a TRANSMISSION SENT squelch on send; the city switches to VISR outlines after 7 pm). Matrix stays the default",
        "0.1.48" to "Prime Link: Prime can stage a text to someone you haven't texted before (new thread). It still waits for your APPROVE tap",
        "0.1.47" to "contact names in the inbox, chat header and message labels now light up in each chat's chosen color, like Prime's name (default chats use the theme color)",
        "0.1.46" to "themes: new Neon City theme (neon yellow + cyan, angled panels, Oxanium titles/keys, glyph badges per contact) in settings > theme; Matrix stays the default. New glitch and key-glow switches",
        "0.1.45" to "fix: tapping inside the message box now puts the green cursor where you tap (typing, backspace and voice insert there)",
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
    var showOx by remember { mutableStateOf(false) }
    val ofl = remember { runCatching { ctx.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().readText() }.getOrDefault("(license file missing)") }
    val oxanium = remember { runCatching { ctx.assets.open("licenses/Oxanium-OFL.txt").bufferedReader().readText() }.getOrDefault("(license file missing)") }
    Column(Modifier.fillMaxSize().background(Black)) {
        Header(LocalTheme.current.chrome.about, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Ui.ScreenPad)) {
            Spacer(Modifier.height(12.dp))
            Text(">_ Emit v${com.example.emit.BuildConfig.VERSION_NAME} (${com.example.emit.BuildConfig.VERSION_CODE}) ${com.example.emit.BuildConfig.BUILD_TYPE}",
                color = Green, fontFamily = UiFont, fontSize = TypeScale.Body)
            Section("changelog")
            Changelog.entries.forEach { (v, t) -> Text("$v  $t", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta, modifier = Modifier.padding(vertical = 2.dp)) }
            Section("licenses")
            Text("JetBrains Mono — SIL Open Font License 1.1", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label)
            Text(if (showOfl) "[ hide license ]" else "[ show license ]", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.tap { showOfl = !showOfl })
            if (showOfl) Text(ofl, color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Spacer(Modifier.height(8.dp))
            Text("Oxanium — SIL Open Font License 1.1", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Label)
            Text(if (showOx) "[ hide license ]" else "[ show license ]", color = Green, fontFamily = UiFont, fontSize = TypeScale.Label, modifier = Modifier.tap { showOx = !showOx })
            if (showOx) Text(oxanium, color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            Section("crash log (on this phone only)")
            if (crash.isBlank()) Text("  no crashes recorded", color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta)
            else {
                Text(crash.takeLast(6000), color = DimGreen, fontFamily = UiFont, fontSize = TypeScale.Meta,
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
