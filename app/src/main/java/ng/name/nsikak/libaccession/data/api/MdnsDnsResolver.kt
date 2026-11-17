package ng.name.nsikak.libaccession.data.api

import android.content.Context
import android.net.wifi.WifiManager
import okhttp3.Dns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Custom DNS resolver for OkHttp that enables resolving `.local` mDNS hostnames across all Android versions
 * (especially Android 7–11 where standard system [Dns.SYSTEM] fails for `.local` domains).
 */
class MdnsDnsResolver(private val context: Context) : Dns {
    private val systemDns = Dns.SYSTEM

    override fun lookup(hostname: String): List<InetAddress> {
        val lowerHost = hostname.lowercase()

        // 1. Try standard system DNS lookup first
        try {
            val addresses = systemDns.lookup(hostname)
            if (addresses.isNotEmpty()) return addresses
        } catch (e: UnknownHostException) {
            if (!lowerHost.endsWith(".local") && lowerHost != "local") {
                throw e
            }
        }

        // 2. Fallback for .local domains on Android < 12 via mDNS multicast query
        if (lowerHost.endsWith(".local") || lowerHost == "local") {
            val resolved = queryMdns(lowerHost)
            if (resolved.isNotEmpty()) {
                return resolved
            }
        }

        throw UnknownHostException("Unable to resolve mDNS host: $hostname")
    }

    private fun queryMdns(hostname: String): List<InetAddress> {
        var multicastLock: WifiManager.MulticastLock? = null
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("tally_mdns_lock")?.apply {
                setReferenceCounted(false)
                acquire()
            }

            val queryPacket = buildMdnsQuery(hostname)
            val group = InetAddress.getByName("224.0.0.251")
            val port = 5353

            val socket = try {
                MulticastSocket(0)
            } catch (_: Exception) {
                MulticastSocket(port)
            }

            socket.reuseAddress = true
            socket.soTimeout = 1200

            // Set active Wi-Fi interface if available
            val wifiInterface = runCatching {
                NetworkInterface.getNetworkInterfaces()?.asSequence()?.firstOrNull { iface ->
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                            iface.inetAddresses.asSequence().any { it is Inet4Address && !it.isLoopbackAddress }
                }
            }.getOrNull()

            if (wifiInterface != null) {
                runCatching { socket.networkInterface = wifiInterface }
            }

            runCatching { socket.joinGroup(group) }

            val sendPacket = DatagramPacket(queryPacket, queryPacket.size, group, port)
            socket.send(sendPacket)

            val recvBuffer = ByteArray(1472)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)

            val startTime = System.currentTimeMillis()
            val addresses = mutableListOf<InetAddress>()

            while (System.currentTimeMillis() - startTime < 1200) {
                try {
                    socket.receive(recvPacket)
                    val parsed = parseMdnsResponse(recvPacket.data, recvPacket.length)
                    if (parsed.isNotEmpty()) {
                        addresses.addAll(parsed)
                        break
                    }
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Exception) {}
            }

            runCatching { socket.leaveGroup(group) }
            runCatching { socket.close() }

            return addresses.distinct()
        } catch (_: Exception) {
            return emptyList()
        } finally {
            runCatching { multicastLock?.release() }
        }
    }

    private fun buildMdnsQuery(hostname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(0) // Transaction ID
        dos.writeShort(0) // Standard query
        dos.writeShort(1) // 1 Question
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        val parts = hostname.trim('.').split('.')
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)

        dos.writeShort(1) // Type A (IPv4)
        dos.writeShort(1 or 0x8000) // Class IN with Unicast Response Bit set

        dos.flush()
        return baos.toByteArray()
    }

    private fun parseMdnsResponse(data: ByteArray, length: Int): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val dis = DataInputStream(ByteArrayInputStream(data, 0, length))
            dis.skipBytes(2) // Transaction ID
            dis.skipBytes(2) // Flags
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            val nsCount = dis.readUnsignedShort()
            val arCount = dis.readUnsignedShort()

            repeat(qdCount) {
                skipName(dis)
                dis.skipBytes(4) // QType, QClass
            }

            val totalRecords = anCount + nsCount + arCount
            for (i in 0 until totalRecords) {
                skipName(dis)
                val type = dis.readUnsignedShort()
                dis.skipBytes(2) // Class
                dis.skipBytes(4) // TTL
                val rdLength = dis.readUnsignedShort()

                if (type == 1 && rdLength == 4) { // Type A (IPv4)
                    val ip = ByteArray(4)
                    dis.readFully(ip)
                    result.add(InetAddress.getByAddress(ip))
                } else {
                    dis.skipBytes(rdLength)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                dis.readUnsignedByte()
                break
            } else {
                dis.skipBytes(len)
            }
        }
    }
}
