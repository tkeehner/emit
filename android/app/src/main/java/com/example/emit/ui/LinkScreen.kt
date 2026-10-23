package com.example.emit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emit.link.LinkService
import com.example.emit.link.LinkState
import com.example.emit.link.LinkStore
import kotlinx.coroutines.delay
import java.text.DateFormat

/** Settings > Link PC: enable, pair (6-digit code + ALLOW), list/revoke PCs, PC-approval toggle. */
@Composable
fun LinkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { LinkStore(ctx) }
    var enabled by remember { mutableStateOf(store.enabled) }
    var pcApproval by remember { mutableStateOf(store.allowPcApproval) }
    var pcs by remember { mutableStateOf(store.pcs()) }
    val running by LinkState.running.collectAsState()
    val addr by LinkState.address.collectAsState()
    val pairing by LinkState.pairing.collectAsState()
    val connected by LinkState.connected.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing) { while (pairing != null) { now = System.currentTimeMillis(); delay(1000)
        if (pairing?.let { it.result == null && !it.awaitingAllow && now > it.until } == true) LinkState.closePairing() } }
    LaunchedEffect(pairing?.result) { pcs = store.pcs() }
    LaunchedEffect(Unit) { if (enabled && !running) LinkService.start(ctx) }
    @Composable fun T(s: String, c: Color = Green, size: Int = 14, m: Modifier = Modifier) = Text(s, color = c, fontFamily = Mono, fontSize = TypeScale.snap(size), modifier = m, softWrap = !(s.startsWith("[") && s.length < 24), maxLines = if (s.startsWith("[") && s.length < 24) 1 else Int.MAX_VALUE)
    @Composable fun Btn(s: String, c: Color = Green, f: () -> Unit) = Text(s, color = c, fontFamily = Mono, fontSize = TypeScale.Body,
        modifier = Modifier.padding(vertical = 8.dp).border(1.dp, c, Pal.R8).tap { f() }.padding(horizontal = 12.dp, vertical = 12.dp))
    Column(Modifier.fillMaxSize().background(Black)) {
    com.example.emit.ui.Header("root@emit:~$ link_pc", onBack)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Ui.ScreenPad)) {
        Spacer(Modifier.height(12.dp))
        T("[${if (enabled) "x" else " "}] link enabled (LAN, end-to-end encrypted)", if (enabled) Green else DimGreen, 15, Modifier.tap {
            enabled = !enabled; store.enabled = enabled; if (enabled) LinkService.start(ctx) else { LinkState.closePairing(); LinkService.stop(ctx) } }.padding(vertical = 8.dp))
        T("[${if (pcApproval) "x" else " "}] PC app may approve sends (otherwise approve only here on the phone)", if (pcApproval) Green else DimGreen, 13,
            Modifier.tap { pcApproval = !pcApproval; store.allowPcApproval = pcApproval }.padding(vertical = 8.dp))
        var botOn by remember { mutableStateOf(com.example.emit.bot.BotCommands.enabled(ctx)) }
        T("[${if (botOn) "x" else " "}] @bot commands (your messages starting with @bot are also sent to your PC / Prime)", if (botOn) Green else DimGreen, 13,
            Modifier.tap { botOn = !botOn; com.example.emit.bot.BotCommands.setEnabled(ctx, botOn) }.padding(vertical = 8.dp))
        val keyFp = remember { java.security.MessageDigest.getInstance("SHA-256").digest(com.example.emit.link.LinkCrypto.publicKey(store.secretKey())).take(8).joinToString("") { "%02x".format(it) } }
        T("this phone's key: $keyFp (msms verify shows the same)", DimGreen, 11)
        T(if (running) "status: listening on ${addr ?: "(no Wi-Fi address)"}" else "status: off", DimGreen, 12)
        if (connected.isNotEmpty()) T("connected now: ${connected.size} PC session(s)", Green, 12)
        Spacer(Modifier.height(16.dp))
        val p = pairing
        if (enabled && running) {
            if (p == null) Btn("[ PAIR NEW PC ]") { LinkState.openPairing() }
            else Column(Modifier.fillMaxWidth().border(1.dp, Amber, Pal.R8).padding(Ui.CardPad)) {
                when {
                    p.result != null -> { T(p.result, Green); Btn("[ done ]") { LinkState.closePairing() } }
                    p.awaitingAllow -> {
                        T("PC \"${p.pcName}\" entered code ${p.code}.", Amber)
                        T("Only allow if the PC said the code matched.", DimGreen, 12)
                        ButtonRow(com.example.emit.ui.Btn("[ ALLOW ]", Green, filled = true) { LinkState.answer(true) },
                            com.example.emit.ui.Btn("[ deny ]", DangerRed) { LinkState.answer(false) }, fontSp = 15)
                    }
                    p.code != null -> { T("type this code on the PC (${p.pcName}):", DimGreen, 12); T(p.code.chunked(3).joinToString(" "), Green, 40) }
                    else -> {
                        T("pairing open ${((p.until - now) / 1000).coerceAtLeast(0)}s", Amber)
                        T("On the PC run:  msms pair --host ${addr ?: "<phone-ip>"}", Green, 13)
                    }
                }
                if (p.result == null && !p.awaitingAllow) Btn("[ cancel ]", DimGreen) { LinkState.closePairing() }
            }
        } else if (enabled) T("starting...", DimGreen, 12)
        Spacer(Modifier.height(20.dp))
        T("> paired PCs", DimGreen, 13)
        if (pcs.isEmpty()) T("none", FaintGreen, 12)
        pcs.forEach { pc ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    T(pc.name + if (pc.pcId in connected) "  [online]" else "", Green, 14)
                    T("key ${java.security.MessageDigest.getInstance("SHA-256").digest(pc.pub).take(8).joinToString("") { "%02x".format(it) }} · id ${pc.pcId} · last seen ${if (pc.lastSeen > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(pc.lastSeen) else "never"}", DimGreen, 11)
                }
                Spacer(Modifier.width(Ui.Gap)); T("[ revoke ]", DangerRed, 13, Modifier.tap { store.revoke(pc.pcId); pcs = store.pcs() }.padding(8.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        RelaySection(store)
        Spacer(Modifier.height(20.dp))
        T("The PC can read chats and create drafts. It can never send: every send needs your APPROVE (here, in the notification, or in the PC app if allowed above).", FaintGreen, 11)
    }
    }
}

