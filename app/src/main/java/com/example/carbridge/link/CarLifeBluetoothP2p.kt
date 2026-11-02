package com.example.carbridge.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.carbridge.link.proto.PbReader
import com.example.carbridge.link.proto.PbWriter
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Starts the Bluetooth negotiation used by CarLife head units and joins their Wi-Fi Direct group. */
internal class CarLifeBluetoothP2p(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onGroupReady: (Inet4Address, Int, () -> Unit) -> Unit,
    private val onGroupLost: () -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "carlife-bt-p2p").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val listeners = mutableListOf<BluetoothServerSocket>()
    private val activeSocket = java.util.concurrent.atomic.AtomicReference<BluetoothSocket?>()
    private val groupReady = AtomicBoolean(false)
    private val transportReady = AtomicBoolean(false)
    @Volatile private var ipRequested = false
    @Volatile private var phoneIp: String? = null
    @Volatile private var target: P2pTarget? = null
    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var p2pReceiverRegistered = false

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        app.getSystemService(BluetoothManager::class.java)?.adapter
    }
    private val p2pManager: WifiP2pManager? by lazy {
        app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                        postStatus("Wi-Fi Direct отключён на телефоне. Включите Wi-Fi и повторите запуск CarLife.")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        try {
            requirePermissions()
            val adapter = bluetoothAdapter ?: throw IOException("В телефоне нет Bluetooth")
            if (!adapter.isEnabled) throw IOException("Включите Bluetooth и повторите запуск CarLife по Wi-Fi")
            val manager = p2pManager ?: throw IOException("Телефон не поддерживает Wi-Fi Direct")
            channel = manager.initialize(app, Looper.getMainLooper()) {
                if (!closed.get()) postStatus("Канал Wi-Fi Direct сброшен Android. Перезапустите беспроводное подключение.")
            } ?: throw IOException("Android не открыл канал Wi-Fi Direct")
            ContextCompat.registerReceiver(app, p2pReceiver, IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            p2pReceiverRegistered = true

            UUIDS.forEach { uuid ->
                val server = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, uuid)
                synchronized(listeners) { listeners += server }
                io.execute { acceptLoop(uuid, server) }
            }
            postStatus("Жду Bluetooth-согласование CarLife от ГУ. Включите беспроводной CarLife на ГУ.")
        } catch (error: Exception) {
            postTerminalStatus("Не удалось начать CarLife по Bluetooth: ${error.message ?: error.javaClass.simpleName}")
            close()
        }
    }

    private fun acceptLoop(uuid: UUID, server: BluetoothServerSocket) {
        try {
            val socket = server.accept()
            if (closed.get() || !activeSocket.compareAndSet(null, socket)) {
                runCatching { socket.close() }
                return
            }
            closeServers()
            postStatus("ГУ подключилось по Bluetooth CarLife. Обмениваюсь параметрами Wi-Fi Direct…")
            io.execute { readBluetooth(socket, uuid) }
        } catch (error: Exception) {
            if (!closed.get() && isListenerActive(server)) {
                Log.w(TAG, "Bluetooth accept failed ($uuid)", error)
                postStatus("Bluetooth CarLife не принял соединение: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun readBluetooth(socket: BluetoothSocket, uuid: UUID) {
        val parser = BtFrameParser()
        val buffer = ByteArray(4096)
        try {
            // The HU expects the wireless-info request immediately after RFCOMM opens.
            Thread.sleep(300L)
            send(socket, MD_WIRELESS_INFO_REQUEST)
            val input = socket.inputStream
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) throw IOException("ГУ закрыло Bluetooth-канал CarLife")
                if (count == 0) continue
                parser.feed(buffer, count).forEach(::onBluetoothFrame)
            }
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.w(TAG, "Bluetooth protocol ended ($uuid)", error)
                postStatus("Bluetooth CarLife прервано: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    private fun onBluetoothFrame(frame: BtFrame) {
        when (frame.type) {
            HU_WIRELESS_INFO -> {
                val parsed = parseP2pTarget(frame.body)
                if (parsed == null) {
                    postStatus("ГУ передало параметры Wi-Fi Direct, но формат не распознан")
                } else {
                    target = parsed
                    postStatus("ГУ передало параметры Wi-Fi Direct. Подключаюсь к группе ГУ…")
                    joinGroup(parsed)
                }
            }
            HU_IP_REQUEST -> {
                ipRequested = true
                sendPhoneIpIfReady()
            }
            HU_STATUS -> Log.i(TAG, "CarLife HU status received (${frame.body.size} bytes)")
            HU_TARGET_INFO -> Log.i(TAG, "CarLife HU target info received (${frame.body.size} bytes)")
            else -> Log.d(TAG, "Ignoring Bluetooth CarLife message 0x${frame.type.toString(16)}")
        }
    }

    private fun joinGroup(target: P2pTarget) {
        if (!target.canJoin) {
            postStatus("ГУ передало некорректные параметры Wi-Fi Direct; проверьте Bluetooth-сопряжение с ГУ")
            return
        }
        val manager = p2pManager ?: return
        val currentChannel = channel ?: return
        try {
            val builder = WifiP2pConfig.Builder()
                .setNetworkName(target.ssid)
                .setPassphrase(target.passphrase)
            target.frequencyMhz?.let(builder::setGroupOperatingFrequency)
            manager.connect(currentChannel, builder.build(), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    postStatus("Запросил Wi-Fi Direct у ГУ; ожидаю адрес телефона в группе…")
                    requestConnectionInfo()
                }
                override fun onFailure(reason: Int) {
                    postStatus("Wi-Fi Direct не подключился (код $reason). Повторите запуск беспроводного CarLife на ГУ.")
                }
            })
        } catch (error: Exception) {
            postStatus("Не удалось подключиться к Wi-Fi Direct ГУ: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun requestConnectionInfo() {
        val manager = p2pManager ?: return
        val currentChannel = channel ?: return
        try {
            manager.requestConnectionInfo(currentChannel) connectionInfo@{ info ->
                if (closed.get()) return@connectionInfo
                if (!info.groupFormed) {
                    if (groupReady.compareAndSet(true, false)) {
                        phoneIp = null
                        transportReady.set(false)
                        handler.post(onGroupLost)
                        postStatus("Wi-Fi Direct связь с ГУ потеряна")
                    }
                    return@connectionInfo
                }
                manager.requestGroupInfo(currentChannel) groupInfo@{ group ->
                    io.execute { resolveLocalAddress(group, info) }
                }
            }
        } catch (error: SecurityException) {
            postStatus("Android запретил доступ к Wi-Fi Direct. Разрешите CarBridge доступ к ближайшим устройствам.")
        } catch (error: Exception) {
            Log.w(TAG, "requestConnectionInfo failed", error)
        }
    }

    private fun resolveLocalAddress(group: WifiP2pGroup?, info: WifiP2pInfo) {
        if (closed.get() || !info.groupFormed || groupReady.get()) return
        val preferredName = runCatching { group?.`interface` }.getOrNull()
        repeat(24) {
            if (closed.get() || groupReady.get()) return
            val local = findP2pAddress(preferredName)
            if (local != null) {
                phoneIp = local.address.hostAddress
                groupReady.set(true)
                postStatus("Wi-Fi Direct подключён (${local.name}, ${local.address.hostAddress}). Запускаю каналы CarLife…")
                handler.post {
                    if (!closed.get()) onGroupReady(local.address, local.prefix) {
                        if (!closed.get()) {
                            transportReady.set(true)
                            sendPhoneIpIfReady()
                        }
                    }
                }
                return
            }
            try { Thread.sleep(500L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
        }
        postStatus("Wi-Fi Direct сформировал группу, но Android не выдал IPv4-адрес интерфейса P2P")
    }

    private fun findP2pAddress(preferredName: String?): P2pAddress? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        val ordered = interfaces.sortedBy { if (it.name == preferredName) 0 else if (it.name.startsWith("p2p", true)) 1 else 2 }
        for (iface in ordered) {
            if (!iface.name.startsWith("p2p", true) || runCatching { !iface.isUp }.getOrDefault(true)) continue
            for (binding in iface.interfaceAddresses) {
                val address = binding.address as? Inet4Address ?: continue
                if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) continue
                val prefix = binding.networkPrefixLength.toInt().coerceIn(1, 32)
                return P2pAddress(iface.name, address, prefix)
            }
        }
        return null
    }

    private fun sendPhoneIpIfReady() {
        if (!ipRequested || !transportReady.get()) return
        val ip = phoneIp ?: return
        val socket = activeSocket.get() ?: return
        try {
            send(socket, MD_WIFI_IP, PbWriter().string(1, ip).toByteArray())
            ipRequested = false
            postStatus("Передал ГУ адрес телефона $ip. Ожидаю TCP-каналы CarLife…")
        } catch (error: Exception) {
            Log.w(TAG, "Could not send P2P IP to head unit", error)
            postStatus("Не удалось сообщить ГУ адрес телефона: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun send(socket: BluetoothSocket, type: Int, body: ByteArray = ByteArray(0)) {
        if (body.size > MAX_BODY) throw IOException("Bluetooth CarLife message is too large")
        val frame = ByteArray(HEADER_BYTES + body.size)
        putU16(frame, 0, body.size)
        putU32(frame, 4, type)
        body.copyInto(frame, HEADER_BYTES)
        synchronized(socket) {
            socket.outputStream.write(frame)
            socket.outputStream.flush()
        }
    }

    private fun parseP2pTarget(body: ByteArray): P2pTarget? = runCatching {
        val fields = PbReader.parse(body) ?: return null
        val parts = PbReader.str(fields, 4)?.split(',') ?: return null
        val ssid = parts.getOrNull(0)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val passphrase = parts.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val frequency = parts.getOrNull(2)?.trim()?.toIntOrNull()?.takeIf { it in 2401..5900 }
        P2pTarget(PbReader.str(fields, 1)?.trim(), ssid, passphrase, frequency)
    }.getOrNull()

    private fun requirePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Разрешите доступ CarBridge к Bluetooth")
        }
        val wifiPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(app, wifiPermission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Разрешите CarBridge доступ к ближайшим устройствам")
        }
    }

    private fun closeServers() {
        synchronized(listeners) {
            listeners.forEach { runCatching { it.close() } }
            listeners.clear()
        }
    }

    private fun isListenerActive(server: BluetoothServerSocket): Boolean =
        synchronized(listeners) { server in listeners }

    private fun postStatus(message: String) = handler.post { if (!closed.get()) onStatus(message) }

    private fun postTerminalStatus(message: String) = handler.post { onStatus(message) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeServers()
        activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
        if (p2pReceiverRegistered) {
            runCatching { app.unregisterReceiver(p2pReceiver) }
            p2pReceiverRegistered = false
        }
        val manager = p2pManager
        val currentChannel = channel
        if (manager != null && currentChannel != null) {
            runCatching { manager.removeGroup(currentChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            }) }
        }
        phoneIp = null
        io.shutdownNow()
    }

    private data class P2pTarget(
        val deviceName: String?,
        val ssid: String,
        val passphrase: String,
        val frequencyMhz: Int?,
    ) {
        val canJoin: Boolean get() = ssid.startsWith("DIRECT-") && passphrase.length in 8..63
    }

    private data class P2pAddress(val name: String, val address: Inet4Address, val prefix: Int)

    private class BtFrame(val type: Int, val body: ByteArray)

    private class BtFrameParser {
        private var pending = ByteArray(0)

        fun feed(chunk: ByteArray, length: Int): List<BtFrame> {
            if (length !in 1..chunk.size) return emptyList()
            if (pending.size + length > MAX_BODY + HEADER_BYTES) pending = ByteArray(0)
            pending += chunk.copyOf(length)
            val frames = ArrayList<BtFrame>()
            var offset = 0
            while (pending.size - offset >= HEADER_BYTES) {
                val bodyLength = u16(pending, offset)
                val channel = u16(pending, offset + 2)
                if (bodyLength > MAX_BODY || channel != 0) {
                    offset++
                    continue
                }
                val frameEnd = offset + HEADER_BYTES + bodyLength
                if (frameEnd > pending.size) break
                frames += BtFrame(u32(pending, offset + 4), pending.copyOfRange(offset + HEADER_BYTES, frameEnd))
                offset = frameEnd
            }
            pending = pending.copyOfRange(offset, pending.size)
            return frames
        }
    }

    companion object {
        private const val TAG = "CarBridge/BT-P2P"
        private const val SERVICE_NAME = "carlife"
        private const val HEADER_BYTES = 8
        private const val MAX_BODY = 4096
        private const val MD_WIRELESS_INFO_REQUEST = 0x00100001
        private const val MD_WIFI_IP = 0x00100007
        private const val HU_WIRELESS_INFO = 0x00108002
        private const val HU_TARGET_INFO = 0x00108005
        private const val HU_IP_REQUEST = 0x00108006
        private const val HU_STATUS = 0x00108009
        private val UUIDS = listOf(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"),
            UUID.fromString("a45bc7e5-bb50-4949-9de1-f78299cf6d78"),
        )

        private fun u16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        private fun u32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)

        private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 8).toByte()
            bytes[offset + 1] = value.toByte()
        }

        private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value ushr 24).toByte()
            bytes[offset + 1] = (value ushr 16).toByte()
            bytes[offset + 2] = (value ushr 8).toByte()
            bytes[offset + 3] = value.toByte()
        }
    }
}
