package com.example.carbridge.link

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarLife's Wi-Fi transport: the HU opens one TCP socket per CarLife channel.
 * The supplied streams adapt those six channel streams to CarLifeHuLink's existing
 * outer-frame stream, so the USB protocol handler remains shared.
 */
class CarLifeWifiTransport(
    context: Context,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { task ->
        Thread(task, "carwifi-io").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val pipeLock = Any()
    private val pipeIn = java.io.PipedInputStream(1024 * 1024)
    private val pipeOut = java.io.PipedOutputStream(pipeIn)
    private val listeners = ConcurrentHashMap<Int, ServerSocket>()
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val pendingWrites = mutableMapOf<Int, ArrayDeque<ByteArray>>()
    private var pendingWriteBytes = 0
    private val probeSocket = AtomicReferenceSocket()
    private val beaconSocket = AtomicReferenceSocket()
    @Volatile private var bluetoothP2p: CarLifeBluetoothP2p? = null
    @Volatile private var localAddress: Inet4Address? = null
    @Volatile private var prefixLength: Int = 0
    @Volatile private var peerAddress: InetAddress? = null

    val input: InputStream get() = pipeIn
    val output: OutputStream = object : OutputStream() {
        private val frames = FrameReader()

        override fun write(value: Int) {
            val one = byteArrayOf(value.toByte())
            write(one, 0, 1)
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            if (closed.get()) throw IOException("Wi-Fi CarLife transport is closed")
            frames.feed(data, offset, length) { channel, inner ->
                synchronized(sockets) {
                    val socket = sockets[channel]
                    if (socket == null || socket.isClosed) {
                        val copy = inner.copyOf()
                        if (pendingWriteBytes + copy.size > MAX_PENDING_WRITE_BYTES) {
                            throw IOException("Wi-Fi CarLife channel $channel is not ready; pending queue is full")
                        }
                        pendingWrites.getOrPut(channel) { ArrayDeque() }.addLast(copy)
                        pendingWriteBytes += copy.size
                    } else {
                        socket.getOutputStream().write(inner)
                    }
                }
            }
        }

        override fun flush() {
            synchronized(sockets) {
                sockets.values.forEach { it.getOutputStream().flush() }
            }
        }

        override fun close() = Unit // The owning link closes this transport separately.
    }

    fun start() {
        if (!requested.compareAndSet(false, true) || closed.get()) return
        val connector = CarLifeBluetoothP2p(app, ::postStatus,
            onGroupReady = { local, prefix, notifyHu ->
                io.execute {
                    try {
                        prepareP2pTransport(local, prefix)
                        notifyHu()
                    } catch (error: Exception) {
                        fail("Не удалось открыть каналы CarLife по Wi-Fi Direct: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            },
            onGroupLost = { fail("Потеряна Wi-Fi Direct группа CarLife") })
        bluetoothP2p = connector
        connector.start()
    }

    private fun prepareP2pTransport(local: Inet4Address, prefix: Int) {
        if (closed.get() || listeners.isNotEmpty()) return
        localAddress = local
        prefixLength = prefix
        openListeners(local)
        startBeacon(local, prefix)
        postStatus("Открыты TCP-каналы CarLife на Wi-Fi Direct (${local.hostAddress}).")
    }

    private fun openListeners(local: Inet4Address) {
        CHANNEL_PORTS.forEach { (channel, port) ->
            if (closed.get()) throw IOException("Wi-Fi CarLife transport is closed")
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(local, port), 4)
                soTimeout = ACCEPT_POLL_MS
            }
            listeners[channel] = server
        }
        postStatus("Слушаю 6 каналов CarLife на ${local.hostAddress}; запускаю обнаружение ГУ…")
        listeners.forEach { (channel, server) ->
            io.execute { acceptLoop(channel, server) }
        }
    }

    private fun acceptLoop(channel: Int, server: ServerSocket) {
        val deadline = System.nanoTime() + ACCEPT_DEADLINE_NS
        while (!closed.get() && !ready.get() && System.nanoTime() < deadline) {
            var socket: Socket? = null
            try {
                val incoming = server.accept().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    if (channel == Wire.CH_VIDEO) sendBufferSize = VIDEO_SEND_BUFFER
                }
                socket = incoming
                val remote = incoming.inetAddress
                val local = localAddress ?: throw IOException("Wi-Fi адрес не готов")
                if (!isSameSubnet(local, remote, prefixLength)) {
                    postStatus("Отклонил CarLife TCP-клиент вне сети ГУ (${remote.hostAddress})")
                    incoming.close(); socket = null
                    continue
                }
                synchronized(sockets) {
                    val peer = peerAddress
                    if (peer != null && peer != remote) {
                        postStatus("Отклонил второй CarLife-клиент (${remote.hostAddress})")
                        incoming.close(); socket = null
                    } else if (sockets[channel]?.isConnected == true && sockets[channel]?.isClosed == false) {
                        postStatus("Отклонил повторный канал CarLife ${channelName(channel)}")
                        incoming.close(); socket = null
                    } else {
                        peerAddress = remote
                        sockets[channel] = incoming
                        pendingWrites.remove(channel)?.let { queued ->
                            val output = incoming.getOutputStream()
                            while (queued.isNotEmpty()) {
                                val pending = queued.removeFirst()
                                pendingWriteBytes -= pending.size
                                output.write(pending)
                            }
                        }
                        postStatus("Подключён канал ${channelName(channel)}: ${sockets.size}/6")
                        socket = null
                        if (sockets.size == CHANNEL_PORTS.size && ready.compareAndSet(false, true)) {
                            onChannelsReady()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Poll so stop() and network loss promptly release accept threads.
            } catch (error: SocketException) {
                if (!closed.get() && !ready.get()) fail("TCP CarLife ${channelName(channel)}: ${error.message}")
                return
            } catch (error: Exception) {
                if (!closed.get()) fail("TCP CarLife ${channelName(channel)}: ${error.message ?: error.javaClass.simpleName}")
                return
            } finally {
                runCatching { socket?.close() }
            }
        }
        if (!closed.get() && !ready.get() && System.nanoTime() >= deadline) {
            fail("За 10 минут ГУ не открыло все 6 TCP-каналов CarLife")
        }
    }

    private fun onChannelsReady() {
        listeners.values.forEach { runCatching { it.close() } }
        postStatus("ГУ подключило все 6 Wi-Fi каналов. Запускаю CarLife…")
        sockets.forEach { (channel, socket) ->
            io.execute { readChannel(channel, socket) }
        }
    }

    private fun readChannel(channel: Int, socket: Socket) {
        val assembler = InnerAssembler()
        val buffer = ByteArray(64 * 1024)
        try {
            val input = socket.getInputStream()
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) throw IOException("ГУ закрыло канал ${channelName(channel)}")
                if (count == 0) continue
                assembler.feed(channel, buffer.copyOf(count)) { ch, inner ->
                    val outer = Wire.outer(ch, inner)
                    synchronized(pipeLock) {
                        if (!closed.get()) {
                            pipeOut.write(outer)
                            pipeOut.flush()
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) fail("Wi-Fi канал ${channelName(channel)} оборвался: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun startBeacon(local: Inet4Address, prefix: Int) {
        val responder = DatagramSocket(null)
        responder.reuseAddress = true
        responder.bind(InetSocketAddress(local, PROBE_PORT))
        responder.soTimeout = 1000
        probeSocket.set(responder)

        val sender = DatagramSocket(null)
        sender.broadcast = true
        sender.bind(InetSocketAddress(local, 0))
        beaconSocket.set(sender)

        val directedBroadcast = broadcastAddress(local, prefix)
        io.execute {
            val buffer = ByteArray(2048)
            while (!closed.get() && !responder.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    val text = String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII)
                    if (text.contains("carlifehost")) {
                        val response = READY_BEACON
                        responder.send(DatagramPacket(response, response.size, packet.address, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep stop responsive without a second thread interrupt mechanism.
                } catch (error: Exception) {
                    if (!closed.get()) Log.w(TAG, "UDP CarLife responder stopped", error)
                    break
                }
            }
        }
        io.execute {
            val payload = HOST_BEACON
            val targets = linkedSetOf(directedBroadcast, InetAddress.getByName("255.255.255.255"))
            while (!closed.get() && !sender.isClosed) {
                try {
                    targets.forEach { destination ->
                        sender.send(DatagramPacket(payload, payload.size, destination, BEACON_PORT))
                    }
                } catch (error: Exception) {
                    if (!closed.get()) Log.w(TAG, "UDP CarLife beacon send failed", error)
                }
                try { Thread.sleep(BEACON_INTERVAL_MS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
    }

    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        Log.w(TAG, message)
        postStatus(message)
        closeResources()
    }

    private fun postStatus(message: String) {
        handler.post { onStatus(message) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) closeResources()
    }

    private fun closeResources() {
        bluetoothP2p?.close()
        bluetoothP2p = null
        listeners.values.forEach { runCatching { it.close() } }
        sockets.values.forEach { runCatching { it.close() } }
        synchronized(sockets) {
            pendingWrites.clear()
            pendingWriteBytes = 0
        }
        probeSocket.close()
        beaconSocket.close()
        synchronized(pipeLock) {
            runCatching { pipeOut.close() }
            runCatching { pipeIn.close() }
        }
        io.shutdownNow()
    }

    private fun isSameSubnet(local: Inet4Address, remote: InetAddress, prefix: Int): Boolean {
        val v4 = remote as? Inet4Address ?: return false
        val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
        return (addressBits(local) and mask) == (addressBits(v4) and mask)
    }

    private fun broadcastAddress(local: Inet4Address, prefix: Int): InetAddress {
        val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix.coerceAtMost(32)))
        val bits = (addressBits(local) and mask) or mask.inv()
        val bytes = byteArrayOf((bits ushr 24).toByte(), (bits ushr 16).toByte(), (bits ushr 8).toByte(), bits.toByte())
        return InetAddress.getByAddress(bytes)
    }

    private fun addressBits(address: Inet4Address): Int {
        val bytes = address.address
        return ((bytes[0].toInt() and 255) shl 24) or ((bytes[1].toInt() and 255) shl 16) or
            ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
    }

    private fun channelName(channel: Int) = when (channel) {
        Wire.CH_CMD -> "CMD"
        Wire.CH_VIDEO -> "VIDEO"
        Wire.CH_MEDIA -> "MEDIA"
        Wire.CH_TTS -> "TTS"
        Wire.CH_VR -> "VR"
        Wire.CH_TOUCH -> "TOUCH"
        else -> "CH$channel"
    }

    private class AtomicReferenceSocket {
        @Volatile private var value: DatagramSocket? = null
        fun set(socket: DatagramSocket) { value = socket }
        fun close() { runCatching { value?.close() }; value = null }
    }

    companion object {
        private const val TAG = "CarBridge/WiFi"
        private const val BEACON_PORT = 7999
        private const val PROBE_PORT = 8999
        private const val ACCEPT_POLL_MS = 1000
        private const val ACCEPT_DEADLINE_NS = 10L * 60L * 1_000_000_000L
        private const val VIDEO_SEND_BUFFER = 64 * 1024
        private const val MAX_PENDING_WRITE_BYTES = 1024 * 1024
        private const val BEACON_INTERVAL_MS = 2000L
        private val HOST_BEACON = "CarlifeHost".toByteArray(StandardCharsets.US_ASCII)
        private val READY_BEACON = "{\"carlifehost\":\"carlife\",\"status\":\"ready\"}"
            .toByteArray(StandardCharsets.US_ASCII)
        private val CHANNEL_PORTS = linkedMapOf(
            Wire.CH_CMD to 7240,
            Wire.CH_VIDEO to 8240,
            Wire.CH_MEDIA to 9240,
            Wire.CH_TTS to 9241,
            Wire.CH_VR to 9242,
            Wire.CH_TOUCH to 9340,
        )
    }
}
