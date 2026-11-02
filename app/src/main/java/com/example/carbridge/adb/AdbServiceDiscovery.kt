package com.example.carbridge.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress
import java.net.NetworkInterface

/** Resolves only this phone's ADB advertisements, never other devices on Wi-Fi. */
internal class AdbServiceDiscovery(context: Context, private val changed: () -> Unit) {
    data class Endpoint(val kind: String, val name: String, val port: Int)
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val endpoints = linkedMapOf<String, Endpoint>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private var resolving = false
    private var generation = 0
    private var resolutionSerial = 0
    @Volatile private var snapshot = emptyList<Endpoint>()
    fun endpoints(kind: String): List<Endpoint> = snapshot.filter { it.kind == kind }

    fun start() {
        main.post {
            if (listeners.isNotEmpty()) return@post
            val token = ++generation
            for (kind in listOf(CONNECT, PAIR)) {
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(type: String) { }
                    override fun onDiscoveryStopped(type: String) { }
                    override fun onStartDiscoveryFailed(type: String, error: Int) {
                        val failed = this
                        main.post { if (generation == token) listeners.remove(failed) }
                    }
                    override fun onStopDiscoveryFailed(type: String, error: Int) { }
                    override fun onServiceFound(info: NsdServiceInfo) { main.post {
                        if (generation == token) {
                            val key = "$kind:${info.serviceName}"
                            if (present.add(key)) { pending.addLast(info); resolveNext(token) }
                        }
                    } }
                    override fun onServiceLost(info: NsdServiceInfo) { main.post {
                        if (generation == token) {
                            val key = "$kind:${info.serviceName}"
                            present.remove(key)
                            if (endpoints.remove(key) != null) publish()
                        }
                    } }
                }
                listeners += listener
                runCatching { manager.discoverServices(kind, NsdManager.PROTOCOL_DNS_SD, listener) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(token: Int) {
        if (token != generation || resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        resolving = true
        val serial = ++resolutionSerial
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) = finish(null)
            override fun onServiceResolved(service: NsdServiceInfo) = finish(service)
            private fun finish(service: NsdServiceInfo?) { main.post {
                if (generation != token || serial != resolutionSerial) return@post
                resolving = false
                if (service != null && service.port in 1..65535 && isOwnAddress(service.host)) {
                    val kind = if (service.serviceType.contains("_adb-tls-pairing")) PAIR else CONNECT
                    val key = "$kind:${service.serviceName}"
                    if (key in present) {
                        endpoints[key] = Endpoint(kind, service.serviceName, service.port)
                        publish()
                    }
                }
                resolveNext(token)
            } }
        }
        runCatching { manager.resolveService(info, listener) }.onFailure {
            resolving = false
            resolveNext(token)
        }
        main.postDelayed({
            if (generation == token && resolving && serial == resolutionSerial) {
                resolutionSerial++
                resolving = false
                resolveNext(token)
            }
        }, 4000)
    }

    private fun publish() { snapshot = endpoints.values.toList(); changed() }
    fun stop() { main.post {
        generation++
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear(); pending.clear(); present.clear(); endpoints.clear()
        resolving = false; snapshot = emptyList()
    } }

    companion object {
        const val CONNECT = "_adb-tls-connect._tcp."
        const val PAIR = "_adb-tls-pairing._tcp."
        fun isOwnAddress(address: InetAddress?): Boolean {
            address ?: return false
            if (address.isLoopbackAddress) return true
            return runCatching { NetworkInterface.getNetworkInterfaces().toList().any {
                it.inetAddresses.toList().any { own -> own.address.contentEquals(address.address) }
            } }.getOrDefault(false)
        }
    }
}
