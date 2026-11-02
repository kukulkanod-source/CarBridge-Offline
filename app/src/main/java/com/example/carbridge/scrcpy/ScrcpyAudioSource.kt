package com.example.carbridge.scrcpy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.adb.AdbChannel
import com.example.carbridge.adb.AdbSession
import java.io.DataInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Independent output capture. Closing it restores local routing without stopping video. */
class ScrcpyAudioSource(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onFormatReady(): Boolean
        fun onPcm(pcm: ByteArray)
        fun onError(message: String)
    }
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val channels = ArrayList<AdbChannel>()
    private var worker: Thread? = null
    @Volatile private var remotePath: String? = null
    @Volatile var lastAudibleMs = SystemClock.elapsedRealtime()
        private set
    private val startupTimeout = Runnable { fail("Захват USB-звука не запустился за 20 секунд") }
    private val health = object : Runnable {
        override fun run() {
            if (closed.get()) return
            if (SystemClock.elapsedRealtime() - lastPacketMs > 5000) fail("Поток USB-звука остановился")
            else main.postDelayed(this, 1000)
        }
    }
    @Volatile private var lastPacketMs = 0L
    fun start() {
        if (!started.compareAndSet(false, true)) return
        main.postDelayed(startupTimeout, 20000)
        worker = Thread({
            try {
                val hex = "%08x".format(SecureRandom().nextInt(Int.MAX_VALUE))
                val remote = "/data/local/tmp/carbridge-audio-$hex.jar"
                remotePath = remote
                val bytes = context.assets.open(ScrcpySource.ASSET_NAME).use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                check(sha == "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536") { "Неверная контрольная сумма аудиосервера" }
                val sync = retain(AdbSession.open("sync:", 5000))
                try { ScrcpySync.send(sync.input, sync.output, remote, bytes) } finally { sync.close() }
                checkOpen()
                val command = "CLASSPATH='$remote' app_process / com.genymobile.scrcpy.Server ${ScrcpySource.SERVER_VERSION} " +
                    "scid=$hex video=false audio=true control=false audio_codec=raw audio_source=output " +
                    "tunnel_forward=true send_dummy_byte=true send_device_meta=false send_stream_meta=true " +
                    "send_frame_meta=false power_on=false cleanup=true"
                val shell = retain(AdbSession.open("shell:$command", 5000))
                Thread({
                    runCatching { shell.input.bufferedReader().useLines { lines -> lines.forEach {
                        if (!closed.get()) SessionDiagnostics.event("USB audio server: ${it.take(300)}")
                    } } }
                    if (!closed.get()) fail("Аудиосервер завершился")
                }, "usb-audio-shell").start()
                var socket: AdbChannel? = null
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                    try { socket = retain(AdbSession.open("localabstract:scrcpy_$hex", 1000)); break }
                    catch (_: IOException) { Thread.sleep(150) }
                }
                val input = DataInputStream(socket?.input ?: throw IOException("Не открыт канал аудио"))
                if (input.readUnsignedByte() != 0) throw IOException("Неверное начало аудиопотока")
                val codec = input.readInt()
                if (codec == 0 || codec == 1) throw IOException("Android отказал в захвате системного звука")
                if (codec != 0x00726177) throw IOException("Ожидался PCM 16-bit LE")
                checkOpen()
                if (!listener.onFormatReady()) throw IOException("ГУ не готово принимать аудио")
                main.removeCallbacks(startupTimeout)
                lastPacketMs = SystemClock.elapsedRealtime()
                main.post(health)
                SessionDiagnostics.event("USB audio capture ready; source=output; pcm=48000/stereo/s16le; packetMs=20")
                var totalBytes = 0L
                var lastStats = SystemClock.elapsedRealtime()
                var intervalBytes = 0L
                var peak = 0
                while (!closed.get()) {
                    val pcm = ByteArray(3840) // 960 stereo frames: 20 ms at 48 kHz.
                    input.readFully(pcm)
                    lastPacketMs = SystemClock.elapsedRealtime()
                    var framePeak = 0
                    for (i in pcm.indices step 2) {
                        val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                        framePeak = maxOf(framePeak, kotlin.math.abs(sample))
                    }
                    if (framePeak > 16) lastAudibleMs = lastPacketMs
                    peak = maxOf(peak, framePeak)
                    checkOpen()
                    listener.onPcm(pcm)
                    totalBytes += pcm.size; intervalBytes += pcm.size
                    if (lastPacketMs - lastStats >= 10000) {
                        SessionDiagnostics.event("USB audio capture stats; bytes=$intervalBytes; totalBytes=$totalBytes; peak=$peak")
                        lastStats = lastPacketMs; intervalBytes = 0; peak = 0
                    }
                }
            } catch (error: Exception) { if (!closed.get()) fail(error.message ?: error.javaClass.simpleName) }
            finally { close() }
        }, "usb-audio-pcm").also { it.start() }
    }
    private fun checkOpen() { if (closed.get()) throw IOException("Аудиозахват остановлен") }
    private fun retain(channel: AdbChannel): AdbChannel {
        synchronized(lock) { if (!closed.get()) { channels.add(channel); return channel } }
        channel.close(); throw IOException("Аудиозахват остановлен")
    }
    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        dispose()
        main.post { listener.onError(message.take(180)) }
    }
    fun close() { if (closed.compareAndSet(false, true)) dispose() }
    private fun dispose() {
        main.removeCallbacks(startupTimeout); main.removeCallbacks(health)
        worker?.takeIf { it !== Thread.currentThread() }?.interrupt()
        val held = synchronized(lock) { channels.toList().also { channels.clear() } }
        Thread({
            held.forEach { runCatching { it.close() } }
            remotePath?.let { remote ->
                if (AdbSession.isConnected()) runCatching {
                    AdbSession.open("shell:rm -f '$remote'", 2000).use { it.input.readBytes() }
                }
            }
        }, "usb-audio-close").start()
    }
}
