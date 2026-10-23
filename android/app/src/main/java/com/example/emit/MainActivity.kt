package com.example.emit

import androidx.compose.foundation.border
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.example.emit.data.*
import com.example.emit.drafts.PrimeDraftStore
import com.example.emit.sms.IncomingBus
import com.example.emit.sms.Notifier
import com.example.emit.sms.SmsSender
import com.example.emit.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

sealed interface Screen {
    data object Inbox : Screen
    data class Thread(val threadId: Long) : Screen
    data class Compose(val address: String, val body: String = "") : Screen
    data object Link : Screen
    data object Settings : Screen
    data object About : Screen
    data object Prime : Screen
}

/** Pure (unit-tested): clear unread for [threadId] in the inbox list. */
fun markReadLocally(list: List<Conversation>, threadId: Long?): List<Conversation> =
    if (threadId == null) list else list.map { if (it.threadId == threadId && it.unread) it.copy(unread = false) else it }

/** Cached messages for one thread, oldest first. */
data class ThreadCache(val threadId: Long, val messages: List<Message>, val hasMore: Boolean, val oldestDate: Long)

class MainActivity : androidx.fragment.app.FragmentActivity() {
    companion object { const val EXTRA_THREAD = "thread_id"; const val EXTRA_LINK = "open_link"; const val EXTRA_PRIME = "open_prime" }

