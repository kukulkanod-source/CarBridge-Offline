package com.example.carbridge.scrcpy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.adb.AdbChannel
import com.example.carbridge.adb.AdbSession
import java.io.DataInputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A control-only shell process: no second video encoder or decoder. */
class ScrcpyInputSession(private val context: Context, private val displayId: Int,
    width: Int, height: Int, private val onFailure: (String) -> Unit) {
    @Volatile private var geometry = Pair(width, height)
    private val touchRevision = java.util.concurrent.atomic.AtomicLong()
    private var waitForDown = false // main thread; discard a gesture interrupted by resize
    private val activePointers = HashMap<Long, Pair<Int, Int>>() // worker thread only
    private val closed = AtomicBoolean()
    private val channelsLock = Any()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(256))
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var channel: AdbChannel? = null
    @Volatile private var shell: AdbChannel? = null
    @Volatile private var upload: AdbChannel? = null
    private val startupTimeout = Runnable { fail("Истекло время подключения управления окном") }

    fun start(onReady: () -> Unit) {
        require(displayId > 0)
        main.postDelayed(startupTimeout, 20000)
        execute {
            val hex = "%08x".format(SecureRandom().nextInt(Int.MAX_VALUE))
            val remote = "/data/local/tmp/carbridge-input-$hex.jar"
            val bytes = context.assets.open(ScrcpySource.ASSET_NAME).use { it.readBytes() }
            val sync = retain(AdbSession.open("sync:", 5000)) { upload = it }
            try { ScrcpySync.send(sync.input, sync.output, remote, bytes) }
            finally { sync.close(); upload = null }
            if (closed.get()) return@execute
            val cmd = "CLASSPATH='$remote' app_process / com.genymobile.scrcpy.Server ${ScrcpySource.SERVER_VERSION} " +
                "scid=$hex video=false audio=false control=true display_id=$displayId " +
                "tunnel_forward=true send_dummy_byte=true send_device_meta=false " +
                "power_on=false power_off_on_close=false clipboard_autosync=false cleanup=true"
            val process = retain(AdbSession.open("shell:$cmd", 5000)) { shell = it }
            Thread({
                runCatching { process.input.bufferedReader().useLines { lines -> lines.forEach {
                    if (!closed.get()) SessionDiagnostics.event("HU content control: ${it.take(280)}")
                } } }
                if (!closed.get()) fail("Процесс управления окном завершился")
            }, "hu-content-shell").start()
            val deadline = SystemClock.elapsedRealtime() + 15000
            while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
                try { retain(AdbSession.open("localabstract:scrcpy_$hex", 1000)) { channel = it }; break }
                catch (_: IOException) { Thread.sleep(150) }
            }
            val control = channel ?: throw IOException("Не открыт канал управления окном")
            if (control.input.read() != 0) throw IOException("Неверный ответ управления окном")
            if (closed.get()) return@execute
            main.removeCallbacks(startupTimeout)
            main.post { if (!closed.get()) onReady() }
            Thread({
                runCatching { val input = DataInputStream(control.input)
                    while (!closed.get()) ScrcpyProtocol.readDeviceMessage(input) }
                if (!closed.get()) fail("Канал управления окном закрыт")
            }, "hu-content-input").start()
        }
    }

    fun touch(event: MotionEvent, viewWidth: Int, viewHeight: Int) {
        if (closed.get() || channel == null || viewWidth <= 0 || viewHeight <= 0) return
        if (event.actionMasked == MotionEvent.ACTION_DOWN) waitForDown = false
        if (waitForDown) return
        val revision = touchRevision.get()
        val (width, height) = geometry
        val points = ArrayList<Triple<Long, Int, Pair<Int, Int>>>()
        val packets = ArrayList<ByteArray>()
        fun pointer(index: Int, action: Int) {
            val id = event.getPointerId(index).toLong()
            val x = (event.getX(index) * width / viewWidth).toInt().coerceIn(0, width - 1)
            val y = (event.getY(index) * height / viewHeight).toInt().coerceIn(0, height - 1)
            points.add(Triple(id, action, Pair(x, y)))
            packets.add(ScrcpyProtocol.touchPointer(action, id, x, y, width, height))
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointer(event.actionIndex, 0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointer(event.actionIndex, 1)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) pointer(i, 2)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until event.pointerCount) pointer(i, 1)
        }
        execute { val output = channel?.output ?: return@execute
            if (touchRevision.get() != revision) return@execute
            packets.forEach { output.write(it) }; output.flush()
            points.forEach { (id, action, xy) -> if (action == 1) activePointers.remove(id) else activePointers[id] = xy }
        }
    }
    fun resize(width: Int, height: Int) {
        if (geometry == Pair(width, height)) return
        waitForDown = true
        touchRevision.incrementAndGet()
        geometry = Pair(width, height)
        execute {
            val output = channel?.output
            if (output != null) {
                activePointers.forEach { (id, xy) -> output.write(ScrcpyProtocol.touchPointer(1, id,
                    xy.first.coerceIn(0, width - 1), xy.second.coerceIn(0, height - 1), width, height)) }
                output.flush()
            }
            activePointers.clear()
        }
    }
    fun paste(text: String): Boolean {
        if (closed.get() || channel == null) return false
        val packet = ScrcpyProtocol.pasteText(text)
        execute { channel?.output?.apply { write(packet); flush() } }
        return true
    }
    fun key(code: Int) = execute {
        channel?.output?.apply { write(ScrcpyProtocol.key(0, code)); write(ScrcpyProtocol.key(1, code)); flush() }
    }
    private fun execute(action: () -> Unit) {
        if (closed.get()) return
        try { worker.execute { if (!closed.get()) runCatching(action).onFailure { fail(it.message ?: it.javaClass.simpleName) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { if (!closed.get()) fail("Переполнена очередь управления окном") }
    }
    private fun fail(message: String) {
        if (!closed.compareAndSet(false, true)) return
        dispose()
        main.post { onFailure(message) }
    }
    fun close() { if (closed.compareAndSet(false, true)) dispose() }
    private fun retain(value: AdbChannel, save: (AdbChannel) -> Unit): AdbChannel {
        synchronized(channelsLock) {
            if (!closed.get()) { save(value); return value }
        }
        value.close()
        throw IOException("Управление окном остановлено")
    }
    private fun dispose() {
        main.removeCallbacks(startupTimeout)
        worker.shutdownNow()
        val held = synchronized(channelsLock) {
            listOfNotNull(channel, shell, upload).also { channel = null; shell = null; upload = null }
        }
        Thread({ held.forEach { runCatching { it.close() } } }, "hu-content-close").start()
    }
}
