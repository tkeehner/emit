package com.example.emit.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.emit.MainActivity
import com.example.emit.sms.IncomingBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** UI-observable link state (pairing code, ALLOW prompt, connected PCs). */
object LinkState {
    data class Pairing(val until: Long, val code: String? = null, val pcName: String? = null, val awaitingAllow: Boolean = false, val result: String? = null)
    val running = MutableStateFlow(false)
    val address = MutableStateFlow<String?>(null)
    val pairing = MutableStateFlow<Pairing?>(null)
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val lanSessions = MutableStateFlow(0)
    /** Open sessions per PC id. One PC can hold several sessions (e.g. two PC-app windows); the id stays "connected"
     *  until its LAST session closes. (Bug fixed in 0.1.22: closing one window used to mark the PC offline.) */
    private val sessionCounts = HashMap<String, Int>()
    @Synchronized fun sessionOpened(id: String) { sessionCounts[id] = (sessionCounts[id] ?: 0) + 1; connected.value = sessionCounts.keys.toSet() }
    @Synchronized fun sessionClosed(id: String) {
        val n = (sessionCounts[id] ?: 1) - 1
        if (n <= 0) sessionCounts.remove(id) else sessionCounts[id] = n
        connected.value = sessionCounts.keys.toSet()
    }
    @Synchronized fun resetSessions() { sessionCounts.clear(); connected.value = emptySet() }
    /** Relay status line for the Link PC screen: off / waiting / connected / error text. */
    val relay = MutableStateFlow("off")
    @Volatile var decision: CompletableFuture<Boolean>? = null
    fun openPairing(ms: Long = 180_000) { pairing.value = Pairing(System.currentTimeMillis() + ms) }
    fun closePairing() { decision?.complete(false); pairing.value = null }
    fun answer(allow: Boolean) { decision?.complete(allow) }
    fun pairingOpen() = pairing.value?.let { it.result == null && System.currentTimeMillis() < it.until } == true
}

/**
 * Foreground service: LAN listener (TCP 47613) + mDNS/NSD advert _matrixsms._tcp. Idle cost is one blocked accept()
 * thread; no wakelocks, no polling. Runs only while "Link PC" is enabled.
 */
class LinkService : Service() {
    companion object {
        const val PORT = 47613
        private const val TAG = "MatrixSMS"
        /** v2: own IMPORTANCE_MIN channel (an existing channel's importance can't be lowered, so new id + delete old). */
        const val CH = "link_background_v2"
        const val OLD_CH = "link_service"
        fun start(c: Context) = c.startForegroundService(Intent(c, LinkService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, LinkService::class.java))
        fun lanAddress(c: Context): String? = runCatching {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }.getOrNull()
    }

