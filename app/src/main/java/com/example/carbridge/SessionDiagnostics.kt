package com.example.carbridge

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** App-session diagnostics only. Never pass ADB keys, pairing codes or authentication payloads. */
object SessionDiagnostics {
    private const val SAMPLE_MS = 10_000L
    private const val MAX_FILES = 7
    private const val MAX_FILE_BYTES = 1_048_576L
    private const val FINAL_RESERVE_BYTES = 8192L
    private const val MAX_PENDING_EVENTS = 64
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "bridge-diagnostics") }
    private val sequence = AtomicLong()
    private var latestFile: File? = null // Diagnostics-worker thread only.
    private val sensitive = Regex(
        "pair(?:ing)?[ _-]*(?:code|key)|код(?:\\s+для)?\\s+сопряж|-----BEGIN|adbkey|private.?key|certificate|identity",
        RegexOption.IGNORE_CASE
    )

    @Volatile private var current: Session? = null

    private class Session(val context: Context, val details: String) {
        val frames = AtomicLong()
        val bytes = AtomicLong()
        val keys = AtomicLong()
        val createdNs = System.nanoTime()
        lateinit var tick: Runnable
        // Mutable fields below are accessed only by the diagnostics worker (pendingEvents: under lock).
        var pendingEvents = 0
        var file: File? = null
        var output: BufferedOutputStream? = null
        var written = 0L
        var capped = false
        var closed = false
        var lastNs = createdNs
        var lastFrames = 0L
        var lastBytes = 0L
        var lastKeys = 0L
    }

    /** Replaces an earlier session, flushing it first. Does no disk I/O on the calling thread. */
    fun start(context: Context, details: String) {
        synchronized(lock) {
            current?.let(::finishLocked)
            val session = Session(context.applicationContext, clean(details))
            session.tick = Runnable tick@{
                synchronized(lock) {
                    if (current !== session) return@tick
                    worker.execute { if (!session.closed) sample(session, final = false) }
                    main.postDelayed(session.tick, SAMPLE_MS)
                }
            }
            current = session
            worker.execute { open(session) }
            main.postDelayed(session.tick, SAMPLE_MS)
        }
    }

    /** Non-blocking and bounded even if the source sends unusually verbose server diagnostics. */
    fun event(message: String) {
        synchronized(lock) {
            val session = current ?: return
            if (session.pendingEvents >= MAX_PENDING_EVENTS) return
            val safeMessage = clean(message)
            session.pendingEvents++
            worker.execute {
                try {
                    if (!session.closed) write(session, "EVENT $safeMessage")
                } finally {
                    synchronized(lock) { session.pendingEvents-- }
                }
            }
        }
    }

    /** Called on the video path: three atomic additions, without formatting, locks or disk I/O. */
    fun frame(bytes: Int, key: Boolean) {
        val session = current ?: return
        session.frames.incrementAndGet()
        session.bytes.addAndGet(bytes.coerceAtLeast(0).toLong())
        if (key) session.keys.incrementAndGet()
    }

    fun stop() {
        synchronized(lock) {
            current?.let(::finishLocked)
            current = null
        }
    }

    /**
     * Capture before stop(). A delayed USB/ADB check then belongs to this exact session,
     * even if the next connection already has a new journal. No I/O on the calling thread.
     */
    fun captureTailRecorder(): (String) -> Unit {
        val session = synchronized(lock) { current } ?: return { }
        return { message ->
            synchronized(lock) {
                if (session.pendingEvents < MAX_PENDING_EVENTS) {
                    val safe = clean(message)
                    session.pendingEvents++
                    worker.execute {
                        try {
                            if (!session.closed) write(session, "EVENT $safe")
                            else DiagnosticTailFile.append(session.file,
                                line("POST_SESSION_EVENT $safe"), MAX_FILE_BYTES)
                        } finally {
                            synchronized(lock) { session.pendingEvents-- }
                        }
                    }
                }
            }
        }
    }

    private fun finishLocked(session: Session) {
        main.removeCallbacks(session.tick)
        worker.execute {
            if (!session.closed) {
                try {
                    sample(session, final = true)
                    write(session, "SESSION_END", final = true)
                } finally {
                    runCatching { session.output?.close() }
                    session.output = null
                    session.closed = true
                }
            }
        }
    }

    private fun open(session: Session) {
        try {
            val directory = directory(session.context)
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create diagnostics directory" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(directory, "session-$stamp-${sequence.incrementAndGet()}.log")
            session.output = BufferedOutputStream(FileOutputStream(file), 8192)
            session.file = file
            latestFile = file
            val info = session.context.packageManager.getPackageInfo(session.context.packageName, 0)
            write(session, "CarBridge session diagnostics; format=1")
            write(session, "app=${session.context.packageName} version=${info.versionName} (${info.longVersionCode})")
            write(session, "device=${Build.MANUFACTURER} ${Build.MODEL}; Android=${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}")
            write(session, "SESSION_START ${session.details}")
            write(session, "Measurements describe frames handed to CarLife, not a confirmation of HU decoding.")
            sample(session, final = false)
            directory.listFiles { entry -> entry.isFile && entry.name.startsWith("session-") && entry.extension == "log" }
                ?.filter { it != file }?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FILES - 1)?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
            runCatching { session.output?.close() }
            session.output = null
        }
    }

    private fun sample(session: Session, final: Boolean) {
        val now = System.nanoTime()
        val frames = session.frames.get()
        val bytes = session.bytes.get()
        val keys = session.keys.get()
        val seconds = ((now - session.lastNs) / 1_000_000_000.0).coerceAtLeast(0.001)
        val elapsed = (now - session.createdNs) / 1_000_000_000.0
        val rate = String.format(Locale.US,
            "elapsed_s=%.1f interval_s=%.1f fps=%.2f bitrate_kbps=%.1f frames=%d keyframes=%d bytes=%d total_frames=%d total_keyframes=%d total_bytes=%d",
            elapsed, seconds, (frames - session.lastFrames) / seconds,
            (bytes - session.lastBytes) * 8.0 / seconds / 1000.0,
            frames - session.lastFrames, keys - session.lastKeys, bytes - session.lastBytes,
            frames, keys, bytes)
        session.lastNs = now
        session.lastFrames = frames
        session.lastBytes = bytes
        session.lastKeys = keys
        write(session, "${if (final) "FINAL_STATS" else "STATS"} $rate ${deviceState(session.context)}", final)
    }

    private fun deviceState(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val interactive = runCatching { power?.isInteractive }.getOrNull()
        val thermal = runCatching { power?.currentThermalStatus }.getOrNull()
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state
        }.getOrNull()
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { String.format(Locale.US, "%.1f", it / 10.0) } ?: "unknown"
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (level >= 0 && scale > 0) (level * 100 / scale).toString() else "unknown"
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val statusName = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "unknown"
            else -> "unknown"
        }
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pluggedName = when {
            (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0 -> "usb"
            (plugged and BatteryManager.BATTERY_PLUGGED_AC) != 0 -> "ac"
            (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 -> "wireless"
            plugged == 0 -> "none"
            else -> "other"
        }
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it >= 0 }?.toString() ?: "unknown"
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        fun batteryProperty(property: Int): String = runCatching {
            batteryManager?.getIntProperty(property)
        }.getOrNull()?.takeIf { it != Int.MIN_VALUE }?.toString() ?: "unknown"
        val currentNow = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAverage = batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val chargeCounter = batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return "battery_level_pct=$levelPercent battery_status=$statusName plugged=$pluggedName plugged_bits=$plugged " +
            "battery_voltage_mv=$voltage battery_current_now_ua=$currentNow battery_current_avg_ua=$currentAverage " +
            "charge_counter_uah=$chargeCounter battery_temp_c=$temperature thermal_status=${thermal ?: "unknown"} " +
            "interactive=${interactive ?: "unknown"} main_display_state=${display ?: "unknown"}"
    }

    private fun write(session: Session, message: String, final: Boolean = false) {
        val stream = session.output ?: return
        if (session.closed || (session.capped && !final)) return
        val line = line(message)
        try {
            val limit = if (final) MAX_FILE_BYTES else MAX_FILE_BYTES - FINAL_RESERVE_BYTES
            if (session.written + line.size > limit) {
                if (!session.capped && !final) {
                    session.capped = true
                    write(session, "LOG_LIMIT_REACHED; further events omitted; final statistics reserved", final = true)
                }
                return
            }
            stream.write(line)
            stream.flush()
            session.written += line.size
        } catch (_: Exception) {
            runCatching { stream.close() }
            session.output = null
        }
    }

    private fun line(message: String): ByteArray {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return "$stamp ${message.take(1800).replace('\r', ' ').replace('\n', ' ')}\n".toByteArray(Charsets.UTF_8)
    }

    /** Flushes the current file, then opens Android's sharing chooser with a temporary read grant. */
    fun shareLatest(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                current?.output?.flush()
                val file = latestFile?.takeIf { it.isFile && it.length() > 0 } ?: directory(app).listFiles { entry ->
                    entry.isFile && entry.name.startsWith("session-") && entry.extension == "log" && entry.length() > 0
                }?.maxByOrNull { it.lastModified() }
                if (file == null) {
                    main.post { Toast.makeText(app, "Журнала пока нет. Сначала подключитесь к ГУ.", Toast.LENGTH_LONG).show() }
                    return@execute
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", file)
                main.post {
                    try {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "CarBridge: журнал сеанса")
                            clipData = ClipData.newUri(app.contentResolver, "CarBridge diagnostics", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = Intent.createChooser(send, "Сохранить или отправить журнал").apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(chooser)
                    } catch (_: Exception) {
                        Toast.makeText(app, "Не удалось открыть отправку журнала.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (_: Exception) {
                main.post { Toast.makeText(app, "Не удалось прочитать журнал сеанса.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun directory(context: Context) = File(context.filesDir, "diagnostics")

    private fun clean(message: String): String =
        if (sensitive.containsMatchIn(message)) "[sensitive authentication detail omitted]"
        else message.take(1500).replace('\r', ' ').replace('\n', ' ')
}
