package ng.name.nsikak.libaccession.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ng.name.nsikak.libaccession.data.api.MdnsDnsResolver
import ng.name.nsikak.libaccession.data.settings.ServerConfig
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

data class DiscoveredServer(val host: String, val port: Int, val via: String)

/**
 * Finds the library server on the LAN.
 *  1. NSD/mDNS service browse for "_library._tcp" (server/library.service on the Avahi box).
 *     Gives both the IP and the real port.
 *  2. Fallback: resolve "school.local" through the system resolver (works on some devices only).
 */
class ServerDiscovery(private val context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(): DiscoveredServer? =
        safe { discoverViaNsd() } ?: safe { resolveHostname() }

    private suspend fun <T> safe(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun discoverViaNsd(): DiscoveredServer? {
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return withTimeoutOrNull(NSD_TIMEOUT_MS) {
                suspendCancellableCoroutine<DiscoveredServer?> { cont ->
                    val l = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                            if (cont.isActive) runCatching { cont.resume(null) }
                        }

                        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                        override fun onDiscoveryStarted(serviceType: String) {}
                        override fun onDiscoveryStopped(serviceType: String) {}
                        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            resolve(serviceInfo) { found ->
                                if (found != null && cont.isActive) runCatching { cont.resume(found) }
                            }
                        }
                    }
                    listener = l
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    @Suppress("DEPRECATION") // resolveService is deprecated on API 34+ but still works on every API level we support.
    private fun resolve(info: NsdServiceInfo, onDone: (DiscoveredServer?) -> Unit) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = onDone(null)

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val rawHost = serviceInfo.host?.hostName
                val ipAddress = ipv4Of(serviceInfo)?.hostAddress
                val host = if (!rawHost.isNullOrEmpty() && !IPV4_PATTERN.matches(rawHost)) {
                    val clean = rawHost.trim('.')
                    if (clean.lowercase().endsWith(".local")) clean else "$clean.local"
                } else if (!ipAddress.isNullOrEmpty()) {
                    ipAddress
                } else {
                    MDNS_HOST
                }
                onDone(DiscoveredServer(host, serviceInfo.port, "mDNS service"))
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): Inet4Address? =
        if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()
        } else {
            info.host as? Inet4Address
        }

    private suspend fun resolveHostname(): DiscoveredServer? {
        val lookup = CoroutineScope(Dispatchers.IO).async {
            runCatching {
                val addresses = MdnsDnsResolver(context).lookup(MDNS_HOST)
                addresses.any { it is Inet4Address }
            }.getOrDefault(false)
        }
        val resolved = withTimeoutOrNull(HOST_TIMEOUT_MS) { lookup.await() } ?: false
        // Keep the NAME (not the IP): it survives DHCP changes and is on the cleartext allow-list.
        return if (resolved) DiscoveredServer(MDNS_HOST, ServerConfig.DEFAULT_PORT, "$MDNS_HOST lookup") else null
    }

    private companion object {
        val IPV4_PATTERN = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        const val SERVICE_TYPE = "_library._tcp"
        const val MDNS_HOST = "school.local"
        const val NSD_TIMEOUT_MS = 4_000L
        const val HOST_TIMEOUT_MS = 2_500L
    }
}