    private lateinit var store: LinkStore
    private lateinit var rpc: LinkRpc
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = AtomicInteger(0)
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var failedPairs = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = LinkStore(this); rpc = LinkRpc(this, store)
        val nm = getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel(OLD_CH) }
        nm.createNotificationChannel(NotificationChannel(CH, "Link PC background (can turn off)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Required while Link PC runs. Turning this off hides it without affecting message notifications."
            setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("listening for your PC on Wi-Fi")
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(7, n)
        if (server == null) startServer()
        return START_STICKY
    }

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CH)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("> Emit link").setContentText(text)
        .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_SECRET).setOnlyAlertOnce(true)
        .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .setContentIntent(PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_LINK, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun startServer() {
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) } }
            .getOrElse { Log.w(TAG, "link: bind failed", it); stopSelf(); return }
        server = ss; LinkState.running.value = true; LinkState.address.value = lanAddress(this)?.let { "$it:$PORT" }
        Log.i(TAG, "link: listening on ${LinkState.address.value}")
        registerNsd()
        RelayConnector.start(this, scope)
        scope.launch {
            while (isActive && !ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                if (conns.get() >= 8) { s.close(); continue }
                launch { handle(s) }
            }
        }
        // Push "messages_changed" to connected PCs when SMS/MMS arrive or are sent (debounced).
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        scope.launch { IncomingBus.events.debounce(400).collect {
            if (LinkState.connected.value.isEmpty()) return@collect
            val top = runCatching { com.example.emit.data.SmsRepository(this@LinkService).conversations().firstOrNull() }.getOrNull()
            LinkEvents.emit("messages_changed", JSONObject().apply { top?.let { put("thread_id", it.threadId).put("name", it.displayName).put("snippet", it.snippet).put("date", it.date) } })
        } }
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply { serviceName = "MatrixSMS-" + store.phoneId.take(6); serviceType = "_matrixsms._tcp"; port = PORT
            setAttribute("id", store.phoneId); setAttribute("v", "1") }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "link: nsd registered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { Log.w(TAG, "link: nsd failed $e") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd = getSystemService(NsdManager::class.java)
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); nsdListener = l }
    }

    /** Same handler for LAN sockets and relay-spliced TLS sockets (viaRelay = true). */
    internal fun handle(s: Socket, viaRelay: Boolean = false) {
        conns.incrementAndGet(); sockets += s
        var pcId: String? = null
        var listener: ((JSONObject) -> Unit)? = null
        var lan = false
        try {
            s.tcpNoDelay = true; s.keepAlive = true; s.soTimeout = 15_000
            val io = FrameIO(s.getInputStream(), s.getOutputStream())
            val first = io.read()
            val t = runCatching { JSONObject(String(first)).optString("t") }.getOrDefault("")
            if (t == "pair1") {
                if (viaRelay) { io.write(json("t" to "err", "msg" to "pairing only on home Wi-Fi").toString().toByteArray()); return }
                pair(io, first, s); return }
            val (ch, id) = PhoneProtocol.sessionResponder(io, first, store.phoneId, store.secretKey(), { store.pubFor(it) })
            pcId = id; store.touch(id); s.soTimeout = 0
            LinkState.sessionOpened(id)
            Log.i(TAG, "link: session open pc=$id via ${if (viaRelay) "relay" else "lan"}")
            if (!viaRelay) { RelayConnector.markHome(this); LinkState.lanSessions.value++; lan = true }
            val l: (JSONObject) -> Unit = { ev -> synchronized(ch) { ch.send(ev) } }
            listener = l; LinkEvents.add(l)
            scope.launch { delay(300); runCatching { com.example.emit.bot.BotCommands.flush(this@LinkService) }
                runCatching { com.example.emit.prime.PrimeChat.flush(this@LinkService) } }
            while (true) {
                val req = ch.recv()
                val rid = req.opt("id")
                val resp = JSONObject().put("id", rid)
                if (store.pubFor(id) == null) { ch.send(resp.put("e", JSONObject().put("code", "revoked").put("msg", "PC was unlinked on the phone"))); break }
                try { resp.put("r", rpc.call(req.optString("m"), req.optJSONObject("p") ?: JSONObject(), viaRelay)) }
                catch (e: RpcError) { resp.put("e", JSONObject().put("code", e.code).put("msg", e.message)) }
                catch (e: Exception) { Log.w(TAG, "link: rpc ${req.optString("m")} failed", e)
                    resp.put("e", JSONObject().put("code", "error").put("msg", e.message ?: e.javaClass.simpleName)) }
                synchronized(ch) { ch.send(resp) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "link: connection ended (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            listener?.let { LinkEvents.remove(it) }
            pcId?.let { id -> LinkState.sessionClosed(id) }
            if (lan) { LinkState.lanSessions.value--; RelayConnector.kick() }
            runCatching { s.close() }; sockets -= s; conns.decrementAndGet()
        }
    }

    private fun pair(io: FrameIO, first: ByteArray, s: Socket) {
        if (!LinkState.pairingOpen()) { io.write(json("t" to "err", "msg" to "phone is not in pairing mode (open Link PC > PAIR NEW PC)").toString().toByteArray()); return }
        s.soTimeout = 150_000
        val fut = CompletableFuture<Boolean>(); LinkState.decision = fut
        try {
            val pc = PhoneProtocol.pairResponder(io, first, store.phoneId, Build.MODEL, store.secretKey(),
                showCode = { code, name -> LinkState.pairing.value = LinkState.pairing.value?.copy(code = code, pcName = name) },
                allow = { _ ->
                    LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = true)
                    runCatching { fut.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
                })
            store.addPc(pc.pcId, pc.name, pc.pub)
            Log.i(TAG, "link: paired pc=${pc.pcId} name=${pc.name}")
            LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = false, result = "paired with ${pc.name}")
            failedPairs = 0
        } catch (e: Exception) {
            Log.w(TAG, "link: pairing failed: ${e.message}")
            if (++failedPairs >= 3) { LinkState.pairing.value = LinkState.pairing.value?.copy(awaitingAllow = false, result = "pairing closed after 3 failures"); failedPairs = 0 }
            else LinkState.pairing.value = LinkState.pairing.value?.copy(code = null, awaitingAllow = false)
        } finally { LinkState.decision = null }
    }

    override fun onDestroy() {
        RelayConnector.stop()
        runCatching { nsdListener?.let { nsd?.unregisterService(it) } }
        runCatching { server?.close() }; sockets.forEach { runCatching { it.close() } }
        scope.cancel(); server = null
        LinkState.running.value = false; LinkState.resetSessions()
        super.onDestroy()
    }
}

/** Restarts the link service (which starts the relay connector) after reboot or after an app update, if link is enabled. */
class LinkBootReceiver : BroadcastReceiver() {
    companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        fun shouldStart(action: String?, linkEnabled: Boolean) = linkEnabled && action in ACTIONS
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (shouldStart(intent.action, LinkStore(context).enabled)) runCatching { LinkService.start(context) }
            .onFailure { Log.w("MatrixSMS", "link: restart after ${intent.action} failed", it) }
    }
}
