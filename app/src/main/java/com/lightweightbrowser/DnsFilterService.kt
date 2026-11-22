package com.lightweightbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Optional DNS-only VPN filter.
 *
 * Only 10.23.42.2/32 is routed to this service and installed as the DNS server.
 * All other traffic stays on the underlying network. Blocked queries receive a
 * local NXDOMAIN; allowed queries are forwarded byte-for-byte to the current
 * network's upstream DNS servers. The service is off unless the user enables it.
 */
class DnsFilterService : VpnService() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (isRunning) return START_NOT_STICKY

        val upstream = upstreamDnsServers()
        if (upstream.isEmpty()) {
            Log.w(TAG, "No usable IPv4 upstream DNS server; DNS filter not started")
            DnsFilterPrefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())

        val builder = Builder()
            .setSession(SESSION_NAME)
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 30)
            .addRoute(VPN_DNS, 32)
            .addDnsServer(VPN_DNS)

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            Log.w(TAG, "Could not establish DNS VPN: ${e.javaClass.simpleName}: ${e.message}")
            shutdown()
            return START_NOT_STICKY
        }
        if (descriptor == null) {
            Log.w(TAG, "Could not establish DNS VPN: no descriptor")
            shutdown()
            return START_NOT_STICKY
        }

        tunnel = descriptor
        isRunning = true
        DnsFilterPrefs.setEnabled(this, true)
        Thread({ runTunnel(descriptor, upstream) }, "lw-dns-filter").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            tunnel?.close()
        } catch (e: Exception) {
            // Already closed by the tunnel thread.
        }
        tunnel = null
        super.onDestroy()
    }

    private fun runTunnel(descriptor: ParcelFileDescriptor, upstream: List<InetAddress>) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val output = FileOutputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MAX_PACKET_BYTES)

        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue

                val envelope = DnsPackets.parseIpv4Udp(buffer.copyOf(read)) ?: continue
                val query = DnsPackets.parseQuery(envelope.payload) ?: continue

                val responsePayload = when {
                    query.questions.isEmpty() ->
                        DnsPackets.responseWithRcode(envelope.payload, query.questionEnd, RCODE_FAILURE)
                    query.questions.any { BlockerEngineManager.shouldBlockDns(it) } -> {
                        Log.d(TAG, "Blocked DNS: ${query.questions.joinToString(", ")}")
                        DnsPackets.nxDomainResponse(envelope.payload, query.questionEnd)
                    }
                    else -> forward(envelope.payload, query.questionEnd, upstream)
                }

                val response = DnsPackets.buildIpv4UdpResponse(envelope, responsePayload) ?: continue
                output.write(response)
                output.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "DNS tunnel stopped: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            shutdown()
        }
    }

    @Synchronized
    private fun shutdown() {
        isRunning = false
        DnsFilterPrefs.setEnabled(this, false)
        try {
            tunnel?.close()
        } catch (e: Exception) {
            // Already closed by the tunnel thread or onDestroy().
        }
        tunnel = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun forward(query: ByteArray, questionEnd: Int, upstream: List<InetAddress>): ByteArray {
        for (server in upstream) {
            val socket = DatagramSocket()
            try {
                socket.soTimeout = FORWARD_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, server, DNS_PORT))
                val replyBuffer = ByteArray(MAX_DNS_RESPONSE_BYTES)
                val reply = DatagramPacket(replyBuffer, replyBuffer.size)
                socket.receive(reply)
                return reply.data.copyOfRange(reply.offset, reply.offset + reply.length)
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                Log.w(TAG, "DNS forward failed via ${server.hostAddress}: ${e.message}")
                continue
            } finally {
                socket.close()
            }
        }
        return DnsPackets.responseWithRcode(query, questionEnd, RCODE_FAILURE)
    }

    private fun upstreamDnsServers(): List<InetAddress> {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = manager.activeNetwork ?: return emptyList()
        val properties = manager.getLinkProperties(network) ?: return emptyList()
        return properties.dnsServers
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { it.isNotEmpty() && it != VPN_ADDRESS && it != VPN_DNS }
            .distinct()
            .mapNotNull { parseIPv4(it) }
    }

    private fun parseIPv4(value: String): Inet4Address? = try {
        InetAddress.getByName(value) as? Inet4Address
    } catch (e: Exception) {
        null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNS ad filter",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Optional DNS-level tracker blocking" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle("DNS ad filter active")
            .setContentText("Tracker domains blocked · tap to open")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.lightweightbrowser.DNS_FILTER_START"
        const val ACTION_STOP = "com.lightweightbrowser.DNS_FILTER_STOP"

        private const val TAG = "DnsFilterService"
        private const val SESSION_NAME = "LightweightBrowser DNS filter"
        private const val CHANNEL_ID = "dns_filter"
        private const val NOTIFICATION_ID = 4242
        private const val VPN_ADDRESS = "10.23.42.1"
        private const val VPN_DNS = "10.23.42.2"
        private const val DNS_PORT = 53
        private const val FORWARD_TIMEOUT_MS = 2500
        private const val MAX_PACKET_BYTES = 16_384
        private const val MAX_DNS_RESPONSE_BYTES = 65_535
        private const val RCODE_FAILURE = 2

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var tunnel: ParcelFileDescriptor? = null
    }
}
