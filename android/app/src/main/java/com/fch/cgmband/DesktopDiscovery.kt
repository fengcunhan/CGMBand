package com.fch.cgmband

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

/**
 * Finds Macs advertising `_cgmband._tcp` (Bonjour) on the current network. All state lives on
 * [executor]; [onChange] is called there with the full list whenever it changes.
 */
class DesktopDiscovery(
    context: Context,
    private val executor: Executor,
    private val onChange: (List<FoundMac>) -> Unit,
) {
    /**
     * A resolved Mac. [id] comes from the TXT record and identifies it across IP changes;
     * [hosts] lists every advertised address, IPv4 first (link-local IPv6 often is not routable).
     */
    data class FoundMac(val serviceName: String, val id: String?, val hosts: List<InetAddress>, val port: Int)

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val found = linkedMapOf<String, FoundMac>()
    private val services = mutableMapOf<String, NsdServiceInfo>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = executor.execute { enqueue(info) }
            override fun onServiceLost(info: NsdServiceInfo) = executor.execute { remove(info.serviceName) }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = executor.execute {
                EventLog.add("Mac 搜索启动失败（$errorCode）")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /** Drops a possibly stale resolution (the Mac restarted on another port) and resolves it again. */
    fun refresh(serviceName: String) {
        val info = services[serviceName] ?: return
        if (found.remove(serviceName) != null) onChange(found.values.toList())
        enqueue(info)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        services.clear()
        resolving = false
        if (found.isNotEmpty()) {
            found.clear()
            onChange(emptyList())
        }
    }

    private fun enqueue(info: NsdServiceInfo) {
        services[info.serviceName] = info
        if (pending.none { it.serviceName == info.serviceName }) pending.addLast(info)
        resolveNext()
    }

    private fun remove(serviceName: String) {
        services.remove(serviceName)
        if (found.remove(serviceName) != null) onChange(found.values.toList())
    }

    /** NsdManager resolves one service at a time on older Android versions, so resolve sequentially. */
    @Suppress("DEPRECATION") // resolveService / host still work and cover minSdk 26
    private fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = executor.execute {
                resolving = false
                val id = info.attributes["id"]?.toString(Charsets.UTF_8)
                found[info.serviceName] = FoundMac(info.serviceName, id, addressesOf(info), info.port)
                onChange(found.values.toList())
                resolveNext()
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = executor.execute {
                resolving = false
                resolveNext()
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun addressesOf(info: NsdServiceInfo): List<InetAddress> {
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses else listOfNotNull(info.host)
        return all.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    private companion object {
        const val SERVICE_TYPE = "_cgmband._tcp"
    }
}