    private var screen by mutableStateOf<Screen>(Screen.Inbox)
    private var permsVersion by mutableIntStateOf(0)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsVersion++ }
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permsVersion++; requestPerms()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.core.splashscreen.SplashScreen.Companion.run { installSplashScreen() }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val cold = savedInstanceState == null
        Notifier.ensureChannel(this)
        handleIntent(intent)
        val rm = getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
            roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else requestPerms()
        setContent { com.example.emit.ui.CrtHost(boot = cold) {
            var locked by remember { mutableStateOf(com.example.emit.link.AppLock.shouldLock(com.example.emit.link.AppLock.enabled(this),
                AppLockState.unlockedAt, AppLockState.backgroundedAt, System.currentTimeMillis())) }
            lockGate = { locked = it }
            if (locked) LockScreen(onPrompt = { promptUnlock { locked = false } }) { AppLockState.unlockedAt = System.currentTimeMillis(); AppLockState.backgroundedAt = 0; locked = false }
            else App()
        } }
    }

    private var lockGate: (Boolean) -> Unit = {}
    override fun onStop() { super.onStop(); com.example.emit.prime.PrimeChat.foreground = false; AppLockState.backgroundedAt = System.currentTimeMillis() }
    override fun onStart() {
        com.example.emit.prime.PrimeChat.foreground = true
        super.onStart()
        if (com.example.emit.link.AppLock.shouldLock(com.example.emit.link.AppLock.enabled(this), AppLockState.unlockedAt, AppLockState.backgroundedAt, System.currentTimeMillis())) lockGate(true)
        else AppLockState.backgroundedAt = 0
    }

    /** Biometric OR device PIN/pattern (always allowed, so the lock can never strand the user). */
    private fun promptUnlock(onOk: () -> Unit) {
        val done = { AppLockState.unlockedAt = System.currentTimeMillis(); AppLockState.backgroundedAt = 0; onOk() }
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) {
            // No biometrics and no device lock set up: nothing to verify against, so don't block the user.
            if (!com.example.emit.link.AppLock.hasPin(this)) done(); return
        }
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Emit")
            .setSubtitle("fingerprint, face, or your phone PIN").setAllowedAuthenticators(auth).build()
        androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { done() }
            }).authenticate(info)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun requestPerms() = permLauncher.launch(arrayOf(
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_NUMBERS))

    private fun handleIntent(i: Intent?) {
        i ?: return
        val tid = i.getLongExtra(EXTRA_THREAD, -1)
        if (tid > 0) { screen = Screen.Thread(tid); return }
        if (i?.getBooleanExtra(EXTRA_LINK, false) == true) { screen = Screen.Link; return }
        if (i.getBooleanExtra(EXTRA_PRIME, false)) { screen = Screen.Prime; return }
        if (i.action == Intent.ACTION_SENDTO || i.action == Intent.ACTION_SEND) {
            val addr = i.data?.schemeSpecificPart?.substringBefore('?')?.let(Uri::decode) ?: ""
            screen = Screen.Compose(addr)
        }
    }

    private fun roleHeld(): Boolean = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS)

    private fun isDefault() = Telephony.Sms.getDefaultSmsPackage(this) == packageName

    @Composable
    private fun App() {
        val repo = remember { SmsRepository(this) }
        val drafts = remember { PrimeDraftStore(this) }
        val scope = rememberCoroutineScope()
        var convos by remember { mutableStateOf(emptyList<Conversation>()) }
        // Per-thread cache keyed by threadId; a thread's screen only ever reads its own entry.
        val cache = remember { androidx.compose.runtime.snapshots.SnapshotStateMap<Long, ThreadCache>() }
        var loadingOlder by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(emptySet<Long>()) }
        var inboxNotice by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf(emptyList<com.example.emit.drafts.PrimeDraft>()) }
        var refresh by remember { mutableIntStateOf(0) }
        var glitch by remember { mutableStateOf(false) }
        val flash = remember { Animatable(0f) }
        val colorStore = remember { ChatColorStore(this) }
        val groupNames = remember { GroupNameStore(this) }
        var chatColors by remember { mutableStateOf(colorStore.all()) }
        val jitter = remember { Animatable(0f) }

        LaunchedEffect(Unit) { IncomingBus.events.collect { refresh++; flash.snapTo(0.35f); flash.animateTo(0f, tween(450)) } }
        // Glitch transition on every screen change.
        LaunchedEffect(screen) {
            glitch = true
            repeat(8) { jitter.snapTo((Random.nextFloat() - 0.5f) * 24f); delay(40) }
            jitter.snapTo(0f); glitch = false
        }
        // Inbox list: on start, on refresh, and when returning to the inbox (not on every thread open).
        LaunchedEffect(refresh, permsVersion, screen == Screen.Inbox) {
            val c = withContext(Dispatchers.IO) { repo.conversations() }
            val p = withContext(Dispatchers.IO) { runCatching { drafts.pending() }.getOrDefault(emptyList()) }
            // Never show the open thread as unread (it's being marked read).
            val open = (screen as? Screen.Thread)?.threadId
            val labels = groupNames.all()
            convos = markReadLocally(c, open).map { if (it.isGroup) it.copy(label = labels[it.threadId]) else it }; pending = p
        }
        // Thread: newest page first (cached copy shows instantly, then refreshes).
        LaunchedEffect(screen, refresh) {
            val s = screen as? Screen.Thread ?: return@LaunchedEffect
            // Optimistic: clear the dot + notification now, persist read/seen in the background.
            convos = markReadLocally(convos, s.threadId)
            Notifier.cancelThread(this@MainActivity, s.threadId)
            launch(Dispatchers.IO) { if (isDefault()) repo.markRead(s.threadId) }
            val page = withContext(Dispatchers.IO) {
                repo.messagesPage(s.threadId)
            }
            if (page.messages.all { it.threadId == s.threadId }) {
                val prev = cache[s.threadId]
                // Keep already-loaded older pages, replace the newest window.
                val older = prev?.messages?.filter { it.date < (page.messages.firstOrNull()?.date ?: Long.MAX_VALUE) } ?: emptyList()
                cache[s.threadId] = ThreadCache(s.threadId, older + page.messages,
                    if (older.isNotEmpty()) prev!!.hasMore else page.hasMore,
                    if (older.isNotEmpty()) prev!!.oldestDate else page.oldestDate)
            }
        }
        fun loadOlder(threadId: Long) {
            val cur = cache[threadId] ?: return
            if (loadingOlder || !cur.hasMore) return
            loadingOlder = true
            scope.launch {
                val page = withContext(Dispatchers.IO) { repo.messagesPage(threadId, cur.oldestDate) }
                val now = cache[threadId]
                if (now != null && page.messages.all { it.threadId == threadId }) {
                    val keys = now.messages.map { it.key }.toHashSet()
                    cache[threadId] = now.copy(messages = page.messages.filter { it.key !in keys } + now.messages,
                        hasMore = page.hasMore, oldestDate = if (page.messages.isEmpty()) now.oldestDate else page.oldestDate)
                }
                loadingOlder = false
            }
        }

        fun deleteThread(tid: Long) {
            // Without the SMS role the provider silently deletes nothing - never pretend it worked.
            if (!roleHeld()) { inboxNotice = "[!] set Emit as default SMS app to delete"; return }
            inboxNotice = null
            deleting = deleting + tid
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val started = System.currentTimeMillis()
                    val r = repo.deleteThread(tid)
                    val wait = 450 - (System.currentTimeMillis() - started); if (wait > 0) delay(wait)
                    if (r) { runCatching { drafts.deleteForThread(tid) }; colorStore.reset(tid); groupNames.set(tid, null) }
                    r
                }
                if (ok) { cache.remove(tid); chatColors = colorStore.all(); convos = convos.filter { it.threadId != tid } }
                else inboxNotice = "[X] delete failed - thread still on phone"
                deleting = deleting - tid
                refresh++
            }
        }

        fun deleteMany(ids: List<Long>) {
            if (ids.isEmpty()) return
            if (!roleHeld()) { inboxNotice = "[!] set Emit as default SMS app to delete"; return }
            inboxNotice = null
            deleting = deleting + ids
            scope.launch {
                val (ok, bad) = BulkSelect.runAll(ids, onProgress = { d, t -> withContext(Dispatchers.Main) { inboxNotice = if (d < t) BulkSelect.progress(d + 1, t) else null } }) { tid ->
                    val r = withContext(Dispatchers.IO) {
                        val r = repo.deleteThread(tid)
                        if (r) { runCatching { drafts.deleteForThread(tid) }; runCatching { colorStore.reset(tid) }; runCatching { groupNames.set(tid, null) } }
                        r
                    }
                    if (r) { cache.remove(tid); convos = convos.filter { it.threadId != tid } }
                    deleting = deleting - tid
                    r
                }
                chatColors = colorStore.all()
                deleting = deleting - ids.toSet()
                inboxNotice = if (bad.isEmpty()) null else BulkSelect.result(ok.size, bad.size)
                android.widget.Toast.makeText(this@MainActivity, BulkSelect.result(ok.size, bad.size), android.widget.Toast.LENGTH_SHORT).show()
                refresh++
            }
        }

        fun go(s: Screen) { notice = null; screen = s }
        fun sendInThread(threadId: Long, shownAddress: String, body: String,
                         atts: List<com.example.emit.mms.Attachment> = emptyList(), onSent: () -> Unit = {}) = scope.launch(Dispatchers.IO) {
            val r = if (atts.isEmpty()) SmsSender.sendToThread(this@MainActivity, threadId, shownAddress, body)
                    else SmsSender.sendMmsToThread(this@MainActivity, threadId, shownAddress, body, atts)
            withContext(Dispatchers.Main) {
                when (r) { SmsSender.Result.Sent -> { notice = null; onSent() }
                    is SmsSender.Result.Refused -> notice = "[X] not sent: ${r.reason}" }
                refresh++
            }
        }
        fun sendGroup(threadId: Long, shown: List<String>, body: String,
                      atts: List<com.example.emit.mms.Attachment>, onSent: () -> Unit) = scope.launch(Dispatchers.IO) {
            val r = SmsSender.sendGroupToThread(this@MainActivity, threadId, shown, body, atts)
            withContext(Dispatchers.Main) {
                when (r) { SmsSender.Result.Sent -> { notice = null; onSent() }
                    is SmsSender.Result.Refused -> notice = "[X] not sent: ${r.reason}" }
                refresh++
            }
        }
        fun sendNew(recipients: List<String>, body: String) = scope.launch(Dispatchers.IO) {
            val tid = if (recipients.size > 1) SmsSender.sendNewGroup(this@MainActivity, recipients, body)
                      else SmsSender.sendNew(this@MainActivity, recipients.first(), body)
            withContext(Dispatchers.Main) { if (tid != null) screen = Screen.Thread(tid); refresh++ }
        }

        val kb = remember { KbController() }
        LaunchedEffect(Unit) { kb.init(this@MainActivity) { repo.allContactNames() } }
        LaunchedEffect(screen) { kb.hide() }
        CompositionLocalProvider(LocalKb provides kb) {
        Box(Modifier.fillMaxSize().background(Black).statusBarsPadding().navigationBarsPadding().imePadding()) {
          Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).graphicsLayer { translationX = jitter.value }) {
                when (val s = screen) {
                    Screen.Inbox -> InboxScreen(convos, pending.size, chatColors,
                        onOpen = { go(Screen.Thread(it.threadId)) },
                        onSetColor = { tid, id -> if (id == null || id == ChatPalette.DEFAULT.id) colorStore.reset(tid) else colorStore.set(tid, id); chatColors = colorStore.all() },
                        deleting = deleting, notice = inboxNotice,
                        onRequestRole = if (inboxNotice != null && !roleHeld()) ({ inboxNotice = null; roleLauncher.launch(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)) }) else null,
                        onDelete = { c -> deleteThread(c.threadId) },
                        onDeleteMany = { ids -> deleteMany(ids) },
                        onOpenLink = { go(Screen.Link) }, onOpenSettings = { go(Screen.Settings) }, onOpenPrime = { go(Screen.Prime) },
                        onRename = { tid, n -> groupNames.set(tid, n); val l = groupNames.all()[tid]
                            convos = convos.map { if (it.threadId == tid) it.copy(label = l) else it } })
                    is Screen.Thread -> {
                        val conv = convos.firstOrNull { it.threadId == s.threadId }
                        val shownAddr = conv?.singleAddress ?: ""
                        val shownSet = conv?.addresses ?: emptyList()
                        BackHandler { go(Screen.Inbox) }
                        key(s.threadId) { CompositionLocalProvider(LocalAccent provides ChatPalette.byId(chatColors[s.threadId])) {
                            ThreadScreen(
                                threadId = s.threadId,
                                title = conv?.displayName ?: "loading...",
                                participants = conv?.addresses ?: emptyList(),
                                canSend = conv != null && (if (conv.isGroup) shownSet.size > 1 else shownAddr.isNotBlank()),
                                participantNames = conv?.names ?: emptyList(), groupLabel = conv?.label,
                                // Prime drafts can't be approved in groups yet.
                                canApprove = conv != null && (conv.isGroup || shownAddr.isNotBlank()),
                                notice = notice,
                                messages = cache[s.threadId]?.takeIf { it.threadId == s.threadId }?.messages,
                                hasMore = cache[s.threadId]?.hasMore == true,
                                loadingOlder = loadingOlder,
                                onNearTop = { loadOlder(s.threadId) },
                                drafts = pending.filter { it.threadId == s.threadId },
                                onBack = { go(Screen.Inbox) },
                                onForward = { body -> go(Screen.Compose("", body)) },
                                onSend = { body, atts, done ->
                                    // Composer text = the user's own outgoing message. After a successful normal send, an @bot
                                    // message also goes to the PC as a bot_command. Incoming messages never reach this path.
                                    val tid = s.threadId
                                    val after: () -> Unit = { done(); scope.launch(Dispatchers.IO) {
                                        runCatching { com.example.emit.bot.BotCommands.onComposerSend(this@MainActivity, tid, body) } } }
                                    if (conv?.isGroup == true) sendGroup(s.threadId, shownSet, body, atts, after)
                                    else sendInThread(s.threadId, shownAddr, body, atts, after) },
                                // Approve = explicit user tap -> send to THIS thread only. Reject never sends.
                                onApprove = { d -> scope.launch(Dispatchers.IO) {
                                    val err = runCatching { com.example.emit.link.DraftApproval.approve(this@MainActivity, d.id, "phone-thread") }.exceptionOrNull()
                                    withContext(Dispatchers.Main) { notice = err?.let { "[X] ${it.message}" }; refresh++ } } },
                                onReject = { d -> scope.launch(Dispatchers.IO) { com.example.emit.link.DraftApproval.deny(this@MainActivity, d.id)
                                    withContext(Dispatchers.Main) { refresh++ } } }) }
                        }
                    }
                    Screen.Link -> { BackHandler { go(Screen.Settings) }; com.example.emit.ui.LinkScreen { go(Screen.Settings) } }
                    Screen.Settings -> { BackHandler { go(Screen.Inbox) }; SettingsScreen({ go(Screen.Inbox) }, { go(Screen.Link) }, { go(Screen.About) }) }
                    Screen.Prime -> { BackHandler { go(Screen.Inbox) }; com.example.emit.ui.PrimeScreen { go(Screen.Inbox) } }
                    Screen.About -> { BackHandler { go(Screen.Settings) }; AboutScreen { go(Screen.Settings) } }
                    is Screen.Compose -> { BackHandler { go(Screen.Inbox) }
                        NewMessageScreen(s.address, { go(Screen.Inbox) }, prefillBody = s.body, onSend = { a, b -> sendNew(a, b) },
                            search = { q -> withContext(Dispatchers.IO) { repo.searchContacts(q) } }) }
                }
                if (screen == Screen.Inbox && com.example.emit.ui.Overlay.open == 0) Text("[+ NEW]", color = Color(0xFF001A08), fontFamily = Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).background(com.example.emit.ui.Pal.Primary, com.example.emit.ui.Pal.R12)
                        .clickable { go(Screen.Compose("")) }.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 18.dp))
                if (screen == Screen.Inbox && !isDefault() && permsVersion >= 0) Text("[!] not default SMS app - tap to fix", color = Amber, fontFamily = Mono,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color(0xFF120E06), com.example.emit.ui.Pal.R10).border(1.dp, Color(0xFF4A3A1C), com.example.emit.ui.Pal.R10).clickable {
                        roleLauncher.launch(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
                    })
            }
            MatrixKeyboard(kb)
          }
            // Registered last so it wins: back collapses the keyboard first.
            BackHandler(enabled = kb.visible) { kb.hide() }
            val flashColor = Color(ChatPalette.byId((screen as? Screen.Thread)?.let { chatColors[it.threadId] }).argb)
            Box(Modifier.fillMaxSize().alpha(flash.value).background(flashColor))
            GlitchOverlay(glitch)
        }
        }
    }
}
