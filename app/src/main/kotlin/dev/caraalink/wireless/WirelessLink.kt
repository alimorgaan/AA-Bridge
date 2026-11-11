package dev.caraalink.wireless

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.caraalink.diagnostics.DiagnosticLogger
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Wi-Fi Direct to the car: scan, push-button connect, and the car's address once the group forms.
 * [close] never removes the group, so a scan-only user can't drop the service's connection.
 */
@SuppressLint("MissingPermission")
class WirelessLink(
    private val context: Context,
    private val logger: DiagnosticLogger,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeers(peers: List<NearbyDevice>)
        fun onCarReachable(host: String, local: InetAddress?)
        fun onGroupLost()
        fun onWifiOff()
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val channel = manager.initialize(context, Looper.getMainLooper(), null)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val seen = HashMap<String, WifiP2pDevice>()
    private var scanning = false
    private var closed = false
    private var groupFormed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (closed) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        logger.log("Wi-Fi Direct off")
                        listener.onWifiOff()
                    }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel, ::onPeerList)
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> manager.requestConnectionInfo(channel, ::onConnectionInfo)
            }
        }
    }

    private val rescan = object : Runnable {
        override fun run() {
            if (!scanning || closed) return
            manager.discoverPeers(channel, logged("scan"))
            main.postDelayed(this, RESCAN_MS)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** Scans now and again every 8 s until [connect] or [close]. */
    fun scan() {
        if (scanning || closed) return
        scanning = true
        rescan.run()
    }

    fun connect(address: String) {
        stopScanning()
        val device = seen[address]
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = -1
            if (device == null || device.wpsPbcSupported()) wps.setup = WpsInfo.PBC
        }
        logger.log("connecting to ${device?.deviceName ?: address}")
        manager.connect(channel, config, logged("connect"))
    }

    fun disconnect() {
        manager.removeGroup(channel, logged("disconnect"))
    }

    fun close() {
        if (closed) return
        closed = true
        stopScanning()
        stopNsd()
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { channel.close() }
    }

    private fun stopScanning() {
        scanning = false
        main.removeCallbacks(rescan)
    }

    private fun onPeerList(list: WifiP2pDeviceList) {
        val peers = list.deviceList.filter { !it.deviceName.isNullOrBlank() }.map { d ->
            seen[d.deviceAddress] = d
            // Every drive's log shows whether the car's address changes after a restart.
            logger.log("peer: '${d.deviceName}' ${d.deviceAddress} type=${d.primaryDeviceType} status=${d.status}")
            NearbyDevice(d.deviceName, d.deviceAddress, isCarType(d.primaryDeviceType))
        }
        listener.onPeers(peers)
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (closed) return
        if (info.groupFormed) {
            if (groupFormed) return
            groupFormed = true
            logger.log("group formed: phoneIsOwner=${info.isGroupOwner} owner=${info.groupOwnerAddress?.hostAddress}")
            val owner = info.groupOwnerAddress?.hostAddress
            if (!info.isGroupOwner && owner != null) listener.onCarReachable(owner, p2pAddress()) else findCarByMdns()
        } else if (groupFormed) {
            groupFormed = false
            stopNsd()
            logger.log("group lost")
            listener.onGroupLost()
        }
    }

    /** When the phone owns the group, the car's address only comes from its _EasyConn._tcp record. */
    private fun findCarByMdns() {
        stopNsd()
        var reported = false
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = logger.log("mDNS: looking for $type")
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) = logger.log("mDNS: start failed $code")
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, code: Int) = logger.log("mDNS: resolve failed $code")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = s.host?.hostAddress ?: return
                        logger.log("mDNS: '${s.serviceName}' at $host:${s.port}")
                        if (!reported && !closed) {
                            reported = true
                            main.post { listener.onCarReachable(host, p2pAddress()) }
                        }
                    }
                })
            }
        }
        nsd.discoverServices("_EasyConn._tcp.", NsdManager.PROTOCOL_DNS_SD, l)
        nsdListener = l
    }

    private fun stopNsd() {
        nsdListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        nsdListener = null
    }

    private fun logged(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = logger.log("$what: ok")
        override fun onFailure(reason: Int) = logger.log("$what failed: reason $reason")
    }

    companion object {
        private const val RESCAN_MS = 8_000L

        /** The phone's own address on the Wi-Fi Direct interface, so the hello leaves through it. */
        fun p2pAddress(): Inet4Address? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
        }.getOrNull()
    }
}