/** Relay (AWS) settings. Normally filled by `msms relay push` from the PC over the encrypted LAN link. */
@Composable
fun RelaySection(store: LinkStore) {
    val status by LinkState.relay.collectAsState()
    var on by remember { mutableStateOf(store.relayEnabled) }
    var host by remember { mutableStateOf(store.relayHost) }
    var port by remember { mutableStateOf(store.relayPort.toString()) }
    var fp by remember { mutableStateOf(store.relayFingerprint) }
    var tok by remember { mutableStateOf(store.relayToken) }
    var editing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val st = androidx.compose.ui.text.TextStyle(color = Green, fontFamily = Mono, fontSize = TypeScale.Label)
    @Composable fun T(s: String, c: Color = Green, size: Int = 13, m: Modifier = Modifier) = Text(s, color = c, fontFamily = Mono, fontSize = TypeScale.snap(size), modifier = m, softWrap = !(s.startsWith("[") && s.length < 24), maxLines = if (s.startsWith("[") && s.length < 24) 1 else Int.MAX_VALUE)
    @Composable fun F(label: String, v: String, secret: Boolean = false, set: (String) -> Unit) {
        T(label, DimGreen, 11)
        androidx.compose.foundation.text.BasicTextField(v, set, textStyle = st, singleLine = true,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Green),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().border(1.dp, FaintGreen, Pal.R8).padding(8.dp))
    }
    LaunchedEffect(Unit) { while (true) { delay(2000); host = store.relayHost.ifEmpty { host }; if (!editing) { on = store.relayEnabled; fp = store.relayFingerprint; tok = store.relayToken; port = store.relayPort.toString() } } }
    Column(Modifier.fillMaxWidth().border(1.dp, FaintGreen, Pal.R8).padding(Ui.CardPad)) {
        T("> AWS relay (away from home)", DimGreen, 13)
        T("[${if (on) "x" else " "}] use relay when not on home Wi-Fi", if (on) Green else DimGreen, 14, Modifier.tap {
            on = !on; store.relayEnabled = on; com.example.emit.link.RelayConnector.kick() }.padding(vertical = 8.dp))
        T("status: $status", if (status.startsWith("PC connected")) Green else DimGreen, 12)
        T(if (store.relayConfigured) "relay: ${store.relayHost}:${store.relayPort} · cert ${store.relayFingerprint.take(16)}… · token set" else "relay: not set. On the PC (home Wi-Fi) run: msms relay push", DimGreen, 11)
        if (!editing) T("[ edit manually ]", DimGreen, 12, Modifier.tap { editing = true }.padding(vertical = 8.dp))
        else {
            F("host", host) { host = it }; F("port", port) { port = it.filter(Char::isDigit).take(5) }
            F("cert sha-256 fingerprint", fp) { fp = it }; F("token", tok, secret = true) { tok = it }
            err?.let { T(it, DangerRed, 11) }
            Row(horizontalArrangement = Arrangement.spacedBy(Ui.Gap)) {
                T("[ save ]", Green, 13, Modifier.border(1.dp, Green, Pal.R8).tap {
                    val p = port.toIntOrNull() ?: 0
                    if (host.isBlank() || p !in 1..65535 || !com.example.emit.link.RelayWire.validFingerprint(fp) || tok.trim().length < 16) err = "check host / port / 64-hex fingerprint / token"
                    else { store.relayHost = host; store.relayPort = p; store.relayFingerprint = fp; store.relayToken = tok; err = null; editing = false
                        com.example.emit.link.RelayConnector.kick() } }.padding(8.dp))
                T("[ cancel ]", DimGreen, 13, Modifier.border(1.dp, DimGreen, Pal.R8).tap { editing = false }.padding(8.dp))
            }
        }
        T("Uses no location permission: home = a Wi-Fi network where your PC connected directly. The relay only passes encrypted frames.", FaintGreen, 10)
    }
}
