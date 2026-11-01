package com.example.emit.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Outbound connection to your relay server, used only away from home Wi-Fi (see RelayPolicy). One TLS socket per paired PC
 * waits in its room (PING every 4 min, no wakelock), and when the PC joins, the spliced socket goes to the same
 * LinkService.handle() as LAN connections. Re-evaluated on every network change (ConnectivityManager.NetworkCallback).
 */
object RelayConnector {
    private const val TAG = "MatrixSMS"
    private var job: Job? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var cm: ConnectivityManager? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var sockets = mutableSetOf<Socket>()

    fun kick() { wake.trySend(Unit) }

    fun currentNet(c: Context): RelayPolicy.Net {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val n = cm.activeNetwork ?: return RelayPolicy.Net(false, false, false, null)
        val caps = cm.getNetworkCapabilities(n); val lp = cm.getLinkProperties(n)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val fp = lp?.let { l ->
            RelayPolicy.networkFingerprint(l.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress,
                l.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }?.let { "${it.address.hostAddress?.substringBeforeLast('.')}/${it.prefixLength}" },
                l.dnsServers.mapNotNull { it.hostAddress }) }
        return RelayPolicy.Net(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true, wifi,
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true, fp)
    }

    fun markHome(c: Context) {
        val n = currentNet(c)
        if (n.wifi && n.fingerprint != null) { LinkStore(c).markHome(n.fingerprint); kick() }
    }

    fun start(svc: LinkService, scope: CoroutineScope) {
        if (job != null) return
        val c = svc.applicationContext
        cm = c.getSystemService(ConnectivityManager::class.java)
        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (lastNet != null && lastNet != network) closeWaiting(); lastNet = network; kick() }
            override fun onLost(network: Network) { if (lastNet == network) { lastNet = null; closeWaiting() }; kick() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = kick()
        }.also { runCatching { cm?.registerDefaultNetworkCallback(it) } }
        job = scope.launch {
            val workers = mutableMapOf<String, Job>()
            while (isActive) {
                val store = LinkStore(c)
                val want = RelayPolicy.shouldConnect(store.relayEnabled, store.relayConfigured, store.pcs().size, currentNet(c),
                    store.homes(), System.currentTimeMillis(), LinkState.lanSessions.value)
                val pcs = if (want) store.pcs().map { it.pcId }.toSet() else emptySet()
                (workers.keys - pcs).forEach { workers.remove(it)?.cancel(); }
                if (!want) { closeAll(); LinkState.relay.value = if (store.relayEnabled && store.relayConfigured) "standby (home Wi-Fi or no network)" else "off" }
                for (id in pcs) if (workers[id]?.isActive != true) workers[id] = launch { worker(svc, id) }
                withTimeoutOrNull(15 * 60_000L) { wake.receive() }  // re-check on network change / config change
            }
        }
    }

    fun stop() {
        runCatching { cb?.let { cm?.unregisterNetworkCallback(it) } }; cb = null
        job?.cancel(); job = null; closeAll(); LinkState.relay.value = "off"
    }

    @Volatile private var lastNet: Network? = null
    private var waiting = mutableSetOf<Socket>()
    /** Default network changed (cellular <-> Wi-Fi): sockets waiting in the room are bound to the old path; drop them now. */
    private fun closeWaiting() { synchronized(this) { waiting.forEach { runCatching { it.close() } }; waiting = mutableSetOf() } }

    private fun closeAll() { synchronized(this) { sockets.forEach { runCatching { it.close() } }; sockets = mutableSetOf() } }

    private fun tls(store: LinkStore): SSLSocket {
        val pin = store.relayFingerprint
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("no cert")
                if (!RelayWire.certMatches(leaf.encoded, pin)) throw CertificateException("relay fingerprint mismatch")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(tm), SecureRandom()) }
        val raw = Socket().apply { connect(InetSocketAddress(store.relayHost, store.relayPort), 10_000); keepAlive = true; tcpNoDelay = true }
        val s = ctx.socketFactory.createSocket(raw, store.relayHost, store.relayPort, true) as SSLSocket
        s.enabledProtocols = s.supportedProtocols.filter { it == "TLSv1.3" }.toTypedArray()
        s.soTimeout = 15_000; s.startHandshake()
        return s
    }

    /** Reads one control line byte-by-byte (so nothing past the newline is consumed before the splice starts). */
    private fun line(inp: java.io.InputStream): String {
        val b = StringBuilder()
        while (true) { val ch = inp.read(); if (ch < 0) throw java.io.EOFException("relay closed"); if (ch == '\n'.code) return b.toString()
            b.append(ch.toChar()); if (b.length > 64) throw java.io.IOException("relay protocol") }
    }

    private suspend fun worker(svc: LinkService, pcId: String) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val store = LinkStore(svc)
            val pub = store.pubFor(pcId) ?: return
            var s: Socket? = null
            try {
                s = tls(store); synchronized(this) { sockets += s; waiting += s }
                val room = RelayWire.roomId(LinkCrypto.dh(store.secretKey(), pub))
                val out = s.getOutputStream(); val inp = s.getInputStream()
                out.write(RelayWire.hello(store.relayToken, room).toByteArray()); out.flush()
                s.soTimeout = RelayPolicy.PING_MS
                LinkState.relay.value = "connected to relay, waiting for PC"
                // Keepalive: PING every PING_MS; if the relay hasn't answered by the next tick the socket is dead
                // (cellular NAT silently dropped it) -> reconnect instead of "waiting" forever on a half-open socket.
                var unanswered = 0
                loop@ while (true) {
                    val l = try { line(inp) } catch (e: SocketTimeoutException) {
                        if (RelayPolicy.keepaliveDead(unanswered)) throw java.io.IOException("keepalive timeout")
                        out.write("PING\n".toByteArray()); out.flush(); unanswered++; continue@loop }
                    val (kind, why) = RelayWire.parse(l)
                    when (kind) {
                        RelayWire.Reply.WAIT -> {}
                        RelayWire.Reply.PONG -> unanswered = 0
                        RelayWire.Reply.PAIRED -> break@loop
                        RelayWire.Reply.ERROR -> throw java.io.IOException("relay: $why")
                    }
                }
                out.write("GO\n".toByteArray()); out.flush(); s.soTimeout = 0; attempt = 0
                synchronized(this) { waiting -= s }
                LinkState.relay.value = "PC connected via relay"
                withContext(Dispatchers.IO) { svc.handle(s, viaRelay = true) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.i(TAG, "relay: ${e.javaClass.simpleName}: ${e.message}")
                LinkState.relay.value = "retrying (${e.message?.take(40)})"
                attempt++
            } finally { s?.let { runCatching { it.close() }; synchronized(this) { sockets -= it; waiting -= it } } }
            delay(if (attempt == 0) 500 else RelayPolicy.backoffMs(attempt - 1, Math.random()))
        }
    }
}
