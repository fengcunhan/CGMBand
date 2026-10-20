package com.fch.cgmband

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import com.fch.cgmband.core.DesktopSession
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pushes readings to the Mac menu-bar app (docs/desktop-protocol.md). On Wi-Fi it discovers Macs,
 * connects automatically to paired ones, and lets the settings screen pair new ones. Everything
 * except UI callbacks runs on one background thread, so no state needs locking.
 */
object DesktopLink {

    data class Status(
        val enabled: Boolean = true,
        val onWifi: Boolean = false,
        val found: List<DesktopDiscovery.FoundMac> = emptyList(),
        val trusted: List<DesktopStore.TrustedMac> = emptyList(),
        val connectedMac: String? = null,
        val connectingMac: String? = null,
        val needsPairing: Set<String> = emptySet(),
    )

    /** Shows the pairing code and reports the user's answer; set by the settings screen while visible. */
    @Volatile
    var pairingPrompt: ((macName: String, code: String, answer: (Boolean) -> Unit) -> Unit)? = null

    @Volatile
    var status = Status()
        private set

    private const val TICK_SECONDS = 10L
    private const val PING_AFTER_MS = 30_000L
    private const val SILENCE_LIMIT_MS = 75_000L
    private val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 60_000L, 300_000L)

    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "cgm-desktop") }
    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    private var context: Context? = null
    private var discovery: DesktopDiscovery? = null
    private var found = emptyList<DesktopDiscovery.FoundMac>()
    private var onWifi = false
    private var connection: DesktopConnection? = null
    private var session: DesktopSession? = null
    private var target: DesktopDiscovery.FoundMac? = null
    private var connectedMac: String? = null
    private val needsPairing = mutableSetOf<String>()
    private var failures = 0
    private var retryAt = 0L

    /** Idempotent; call from any long-lived component. */
    fun start(context: Context) = io.execute {
        if (this.context != null) return@execute
        val app = context.applicationContext
        this.context = app
        discovery = DesktopDiscovery(app, io) { macs ->
            found = macs
            maybeConnect()
            publish()
        }
        watchWifi(app)
        io.scheduleWithFixedDelay(::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS)
        publish()
    }

    /** Sends a data message (bg / history) if a paired Mac is connected; otherwise drops it. */
    fun send(message: String) = io.execute {
        val frame = session?.seal(message) ?: return@execute
        write(frame)
    }

    /** User asked to pair with a discovered Mac. */
    fun pair(serviceName: String) = io.execute {
        val mac = found.firstOrNull { it.serviceName == serviceName } ?: return@execute
        disconnect()
        connect(mac, allowPairing = true)
    }

    fun forget(macId: String) = io.execute {
        val app = context ?: return@execute
        DesktopStore.forget(app, macId)
        if (target?.id == macId) disconnect()
        publish()
    }

    fun setEnabled(enabled: Boolean) = io.execute {
        val app = context ?: return@execute
        DesktopStore.setEnabled(app, enabled)
        if (enabled) maybeConnect() else disconnect()
        publish()
    }

    fun observe(observer: () -> Unit) = observers.add(observer)

    fun stopObserving(observer: () -> Unit) = observers.remove(observer)

    // ---- network ---------------------------------------------------------------------------

    private fun watchWifi(app: Context) {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = io.execute {
                    onWifi = true
                    discovery?.start()
                    publish()
                }

                override fun onLost(network: Network) = io.execute {
                    onWifi = false
                    discovery?.stop()
                    disconnect()
                    publish()
                }
            },
        )
    }

    // ---- connection lifecycle --------------------------------------------------------------

    private fun maybeConnect() {
        val app = context ?: return
        if (!DesktopStore.isEnabled(app) || !onWifi || connection != null) return
        if (System.currentTimeMillis() < retryAt) return
        val trusted = DesktopStore.trustedMacs(app).filter { it.id !in needsPairing }
        val trustedIds = trusted.map { it.id }.toSet()
        val mac = found.firstOrNull { it.id in trustedIds } ?: lastKnownAddress(app, trusted) ?: return
        connect(mac, allowPairing = false)
    }

    /**
     * Android pauses Bonjour discovery for apps that are not in the foreground, so a paired Mac
     * that restarted would never be found while the phone is locked. Fall back to where it was
     * last reached (the Mac listens on a fixed port).
     */
    private fun lastKnownAddress(app: Context, trusted: List<DesktopStore.TrustedMac>): DesktopDiscovery.FoundMac? =
        trusted.firstNotNullOfOrNull { mac ->
            DesktopStore.lastAddress(app, mac.id)?.let { (hosts, port) ->
                DesktopDiscovery.FoundMac(mac.name, mac.id, hosts, port)
            }
        }

    private fun connect(mac: DesktopDiscovery.FoundMac, allowPairing: Boolean) {
        val app = context ?: return
        val trusted = DesktopStore.trustedMacs(app).associate { it.id to it.publicKey }
        val conn = DesktopConnection(io, ::onLine, ::onConnectionClosed)
        val newSession = DesktopSession(DesktopStore.identity(app), trusted, allowPairing)
        target = mac
        connection = conn
        session = newSession
        publish()
        EventLog.add("正在连接 Mac：${mac.serviceName}（端口 ${mac.port}${if (allowPairing) "，配对" else ""}）")
        try {
            conn.open(mac.hosts, mac.port)
            handle(newSession.start())
        } catch (e: IOException) {
            EventLog.add("连接 Mac 失败：${mac.serviceName}（${e.message}）")
            dropConnection(retry = true)
            discovery?.refresh(mac.serviceName)
        }
    }

    private fun onLine(conn: DesktopConnection, line: String) {
        if (conn !== connection) return
        session?.let { handle(it.onFrame(line)) }
    }

    private fun onConnectionClosed(conn: DesktopConnection, reason: String) {
        if (conn !== connection) return
        if (connectedMac != null) EventLog.add("与 Mac 的连接断开（$reason）")
        val serviceName = target?.serviceName
        dropConnection(retry = true)
        serviceName?.let { discovery?.refresh(it) }
    }

    private fun handle(output: DesktopSession.Output) {
        output.frames.forEach(::write)
        output.events.forEach { event ->
            when (event) {
                is DesktopSession.Event.PairingRequired -> promptPairing(event)
                is DesktopSession.Event.Ready -> onReady(event)
                DesktopSession.Event.SyncRequested -> sendSnapshot()
                is DesktopSession.Event.Closed -> onSessionClosed(event.reason)
            }
        }
    }

    private fun promptPairing(event: DesktopSession.Event.PairingRequired) {
        val current = session
        val prompt = pairingPrompt
        if (prompt == null) {
            current?.let { handle(it.confirmPairing(false)) }
            return
        }
        main.post {
            prompt(event.macName, event.code) { accepted ->
                io.execute { if (session === current) current?.let { handle(it.confirmPairing(accepted)) } }
            }
        }
    }

    private fun onReady(event: DesktopSession.Event.Ready) {
        val app = context ?: return
        if (event.newlyPaired) {
            DesktopStore.trust(app, DesktopStore.TrustedMac(event.mac.id, event.mac.name, event.mac.publicKey))
            EventLog.add("已与 Mac「${event.mac.name}」配对")
        }
        needsPairing.remove(event.mac.id)
        target?.let { DesktopStore.rememberAddress(app, event.mac.id, it.hosts, it.port) }
        EventLog.add("已连接 Mac：${event.mac.name}")
        failures = 0
        connectedMac = event.mac.name
        publish()
        sendSnapshot()
    }

    private fun onSessionClosed(reason: String) {
        when (reason) {
            "needs_pairing" -> {
                target?.id?.let { needsPairing.add(it) }
                EventLog.add("Mac 需要重新配对")
            }
            "rejected", "rejected_by_user" -> EventLog.add("配对已取消")
            "proto" -> EventLog.add("Mac 端版本不兼容")
            else -> EventLog.add("与 Mac 的会话结束（$reason）")
        }
        // Pairing outcomes need the user; only plain network failures retry automatically.
        dropConnection(retry = reason !in setOf("needs_pairing", "rejected", "rejected_by_user", "proto"))
    }

    private fun write(frame: String) {
        try {
            connection?.send(frame)
        } catch (e: IOException) {
            dropConnection(retry = true)
        }
    }

    private fun tick() {
        val conn = connection
        val current = session
        val now = System.currentTimeMillis()
        if (conn != null && current?.isReady == true) {
            if (now - conn.lastReceivedAt > SILENCE_LIMIT_MS) {
                EventLog.add("Mac 长时间无响应，重新连接")
                dropConnection(retry = true)
            } else if (now - conn.lastSentAt > PING_AFTER_MS) {
                current.keepAlive()?.let(::write)
            }
        }
        maybeConnect()
    }

    private fun disconnect() = dropConnection(retry = false)

    private fun dropConnection(retry: Boolean) {
        connection?.close()
        connection = null
        session = null
        target = null
        connectedMac = null
        if (retry) {
            failures++
            retryAt = System.currentTimeMillis() + RETRY_DELAYS_MS[minOf(failures, RETRY_DELAYS_MS.size) - 1]
        } else {
            failures = 0
            retryAt = 0
        }
        publish()
    }

    private fun sendSnapshot() {
        val app = context ?: return
        main.post { BandSync.pushDesktopSnapshot(app) }
    }

    private fun publish() {
        val app = context ?: return
        status = Status(
            enabled = DesktopStore.isEnabled(app),
            onWifi = onWifi,
            found = found,
            trusted = DesktopStore.trustedMacs(app),
            connectedMac = connectedMac,
            connectingMac = if (connectedMac == null) target?.serviceName else null,
            needsPairing = needsPairing.toSet(),
        )
        observers.forEach { it() }
    }
}
