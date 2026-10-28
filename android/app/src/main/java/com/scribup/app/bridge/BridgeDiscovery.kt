package com.scribup.app.bridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FoundBridge(val name: String, val host: String, val port: Int)

/** Trouve le compagnon PC sur le Wi-Fi (mDNS, service _scribup._tcp). */
class BridgeDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    private val _found = MutableStateFlow<List<FoundBridge>>(emptyList())
    val found: StateFlow<List<FoundBridge>> = _found.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun start() {
        if (listener != null) return
        _found.value = emptyList()
        lock = wifi.createMulticastLock("scribup").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { _searching.value = true }
            override fun onDiscoveryStopped(serviceType: String) { _searching.value = false }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stop() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = resolve(serviceInfo)
        }
        listener = l
        nsd.discoverServices("_scribup._tcp", NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val bridge = FoundBridge(serviceInfo.serviceName.removePrefix("ScribUp "), host, serviceInfo.port)
                _found.update { list -> (list.filterNot { it.host == host && it.port == bridge.port } + bridge) }
            }
        })
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.release()
        lock = null
        _searching.value = false
    }
}
