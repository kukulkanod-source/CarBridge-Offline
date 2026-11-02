package com.example.carbridge.adb

import android.content.Context
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Persistent, in-process ADB client for Android 11+ Wireless debugging.
 * Pairing and connection ports are different ports shown by Android settings.
 * pair/connect/open block; callers must use a worker. Channels may run in parallel.
 */
object AdbSession {
    private const val TAG = "CarBridge/ADB"
    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val OPERATION_LOCK_TIMEOUT_MS = 1500L
    private val operationLock = ReentrantLock()
    private val stateLock = Any()
    private val identityLock = Any()
    @Volatile private var identity: AdbIdentity? = null
    private var identityTask: Future<AdbIdentity>? = null
    private val identityWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "adb-identity").apply { isDaemon = true }
    }
    private val cleanupWorker = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "adb-pairing-cleanup").apply { isDaemon = true }
    }
    @Volatile private var connection: AdbConnection? = null
    @Volatile private var pairing: PairingConnectionCtx? = null
    @Volatile private var connectedEndpoint: AdbEndpoint? = null
    private var generation = 0L
    private var cancellationEpoch = 0L
    private val channels = ConcurrentHashMap.newKeySet<LibraryChannel>()

    @Volatile var lastError: String? = null
        private set

    fun pair(context: Context, host: String, pairingPort: Int, code: String): Boolean {
        requireWorker()
        val address = validateEndpoint(host, pairingPort)
        require(code.length == 6 && code.all { it in '0'..'9' }) { "Код сопряжения должен содержать 6 цифр" }
        return operation {
            val epoch = synchronized(stateLock) { cancellationEpoch }
            disconnectInternal(false)
            val operationGeneration = synchronized(stateLock) { generation }
            val keys = keys(context.applicationContext)
            checkCancellation(epoch)
            val client = try {
                PairingConnectionCtx(address, pairingPort, code.toByteArray(Charsets.UTF_8),
                    keys.privateKey, keys.certificate, "CarBridge")
            } catch (error: Exception) { throw failure("Не удалось подготовить TLS ADB", error) }
            synchronized(stateLock) {
                if (generation != operationGeneration) {
                    client.close()
                    throw AdbOperationCancelledException()
                }
                pairing = client
            }
            try {
                client.start()
                synchronized(stateLock) {
                    if (generation != operationGeneration) throw AdbOperationCancelledException()
                }
                lastError = null
                Log.i(TAG, "Сопряжение ADB завершено")
                true
            } catch (error: Exception) {
                // The code and identity are never logged or included in our error messages.
                throw failure("Сопряжение ADB не удалось. Проверьте код и порт сопряжения", error)
            } catch (error: LinkageError) {
                lastError = "Нативная библиотека сопряжения ADB несовместима с этим устройством"
                throw IOException(lastError, error)
            } finally {
                synchronized(stateLock) { if (pairing === client) pairing = null }
                client.close()
            }
        }
    }

    @JvmOverloads
    fun connect(context: Context, host: String, connectPort: Int,
                mode: AdbConnectionMode = AdbConnectionMode.TLS,
                timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        requireWorker()
        require(timeoutMs in 1..120_000L) { "Некорректный таймаут подключения ADB" }
        val address = validateEndpoint(if (mode == AdbConnectionMode.LOCAL_TCP) "127.0.0.1" else host, connectPort)
        val deadline = deadline(timeoutMs)
        return operation(minOf(OPERATION_LOCK_TIMEOUT_MS, timeoutMs)) {
            connectInternal(context, address, connectPort, mode, remainingMillis(deadline))
        }
    }

    @JvmOverloads
    fun connectLocal(context: Context, port: Int, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean =
        connect(context, "127.0.0.1", port, AdbConnectionMode.LOCAL_TCP, timeoutMs)

    private fun connectInternal(context: Context, address: String, connectPort: Int,
                                mode: AdbConnectionMode, timeoutMs: Long): Boolean {
            val deadline = deadline(timeoutMs)
            val epoch = synchronized(stateLock) { cancellationEpoch }
            if (isConnectedFor(address, connectPort, mode)) return true
            disconnectInternal(false)
            val operationGeneration = synchronized(stateLock) { generation }
            val keys = keys(context.applicationContext, remainingMillis(deadline))
            checkCancellation(epoch)
            synchronized(stateLock) {
                if (generation != operationGeneration || Thread.currentThread().isInterrupted) {
                    throw AdbOperationCancelledException()
                }
            }
            var candidate: AdbConnection? = null
            try {
                candidate = AdbConnection.Builder(address, connectPort)
                    .setApi(Build.VERSION.SDK_INT)
                    .setPrivateKey(keys.privateKey)
                    .setCertificate(keys.certificate)
                    .setDeviceName("CarBridge")
                    .setConnectTimeoutMillis(remainingMillis(deadline).toInt())
                    .build()
                synchronized(stateLock) {
                    if (generation != operationGeneration) throw AdbOperationCancelledException()
                    connection = candidate
                }
                val success = candidate.connect(remainingMillis(deadline), TimeUnit.MILLISECONDS, true)
                if (!success) {
                    lastError = "ADB не ответил вовремя. Проверьте порт подключения"
                    return false
                }
                AdbTransportPolicy.requireAuthenticatedMode(candidate, mode)
                synchronized(stateLock) {
                    if (generation != operationGeneration || connection !== candidate) {
                        throw AdbOperationCancelledException()
                    }
                    connectedEndpoint = AdbEndpoint(address, connectPort, mode)
                }
                lastError = null
                Log.i(TAG, "ADB подключён: $mode")
                return true
            } catch (error: Exception) {
                val message = if (mode == AdbConnectionMode.LOCAL_TCP)
                    "Локальный ADB недоступен. После перезагрузки подготовьте офлайн режим через TLS"
                    else "Не удалось подключиться к TLS ADB. Проверьте порт подключения и сопряжение"
                throw failure(message, error)
            } finally {
                if (candidate != null && (connectedEndpoint != AdbEndpoint(address, connectPort, mode) || connection !== candidate)) {
                    synchronized(stateLock) { if (connection === candidate) connection = null }
                    runCatching { candidate.close() }
                }
            }
    }

    fun isConnected(): Boolean = connectedEndpoint != null &&
        connection?.let { it.isConnected && it.isConnectionEstablished } == true

    val connectionMode: AdbConnectionMode?
        get() = connectedEndpoint?.mode?.takeIf { isConnected() }

    val isLocalTcp: Boolean get() = connectionMode == AdbConnectionMode.LOCAL_TCP

    fun isConnectedFor(host: String, port: Int, mode: AdbConnectionMode = AdbConnectionMode.TLS): Boolean {
        val address = if (mode == AdbConnectionMode.LOCAL_TCP) "127.0.0.1"
            else host.trim().removePrefix("[").removeSuffix("]")
        return isConnected() && connectedEndpoint == AdbEndpoint(address, port, mode)
    }

    /** A worker-only round trip; TCP flags alone can stay true briefly while adbd restarts. */
    @JvmOverloads
    fun probeConnected(host: String, port: Int, mode: AdbConnectionMode = AdbConnectionMode.TLS,
                       timeoutMs: Long = 2000L): Boolean {
        requireWorker()
        require(timeoutMs in 1..120_000L) { "Некорректный таймаут ADB" }
        val deadline = deadline(timeoutMs)
        return operation(minOf(OPERATION_LOCK_TIMEOUT_MS, timeoutMs)) {
            if (!isConnectedFor(host, port, mode)) return@operation false
            val expected = connection ?: return@operation false
            try {
                val stream = expected.open("sync:", remainingMillis(deadline))
                try { stream.close(remainingMillis(deadline)) }
                catch (error: Exception) { expected.abort(); throw error }
                connection === expected && isConnectedFor(host, port, mode)
            } catch (error: IOException) {
                invalidateConnection(expected)
                lastError = "ADB не ответил на проверку соединения"
                false
            } catch (error: InterruptedException) {
                invalidateConnection(expected)
                throw interrupted(error)
            }
        }
    }

    /** Prepare at home through an already-authorized TLS session. No preference is saved here. */
    @JvmOverloads
    fun activateOffline(context: Context, port: Int = 0): Int {
        requireWorker()
        return operation {
            requireUsbDebugging(context)
            if (connectionMode != AdbConnectionMode.TLS) {
                throw IOException("Для подготовки офлайн режима сначала подключитесь через TLS ADB")
            }
            requireLocalSession()
            val epoch = synchronized(stateLock) { cancellationEpoch }
            val activatedPort = OfflineAdbActivation.activate(port,
                openService = { open(it, 3000L) },
                disconnectTransport = { disconnectInternal(false) },
                reconnectLocal = { selected, timeout ->
                    connectInternal(context, "127.0.0.1", selected, AdbConnectionMode.LOCAL_TCP, timeout)
                },
                checkCancelled = { checkCancellation(epoch) })
            lastError = null
            Log.i(TAG, "Офлайн ADB подтверждён на локальном порту $activatedPort")
            activatedPort
        }
    }

    /** Require the USB-mode ACK and refusal of the previous TCP port before reporting disabled. */
    fun deactivateOffline(context: Context, port: Int): Boolean {
        requireWorker()
        require(port in 1..65535) { "Укажите ранее подготовленный локальный порт ADB" }
        return operation {
            requireUsbDebugging(context)
            if (!isConnected() && !connectInternal(context, "127.0.0.1", port,
                    AdbConnectionMode.LOCAL_TCP, CONNECT_TIMEOUT_MS)) {
                throw IOException("Нет авторизованного ADB для отключения офлайн режима")
            }
            requireLocalSession()
            val endpoint = connectedEndpoint
            if (endpoint?.mode == AdbConnectionMode.LOCAL_TCP && endpoint.port != port) {
                throw IOException("Укажите действующий локальный порт ADB ${endpoint.port}")
            }
            val epoch = synchronized(stateLock) { cancellationEpoch }
            OfflineAdbActivation.deactivate(port,
                openService = { open(it, 3000L) },
                disconnectTransport = { disconnectInternal(false) },
                checkCancelled = { checkCancellation(epoch) })
            lastError = null
            Log.i(TAG, "Отключение офлайн ADB подтверждено")
            true
        }
    }

    fun open(destination: String): AdbChannel = open(destination, CONNECT_TIMEOUT_MS)

    fun open(destination: String, timeoutMs: Long): AdbChannel {
        requireWorker()
        require(destination.isNotBlank() && '\u0000' !in destination) { "Некорректный сервис ADB" }
        require(timeoutMs in 1..120_000L) { "Некорректный таймаут ADB" }
        if (!isConnected()) throw IOException("ADB не подключён")
        val current = connection?.takeIf { it.isConnected && it.isConnectionEstablished }
            ?: throw IOException("ADB не подключён")
        try {
            val stream = current.open(destination, timeoutMs)
            val channel = LibraryChannel(stream)
            val accepted = synchronized(stateLock) {
                (connection === current).also { if (it) channels.add(channel) }
            }
            if (!accepted) {
                channel.close()
                throw IOException("Сессия ADB завершена")
            }
            return channel
        } catch (error: Exception) {
            throw failure("Не удалось открыть сервис ADB", error)
        }
    }

    /** Close all service channels and cancel an in-progress pair/connect; keep the identity. */
    fun disconnect() = disconnectInternal(true)

    private fun disconnectInternal(cancelOperation: Boolean) {
        val oldConnection: AdbConnection?
        val oldPairing: PairingConnectionCtx?
        val oldChannels: List<LibraryChannel>
        synchronized(stateLock) {
            generation++
            if (cancelOperation) cancellationEpoch++
            oldConnection = connection; connection = null
            oldPairing = pairing; pairing = null
            connectedEndpoint = null
            oldChannels = channels.toList(); channels.clear()
        }
        // Closing the socket first wakes readers/writers instead of waiting for a congested WRTE.
        oldConnection?.abort()
        // Native pairing cleanup must never delay cancellation or a later local reconnect.
        oldPairing?.let { client -> cleanupWorker.execute { runCatching { client.close() } } }
        runCatching { oldConnection?.close() }
        oldChannels.forEach { it.detach() }
        lastError = null
    }

    private fun invalidateConnection(expected: AdbConnection) {
        val oldChannels: List<LibraryChannel>
        synchronized(stateLock) {
            if (connection !== expected) return
            generation++
            connection = null
            connectedEndpoint = null
            oldChannels = channels.toList()
            channels.clear()
        }
        expected.abort()
        runCatching { expected.close() }
        oldChannels.forEach { it.detach() }
    }

    private fun requireUsbDebugging(context: Context) {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) != 1) {
            throw IOException("Включите «Отладка по USB» в настройках разработчика и оставьте её включённой")
        }
    }

    private fun requireLocalSession() {
        val endpoint = connectedEndpoint ?: throw IOException("Нет авторизованного ADB")
        val address = endpoint.host.substringBefore('%')
        val loopback = address == "127.0.0.1" || address == "::1" || address.equals("localhost", true)
        val ownAddress = loopback || NetworkInterface.getNetworkInterfaces()?.toList()?.any { network ->
            network.inetAddresses.toList().any { it.hostAddress?.substringBefore('%') == address }
        } == true
        if (!ownAddress) throw IOException("Подготовка офлайн ADB разрешена только для этого телефона")
    }

    private fun checkCancellation(epoch: Long) {
        if (Thread.currentThread().isInterrupted || synchronized(stateLock) { cancellationEpoch != epoch }) {
            throw AdbOperationCancelledException()
        }
    }

    private fun keys(context: Context, timeoutMs: Long = CONNECT_TIMEOUT_MS): AdbIdentity {
        identity?.let { return it }
        val task = synchronized(identityLock) {
            identityTask ?: identityWorker.submit<AdbIdentity> {
                AdbIdentity.loadOrCreate(context).also { identity = it }
            }.also { identityTask = it }
        }
        try { return task.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (error: InterruptedException) { throw interrupted(error) }
        catch (error: TimeoutException) {
            // A disk/key-generation task may finish later and remains the single identity owner.
            // Do not launch concurrent file writers or regenerate a key merely due to a timeout.
            throw SocketTimeoutException("Подготовка ключа ADB не завершилась вовремя")
        } catch (error: ExecutionException) {
            synchronized(identityLock) { if (identityTask === task) identityTask = null }
            throw IOException("Не удалось прочитать ключ ADB", error.cause ?: error)
        }
    }

    private fun <T> operation(lockTimeoutMs: Long = OPERATION_LOCK_TIMEOUT_MS, block: () -> T): T {
        val acquired = try { operationLock.tryLock(lockTimeoutMs, TimeUnit.MILLISECONDS) }
        catch (error: InterruptedException) { throw interrupted(error) }
        if (!acquired) throw IOException("ADB занят предыдущей операцией. Отмените её и повторите проверку")
        try { return block() }
        catch (error: IOException) { lastError = error.message; throw error }
        finally { operationLock.unlock() }
    }

    private fun deadline(timeoutMs: Long) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)

    private fun remainingMillis(deadline: Long): Long {
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw SocketTimeoutException("Операция ADB не завершилась вовремя")
        return maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining))
    }

    private fun requireWorker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Операцию ADB нужно выполнять в рабочем потоке" }
    }

    private fun validateEndpoint(host: String, port: Int): String {
        val result = host.trim().removePrefix("[").removeSuffix("]")
        require(result.isNotEmpty() && result.none { it.isWhitespace() } && '/' !in result && '\u0000' !in result) {
            "Укажите IP-адрес или имя хоста ADB без схемы и порта"
        }
        require(port in 1..65535) { "Порт ADB должен быть от 1 до 65535" }
        return result
    }

    private fun failure(message: String, error: Exception): IOException {
        if (error is InterruptedException) return interrupted(error)
        if (error is InterruptedIOException) {
            lastError = error.message
            return error
        }
        lastError = message
        return IOException(message, error)
    }

    private fun interrupted(error: InterruptedException): InterruptedIOException {
        Thread.currentThread().interrupt()
        lastError = "Операция ADB прервана"
        return InterruptedIOException(lastError).apply { initCause(error) }
    }

    private class LibraryChannel(private val stream: AdbStream) : AdbChannel {
        private val closed = AtomicBoolean(false)
        private val reader = stream.openInputStream()
        private val writer = stream.openOutputStream()
        override val input: InputStream = object : InputStream() {
            override fun read() = reader.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = reader.read(bytes, offset, length)
            override fun available() = reader.available()
            override fun close() = this@LibraryChannel.close()
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(value: Int) = writer.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = writer.write(bytes, offset, length)
            override fun flush() = writer.flush()
            override fun close() = this@LibraryChannel.close()
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            channels.remove(this)
            stream.close()
        }
        fun detach() {
            closed.set(true)
            channels.remove(this)
            stream.closeLocally()
        }
    }
}

internal class AdbOperationCancelledException : InterruptedIOException("Операция ADB отменена")
