package com.example.carbridge.scrcpy

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.media.MediaCodecList
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.DisplayMetrics
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.VideoProfile
import com.example.carbridge.adb.AdbChannel
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.link.HuLink
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * H.264 and control on scrcpy's shell-owned separate display, using the already
 * connected local wireless ADB session. No MediaProjection or app-owned VD.
 * All callbacks run on worker threads. start()/stop()/control calls are asynchronous.
 */
class ScrcpySource(
    context: Context,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    initialPackage: String?,
    private val listener: Listener,
    private val beforeStart: (() -> Unit)? = null,
    private val videoProfile: VideoProfile = VideoProfile.DEFAULT,
    private val maxVideoSize: Int? = null,
    private val touchSourceWidth: Int = width,
    private val touchSourceHeight: Int = height
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, key: Boolean, ptsUs: Long)
        fun onReady(width: Int, height: Int, displayId: Int?)
        fun onStatus(message: String)
        fun onError(message: String, error: Throwable?)
        fun onDisplayDensityApplied(dpi: Int) { }
        fun onDisplayEmpty(displayId: Int) { }
        fun onForegroundApp(packageName: String?) { }
    }

    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val channelsLock = Any()
    private val controlLock = Any()
    private val pendingLock = Any()
    private val controls = Executors.newSingleThreadScheduledExecutor { Thread(it, "scrcpy-controls") }
    private val displayCommands = Executors.newSingleThreadExecutor { Thread(it, "scrcpy-display-settings") }
    private val densityRevision = AtomicLong()
    private val lastDisplaySnapshotMs = AtomicLong(0)
    private var landscapeConfiguredFor: Int? = null
    private var emptyDisplaySamples = 0
    private val physicalPower = PhysicalScreenPowerState()
    private val resetScheduled = AtomicBoolean(false)
    @Volatile private var sync: AdbChannel? = null
    @Volatile private var shell: AdbChannel? = null
    @Volatile private var video: AdbChannel? = null
    @Volatile private var control: AdbChannel? = null
    @Volatile private var wakeupCommand: AdbChannel? = null
    @Volatile private var densityCommand: AdbChannel? = null
    @Volatile private var startWorker: Thread? = null
    @Volatile private var ready = false
    @Volatile private var firstFrame = false
    @Volatile private var streamWidth = width
    @Volatile private var streamHeight = height
    @Volatile private var displayId: Int? = null
    @Volatile private var applicationDisplayId: Int? = null
    @Volatile private var applicationInput: ScrcpyInputSession? = null
    fun attachApplicationDisplay(id: Int, input: ScrcpyInputSession) {
        require(id > 0)
        applicationDisplayId = id
        applicationInput = input
        landscapeConfiguredFor = null
    }
    private fun appDisplay() = applicationDisplayId ?: displayId
    @Volatile private var screenOffSent = false
    @Volatile private var lastServerLine = ""
    @Volatile private var remoteServerPath: String? = null
    private var pendingPackage = initialPackage?.takeIf { it.isNotBlank() }
    private var pendingKeyframe = false
    private var lastResetMs = Long.MIN_VALUE / 2
    @Volatile private var touchDown = false
    private var lastTouchX = 0
    private var lastTouchY = 0
    private var huPrimaryPointerId: Long? = null
    private var huSecondaryPointerId: Long? = null
    private var huPrimaryX = 0
    private var huPrimaryY = 0
    private var huSecondaryX = 0
    private var huSecondaryY = 0
    private val pinchReserved = AtomicBoolean(false)
    private val pinchRevision = AtomicLong()
    private val launchRevision = AtomicLong()
    private val touchEpoch = AtomicLong()
    @Volatile private var activePinch: PinchRun? = null

    private class PinchRun(val gesture: PinchGesture, val revision: Long, val startedAtMs: Long) {
        val pointers = PinchGesture.Pointers()
        var nextStep: ScheduledFuture<*>? = null
    }

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        val worker = Thread({
            try {
                require(Build.VERSION.SDK_INT >= 29) { "Отдельный дисплей scrcpy требует Android 10 или новее" }
                require(width in 1..65535 && height in 1..65535 && dpi in 1..1280) { "Некорректные параметры дисплея" }
                require(maxVideoSize == null || maxVideoSize in 1..maxOf(width, height)) { "Некорректное ограничение видеокадра" }
                require(touchSourceWidth > 0 && touchSourceHeight > 0) { "Некорректный размер области касания" }
                checkOpen()
                beforeStart?.invoke()
                checkOpen()
                check(AdbSession.isConnected()) { "Сначала подключите локальный ADB" }
                controls.scheduleWithFixedDelay({ diagnoseDisplay("periodic") }, 15, 15, TimeUnit.SECONDS)
                controls.schedule({
                    if (!closed.get() && !firstFrame) fail("scrcpy не прислал видеокадр за 30 секунд", null)
                }, 30, TimeUnit.SECONDS)
                val scid = SecureRandom().nextInt(Int.MAX_VALUE)
                val hex = "%08x".format(scid)
                val remote = "/data/local/tmp/carbridge-scrcpy-$hex.jar"
                remoteServerPath = remote
                status("Загрузка scrcpy-server $SERVER_VERSION")
                val serverBytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
                val sha = MessageDigest.getInstance("SHA-256").digest(serverBytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                check(sha == SERVER_SHA256) { "Контрольная сумма scrcpy-server не совпала" }
                val upload = keep(AdbSession.open("sync:", 10_000L)) { sync = it }
                try {
                    ScrcpySync.send(upload.input, upload.output, remote, serverBytes)
                } finally {
                    synchronized(channelsLock) { if (sync === upload) sync = null }
                    runCatching { upload.close() }
                }
                checkOpen()
                val server = keep(AdbSession.open("shell:${serverCommand(remote, hex)}", 10_000L)) { shell = it }
                Thread({ readServerLog(server) }, "scrcpy-server-log").start()
                status("scrcpy: создание отдельного дисплея ${width}x$height/$dpi")
                val socket = "localabstract:scrcpy_$hex"
                val videoChannel = keep(openSocket(socket)) { video = it }
                // The dummy byte is sent on the video connection before the server
                // accepts control. Do not read the codec header before opening both.
                if (videoChannel.input.read() != 0) throw IOException("scrcpy: неверный начальный байт")
                checkOpen()
                val controlChannel = keep(openSocket(socket)) { control = it }
                Thread({ readControl(controlChannel) }, "scrcpy-device-messages").start()
                readVideo(videoChannel)
            } catch (e: Exception) {
                if (!closed.get()) fail("scrcpy: ${e.message ?: e.javaClass.simpleName}", e)
            } finally {
                closed.set(true)
                cleanup()
            }
        }, "scrcpy-start-video")
        startWorker = worker
        worker.start()
    }

    /** Coordinates supplied by HuLink are relative to its negotiated width/height. */
    fun sendTouch(event: HuLink.TouchEvent) {
        if (!ready || closed.get() || activePinch != null) return
        val epoch = touchEpoch.get()
        submit {
            if (activePinch != null || epoch != touchEpoch.get()) return@submit
            val outWidth = streamWidth
            val outHeight = streamHeight
            fun mappedX(value: Int) = if (value < 0) lastTouchX else
                (value.toLong() * outWidth / touchSourceWidth).toInt().coerceIn(0, outWidth - 1)
            fun mappedY(value: Int) = if (value < 0) lastTouchY else
                (value.toLong() * outHeight / touchSourceHeight).toInt().coerceIn(0, outHeight - 1)
            fun write(action: Int, pointerId: Long, x: Int, y: Int) {
                val tx = mappedX(x)
                val ty = mappedY(y)
                writeControl(ScrcpyProtocol.touchPointer(action, pointerId, tx, ty, outWidth, outHeight))
                lastTouchX = tx
                lastTouchY = ty
            }
            fun secondPoint(fallbackX: Int, fallbackY: Int): Pair<Int, Int> {
                val px = event.pointerX
                val py = event.pointerY
                return if (px == null || py == null) fallbackX to fallbackY
                    else px to py
            }
            fun nextPointerId(): Long = when {
                huPrimaryPointerId == null || huPrimaryPointerId == FIRST_HU_POINTER_ID -> SECOND_HU_POINTER_ID
                else -> FIRST_HU_POINTER_ID
            }
            when (event.action) {
                0 -> {
                    if (huPrimaryPointerId != null || huSecondaryPointerId != null) {
                        releaseHuPointers(::write)
                    }
                    huPrimaryPointerId = FIRST_HU_POINTER_ID
                    huPrimaryX = event.x
                    huPrimaryY = event.y
                    write(0, huPrimaryPointerId!!, huPrimaryX, huPrimaryY)
                }
                5 -> {
                    if (huPrimaryPointerId == null) {
                        huPrimaryPointerId = FIRST_HU_POINTER_ID
                        huPrimaryX = event.x
                        huPrimaryY = event.y
                        write(0, huPrimaryPointerId!!, huPrimaryX, huPrimaryY)
                    }
                    if (huSecondaryPointerId == null) {
                        huSecondaryPointerId = nextPointerId()
                        val point = secondPoint(event.x, event.y)
                        huSecondaryX = point.first
                        huSecondaryY = point.second
                        write(0, huSecondaryPointerId!!, huSecondaryX, huSecondaryY)
                        SessionDiagnostics.event("HU multitouch started; two pointers forwarded to navigation display")
                    }
                }
                2 -> {
                    val primaryId = huPrimaryPointerId ?: return@submit
                    // Some HUs send both coordinates in MOVE without a separate
                    // POINTER_DOWN. The payload itself confirms the second finger.
                    if (huSecondaryPointerId == null && event.pointerX != null && event.pointerY != null) {
                        huSecondaryPointerId = nextPointerId()
                        huSecondaryX = event.pointerX
                        huSecondaryY = event.pointerY
                        write(0, huSecondaryPointerId!!, huSecondaryX, huSecondaryY)
                        SessionDiagnostics.event("HU multitouch recovered from two-pointer MOVE")
                    }
                    huPrimaryX = event.x
                    huPrimaryY = event.y
                    write(2, primaryId, huPrimaryX, huPrimaryY)
                    huSecondaryPointerId?.let { secondaryId ->
                        val point = secondPoint(huSecondaryX, huSecondaryY)
                        huSecondaryX = point.first
                        huSecondaryY = point.second
                        write(2, secondaryId, huSecondaryX, huSecondaryY)
                    }
                }
                6 -> {
                    val primaryId = huPrimaryPointerId
                    val secondaryId = huSecondaryPointerId
                    if (event.actionIndex == 0 && primaryId != null && secondaryId != null) {
                        val remaining = secondPoint(huSecondaryX, huSecondaryY)
                        write(1, primaryId, event.x, event.y)
                        huPrimaryPointerId = secondaryId
                        huPrimaryX = remaining.first
                        huPrimaryY = remaining.second
                        huSecondaryPointerId = null
                        write(2, secondaryId, huPrimaryX, huPrimaryY)
                    } else if (secondaryId != null) {
                        val point = secondPoint(huSecondaryX, huSecondaryY)
                        write(1, secondaryId, point.first, point.second)
                        huSecondaryPointerId = null
                        if (primaryId != null) {
                            huPrimaryX = event.x
                            huPrimaryY = event.y
                            write(2, primaryId, huPrimaryX, huPrimaryY)
                        }
                    } else if (primaryId != null) {
                        write(1, primaryId, event.x, event.y)
                        huPrimaryPointerId = null
                    }
                }
                1, 3 -> releaseHuPointers(::write)
                else -> return@submit
            }
            touchDown = huPrimaryPointerId != null || huSecondaryPointerId != null
        }
    }

    private inline fun releaseHuPointers(write: (Int, Long, Int, Int) -> Unit) {
        huSecondaryPointerId?.let { write(1, it, huSecondaryX, huSecondaryY) }
        huSecondaryPointerId = null
        huPrimaryPointerId?.let { write(1, it, huPrimaryX, huPrimaryY) }
        huPrimaryPointerId = null
        touchDown = false
    }

    /** Send one ordinary pinch to the foreground application; no density or surface change. */
    fun zoomMap(zoomIn: Boolean) {
        if (!ready || !firstFrame || closed.get()) {
            status("Карта: сначала подключите ГУ и откройте приложение")
            return
        }
        if (touchDown) {
            status("Карта: отпустите экран ГУ перед изменением масштаба")
            return
        }
        val revision = pinchRevision.get()
        // Do not accumulate zoom requests while the executor or a gesture is busy.
        if (!pinchReserved.compareAndSet(false, true)) return
        try {
            controls.execute {
                if (closed.get() || !ready || !firstFrame || revision != pinchRevision.get()) {
                    pinchReserved.set(false)
                    return@execute
                }
                if (touchDown) {
                    pinchReserved.set(false)
                    status("Карта: отпустите экран ГУ перед изменением масштаба")
                    return@execute
                }
                try {
                    val run = PinchRun(PinchGesture.create(streamWidth, streamHeight, zoomIn), revision,
                        SystemClock.elapsedRealtime())
                    synchronized(controlLock) {
                        touchEpoch.incrementAndGet()
                        activePinch = run
                    }
                    runPinchStep(run, 0)
                    SessionDiagnostics.event("Map pinch requested: zoomIn=$zoomIn; frame=${run.gesture.width}x${run.gesture.height}")
                } catch (error: Exception) {
                    pinchReserved.set(false)
                    if (!closed.get()) fail("scrcpy pinch: ${error.message}", error)
                }
            }
        } catch (_: RejectedExecutionException) {
            pinchReserved.set(false)
        }
    }

    /** Complete before a new overlay/dialog is shown, so it cannot receive the remaining moves. */
    fun cancelMapZoom(onComplete: () -> Unit = {}) {
        pinchRevision.incrementAndGet()
        if (activePinch == null && !pinchReserved.get()) { onComplete(); return }
        try {
            controls.execute {
                try {
                    if (!closed.get()) cancelPinchOnControls()
                } catch (error: Exception) {
                    if (!closed.get()) fail("scrcpy pinch cancellation: ${error.message}", error)
                } finally {
                    runCatching { onComplete() }
                }
            }
        } catch (_: RejectedExecutionException) {
            runCatching { onComplete() }
        }
    }

    private fun runPinchStep(run: PinchRun, index: Int) {
        synchronized(controlLock) {
            if (activePinch !== run || closed.get()) return
            if (!ready || run.revision != pinchRevision.get() ||
                streamWidth != run.gesture.width || streamHeight != run.gesture.height ||
                SystemClock.elapsedRealtime() - run.startedAtMs > PinchGesture.MAX_ELAPSED_MS) {
                cancelPinchOnControls()
                return
            }
            val step = run.gesture.steps[index]
            for (touch in step.touches) {
                run.pointers.beforeWrite(touch)
                writeControl(ScrcpyProtocol.touchPointer(touch.action, touch.pointerId, touch.x, touch.y,
                    run.gesture.width, run.gesture.height))
                run.pointers.afterWrite(touch)
            }
            if (index == run.gesture.steps.lastIndex) {
                touchEpoch.incrementAndGet()
                activePinch = null
                pinchReserved.set(false)
                status("Карта: жест изменения масштаба отправлен приложению")
            } else {
                val delay = run.gesture.steps[index + 1].atMs - step.atMs
                run.nextStep = controls.schedule({
                    try {
                        runPinchStep(run, index + 1)
                    } catch (error: Exception) {
                        if (!closed.get()) fail("scrcpy pinch: ${error.message}", error)
                    }
                }, delay, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** Only called on controls, or during cleanup while holding controlLock. */
    private fun cancelPinchOnControls() {
        synchronized(controlLock) {
            val run = activePinch ?: return
            run.nextStep?.cancel(false)
            releasePinchPointers(run)
            touchEpoch.incrementAndGet()
            activePinch = null
            pinchReserved.set(false)
        }
    }

    /** Raw output also permits cleanup to release known fingers after closed=true. */
    private fun releasePinchPointers(run: PinchRun) {
        val output = control?.output ?: return
        for (touch in run.pointers.releases()) {
            output.write(ScrcpyProtocol.touchPointer(1, touch.pointerId, touch.x, touch.y, streamWidth, streamHeight))
            output.flush()
            run.pointers.afterWrite(touch)
        }
    }

    fun pasteText(text: String): Boolean {
        if (!ready || closed.get() || text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > 16384) return false
        val input = applicationInput ?: return false
        return input.paste(text)
    }

    /** Android key code, e.g. BACK=4, HOME=3, APP_SWITCH=187, on the scrcpy VD. */
    fun sendKey(keyCode: Int) {
        if (!ready || keyCode < 0) return
        applicationInput?.let { it.key(keyCode); return }
        pinchRevision.incrementAndGet()
        submit {
            cancelPinchOnControls()
            writeControl(ScrcpyProtocol.key(0, keyCode))
            writeControl(ScrcpyProtocol.key(1, keyCode))
        }
    }

    fun launchApp(packageName: String) {
        // Validate synchronously, without ever interpolating a package into a shell command.
        ScrcpyProtocol.startApp(packageName)
        pinchRevision.incrementAndGet()
        if (ready) submit { cancelPinchOnControls() }
        synchronized(pendingLock) { pendingPackage = packageName }
        if (ready) flushPending()
    }

    /** Launches the navigation app full-screen on scrcpy's HU display. */
    fun launchApp(packageName: String, onLaunchResult: (Boolean, String) -> Unit) {
        launchApp(packageName, resolveLauncherComponent(packageName), onLaunchResult)
    }

    /** Launches the exact launcher activity selected from CarBridge's HU app list. */
    fun launchApp(
        packageName: String,
        launcherComponent: ComponentName?,
        onLaunchResult: (Boolean, String) -> Unit
    ) {
        val launchToken = launchRevision.incrementAndGet()
        ScrcpyProtocol.startApp(packageName)
        val component = launcherComponent?.takeIf { it.packageName == packageName }
            ?: resolveLauncherComponent(packageName)
        if (component == null) {
            onLaunchResult(false, "На телефоне не найдена MAIN/LAUNCHER Activity для $packageName")
            return
        }
        val targetDisplay = appDisplay()
        if (!ready || targetDisplay == null || targetDisplay <= 0) {
            onLaunchResult(false, "виртуальный дисплей ещё не готов")
            return
        }
        try {
            displayCommands.execute {
                if (closed.get() || appDisplay() != targetDisplay) return@execute
                try {
                    configureLandscape(targetDisplay)
                    val start = runShellCommand(
                        "am start --user current --display $targetDisplay -n ${shellArgument(component.flattenToString())}"
                    )
                    if (shellCommandFailed(start)) throw IOException(start.take(240).ifBlank { "Android не запустил приложение" })
                    onLaunchResult(true, "полноэкранный запуск ${component.flattenToShortString()}; " +
                        "display=$targetDisplay; ${start.trim().take(180)}")
                    diagnoseDisplay("app launch ${component.packageName}", force = true)
                    verifyLaunchPlacement(packageName, targetDisplay, launchToken)
                } catch (error: Exception) {
                    onLaunchResult(false, error.message ?: error.javaClass.simpleName)
                }
            }
        } catch (error: RejectedExecutionException) {
            onLaunchResult(false, "ADB-команда отменена при остановке сессии")
        }
    }

    fun watchRequestedLaunch(pkg: String) {
        val target=appDisplay()?.takeIf { it>0 } ?: return
        verifyLaunchPlacement(pkg,target,launchRevision.incrementAndGet())
    }
    fun cancelLaunchPlacement() { launchRevision.incrementAndGet() }

    /** Some launcher trampolines reuse a task on display 0. Only repair the app
     * explicitly requested on the HU, for six seconds after launch. Never move
     * mixed/system roots or continuously steal applications from the phone. */
    private fun verifyLaunchPlacement(pkg: String, target: Int, token: Long, assistant: Boolean = false) {
        listOf(500L, 1500L, 3000L, 6000L).forEach { delay ->
            controls.schedule({
                if (closed.get() || launchRevision.get() != token || appDisplay() != target) return@schedule
                try { displayCommands.execute {
                    if (closed.get() || launchRevision.get() != token || appDisplay() != target) return@execute
                    runCatching {
                        val snapshot = runShellCommand(HuWindowSnapshot.COMMAND)
                        if (HuWindowSnapshot.foregroundPackage(snapshot, target) == pkg) return@runCatching
                        if (HuWindowSnapshot.foregroundPackage(snapshot, 0) != pkg) return@runCatching
                        val roots = runShellCommand("am stack list")
                        val headers = Regex("(?m)^(?:RootTask|Stack) id=(\\d+)\\b[^\\r\\n]*").findAll(roots).toList()
                        val root = headers.indices.firstOrNull { index ->
                            val header = headers[index]
                            if (!Regex("displayId=0\\b").containsMatchIn(header.value)) return@firstOrNull false
                            val section = roots.substring(header.range.last + 1, headers.getOrNull(index+1)?.range?.first ?: roots.length)
                            val packages = Regex("(?m)^\\s*taskId=\\d+: ([A-Za-z0-9_.]+)/").findAll(section).map { it.groupValues[1] }.toList()
                            packages.isNotEmpty() && packages.all { it == pkg } && section.contains("topActivity=ComponentInfo{$pkg/")
                        } ?: return@runCatching
                        val id = headers[root].groupValues[1].toIntOrNull() ?: return@runCatching
                        val result = runShellCommand("am display move-stack $id $target")
                        val after = runShellCommand(HuWindowSnapshot.COMMAND)
                        val placed = HuWindowSnapshot.foregroundPackage(after, target) == pkg
                        SessionDiagnostics.event("HU launch placement repair; package=$pkg; root=$id; display=$target; success=$placed; commandFailed=${shellCommandFailed(result)}")
                        listener.onForegroundApp(HuWindowSnapshot.foregroundPackage(after, target))
                        if (!placed && delay == 6000L) listener.onStatus("Android не позволил перенести $pkg на ГУ")
                    }.onFailure { SessionDiagnostics.event("HU launch placement check failed: ${it.javaClass.simpleName}") }
                } } catch (_: RejectedExecutionException) { }
            }, delay, TimeUnit.MILLISECONDS)
        }
        if (assistant) controls.schedule({
            if (closed.get() || launchRevision.get() != token || appDisplay() != target) return@schedule
            try { displayCommands.execute {
                if (closed.get() || launchRevision.get() != token || appDisplay() != target) return@execute
                runCatching {
                    val snapshot = runShellCommand(HuWindowSnapshot.COMMAND)
                    val onHu = HuWindowSnapshot.foregroundPackage(snapshot, target) == pkg
                    SessionDiagnostics.event("HU assistant placement verified; package=$pkg; display=$target; onHu=$onHu; phoneForeground=${HuWindowSnapshot.foregroundPackage(snapshot, 0) == pkg}")
                    listener.onForegroundApp(HuWindowSnapshot.foregroundPackage(snapshot, target))
                    if (!onHu) listener.onStatus("Ассистент не появился на ГУ: Android или само приложение оставило его на телефоне")
                }.onFailure { SessionDiagnostics.event("HU assistant placement verification failed: ${it.javaClass.simpleName}") }
            } } catch (_: RejectedExecutionException) { }
        }, 7500L, TimeUnit.MILLISECONDS)
    }

    private fun resolveLauncherComponent(packageName: String): ComponentName? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        launchIntent?.component?.takeIf { it.packageName == packageName }?.let { return it }

        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        @Suppress("DEPRECATION")
        val activity = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .firstOrNull { it.activityInfo?.packageName == packageName }
            ?.activityInfo
        return activity?.let { ComponentName(it.packageName, it.name) }
    }

    /** Resolve the voice Activity explicitly, then verify the actual destination task. */
    fun launchVoiceAssistant() {
        val target = appDisplay()?.takeIf { it > 0 } ?: return
        val token = launchRevision.incrementAndGet()
        try { displayCommands.execute {
            if (closed.get() || appDisplay() != target || launchRevision.get() != token) return@execute
            runCatching {
                var requested = false
                for (action in listOf("android.intent.action.VOICE_COMMAND", "android.intent.action.ASSIST")) {
                    val resolved = runShellCommand("cmd package resolve-activity --brief --user current -a $action")
                    val flat = Regex("(?m)^([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\\s*$")
                        .find(resolved)?.groupValues?.get(1)
                    val component = flat?.let(ComponentName::unflattenFromString)
                    if (component == null || component.packageName in setOf("android", "com.android.intentresolver")) continue
                    configureLandscape(target)
                    val output = runShellCommand("am start --user current --display $target -f 0x18000000 " +
                        "-a $action -n ${shellArgument(component.flattenToString())}")
                    if (shellCommandFailed(output)) continue
                    requested = true
                    SessionDiagnostics.event("HU wheel assistant start requested; package=${component.packageName}; display=$target; microphone=phone; placementPending=true")
                    verifyLaunchPlacement(component.packageName, target, token, assistant = true)
                    break
                }
                if (!requested) {
                    SessionDiagnostics.event("HU wheel assistant not resolved or start rejected; display=$target")
                    status("Не найдена доступная Activity голосового ассистента для экрана ГУ")
                }
            }.onFailure { status("Не удалось вызвать ассистента: ${it.javaClass.simpleName}") }
        } } catch (_: RejectedExecutionException) { }
    }

    /** Settings are launched on the HU display, never by expanding the phone's shade. */
    fun launchSettings(action: String) {
        if (action !in setOf("android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS",
                "android.settings.WIRELESS_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS",
                "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS",
                "android.settings.SETTINGS", "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) return
        val target = appDisplay()?.takeIf { it > 0 } ?: return
        try {
            displayCommands.execute {
                if (closed.get() || appDisplay() != target) return@execute
                runCatching {
                    configureLandscape(target)
                    val result = runShellCommand("am start --user current --display $target -a ${shellArgument(action)}")
                    SessionDiagnostics.event("HU settings action=$action; success=${!shellCommandFailed(result)}")
                    if (shellCommandFailed(result)) status("Не удалось открыть настройки на ГУ")
                }.onFailure { status("Не удалось открыть настройки: ${it.javaClass.simpleName}") }
            }
        } catch (_: RejectedExecutionException) { }
    }

    /** Explicit user action from a HU icon menu. Android stops the whole package. */
    fun stopApp(packageName: String, result: (Boolean) -> Unit) {
        if (packageName == context.packageName ||
            !Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+").matches(packageName)) {
            result(false)
            return
        }
        val target = appDisplay()?.takeIf { it > 0 } ?: run { result(false); return }
        try {
            displayCommands.execute {
                if (closed.get() || appDisplay() != target) return@execute
                val success = runCatching {
                    val output = runShellCommand("am force-stop --user current ${shellArgument(packageName)} && echo CARBRIDGE_STOP_OK")
                    !shellCommandFailed(output) && output.lineSequence().any { it.trim() == "CARBRIDGE_STOP_OK" }
                }.getOrDefault(false)
                SessionDiagnostics.event("HU stop app=$packageName; success=$success")
                result(success)
                diagnoseDisplay("stop app", force = true)
            }
        } catch (_: RejectedExecutionException) { result(false) }
    }

    private fun shellArgument(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun configureLandscape(target: Int) {
        if (landscapeConfiguredFor == target) return
        var applied = true
        for (command in HuLandscapeCommand.commands(target)) {
            val result = runShellCommand("$command && echo CARBRIDGE_ORIENTATION_OK")
            val success = result.lineSequence().any { it.trim() == "CARBRIDGE_ORIENTATION_OK" }
            if (!success) applied = false
            SessionDiagnostics.event("HU landscape display=$target; command=$command; success=$success; " +
                "response=${result.trim().take(240)}")
        }
        if (applied) landscapeConfiguredFor = target
        else status("Android не подтвердил все настройки альбомной ориентации ГУ; запуск приложения продолжается")
    }

    /** Sample window ownership, never user text or complete activity intent dumps. */
    fun diagnoseDisplay(reason: String, force: Boolean = false) {
        val target = appDisplay()?.takeIf { it > 0 } ?: return
        if (!ready || closed.get()) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastDisplaySnapshotMs.get()
        if (!force && now - previous < 5000L) return
        if (!lastDisplaySnapshotMs.compareAndSet(previous, now)) return
        try {
            displayCommands.execute {
                if (closed.get() || appDisplay() != target) return@execute
                runCatching {
                    val snapshot = runShellCommand(HuWindowSnapshot.COMMAND)
                    listener.onForegroundApp(HuWindowSnapshot.foregroundPackage(snapshot, target))
                    SessionDiagnostics.event("HU window snapshot; reason=${reason.take(100)}; display=$target; " +
                        "${snapshot.trim().replace('\n', ' ').take(2400)}")
                    val empty = HuWindowSnapshot.isEmpty(snapshot, target)
                    if (reason == "periodic") emptyDisplaySamples = if (empty) emptyDisplaySamples + 1 else 0
                    if ((reason == "Back on HU" && empty) ||
                        (reason == "periodic" && applicationDisplayId == target && emptyDisplaySamples == 2)) {
                        SessionDiagnostics.event("HU app display empty; display=$target; reason=$reason; returning to launcher")
                        listener.onDisplayEmpty(target)
                    }
                }.onFailure {
                    SessionDiagnostics.event("HU window snapshot failed; display=$target; ${it.javaClass.simpleName}")
                }
            }
        } catch (_: RejectedExecutionException) { /* session stopped */ }
    }

    private fun runShellCommand(command: String): String {
        val channel = AdbSession.open("shell:$command", SHELL_COMMAND_TIMEOUT_MS)
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_SHELL_OUTPUT_BYTES) {
                val count = channel.input.read(buffer, 0, minOf(buffer.size, MAX_SHELL_OUTPUT_BYTES - total))
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            runCatching { channel.close() }
        }
    }

    private fun shellCommandFailed(output: String): Boolean =
        SHELL_ERROR_MARKERS.any { output.contains(it, ignoreCase = true) }

    /** With new_display, scrcpy applies type 10 only to the physical main display. */
    fun setPhysicalScreenOn(on: Boolean) {
        physicalPower.request(on)
        if (ready) flushPending()
    }

    /**
     * The hardware POWER button can put Android to sleep, unlike scrcpy's panel
     * OFF command. The service chooses the new physical panel state once per
     * genuine sleep, so a hardware press can show the phone again. No startup wake,
     * lock-screen dismissal, or polling is performed here.
     */
    fun recoverFromDeviceSleep(panelOn: Boolean, onComplete: () -> Unit = {}) {
        // Revisions invalidate every delayed OFF belonging to the previous choice.
        physicalPower.request(panelOn)
        val recovery = physicalPower.beginRecovery(ready && firstFrame && !closed.get())
        if (recovery == null) { onComplete(); return }
        val completed = AtomicBoolean(false)
        fun complete() {
            physicalPower.finishRecovery(recovery)
            if (completed.compareAndSet(false, true)) onComplete()
        }
        try {
            controls.execute {
                if (!isRecoveryCurrent(recovery)) {
                    complete()
                    return@execute
                }
                try {
                    status(if (panelOn) "Включаю экран телефона; дисплей ГУ остаётся активным"
                        else "Гашу экран телефона; дисплей ГУ остаётся активным")
                    val channel = keep(AdbSession.open(ScrcpyWakeupCommand.DESTINATION, 1500L)) { wakeupCommand = it }
                    try {
                        ScrcpyWakeupCommand.awaitAcknowledgement(channel.input, 2000L) { isRecoveryCurrent(recovery) }
                    } finally {
                        synchronized(channelsLock) { if (wakeupCommand === channel) wakeupCommand = null }
                        // LibraryChannel.close() has its own one-second CLSE budget.
                        runCatching { channel.close() }
                    }
                    if (isRecoveryCurrent(recovery)) scheduleRecoveryPanel(recovery, 250L, finalPass = false, complete = ::complete)
                    else complete()
                } catch (error: Exception) {
                    if (isRecoveryCurrent(recovery)) {
                        Log.w(TAG, "Phone sleep recovery failed", error)
                        status("Не удалось восстановить экран ГУ после сна: включите и разблокируйте телефон")
                    }
                    // A power-management quirk must not tear down an otherwise
                    // healthy CarLife/ADB video session.
                    complete()
                }
            }
        } catch (_: RejectedExecutionException) {
            complete()
        }
    }

    /** Apply the navigation/app content density only to this source's separate display. */
    fun applyDisplayDensity(dpi: Int) {
        val target = displayId
        if (!ready || closed.get() || target == null || target <= 0) {
            status("Дисплей ГУ ещё не создан; масштаб приложений применится при следующем подключении")
            return
        }
        val destination = ScrcpyDensityCommand.destination(dpi, target)
        val revision = densityRevision.incrementAndGet()
        fun current() = !closed.get() && ready && displayId == target && densityRevision.get() == revision
        cancelMapZoom {
            try {
                displayCommands.execute {
                    if (!current()) return@execute
                    try {
                        val command = keep(AdbSession.open(destination, 1500L)) { densityCommand = it }
                        val confirmation = try {
                            ScrcpyDensityCommand.readConfirmation(command.input, dpi, 2500L, ::current)
                        } finally {
                            synchronized(channelsLock) { if (densityCommand === command) densityCommand = null }
                            runCatching { command.close() }
                        }
                        if (!current()) return@execute
                        val metrics = DisplayMetrics()
                        val deadline = SystemClock.elapsedRealtime() + 750L
                        do {
                            if (!current()) return@execute
                            @Suppress("DEPRECATION")
                            context.getSystemService(DisplayManager::class.java).getDisplay(target)?.getRealMetrics(metrics)
                            if (metrics.densityDpi == dpi) break
                            Thread.sleep(50L)
                        } while (SystemClock.elapsedRealtime() < deadline)
                        if (!current()) return@execute
                        SessionDiagnostics.event("VD density confirmed; display=$target; requested=$dpi; metrics=${metrics.densityDpi}; " +
                            confirmation.replace('\n', ' ').take(256))
                        listener.onDisplayDensityApplied(dpi)
                        status(if (metrics.densityDpi == dpi) "Размер приложений на ГУ изменён: $dpi dpi, Android подтвердил применение"
                            else "Android принял $dpi dpi; приложение на ГУ обновляет масштаб")
                    } catch (error: Exception) {
                        if (current()) {
                            Log.w(TAG, "Separate display density update failed", error)
                            status("Не удалось применить размер приложений сейчас: ${error.message?.take(160)}. Сохранён для следующего подключения")
                        }
                    }
                }
            } catch (_: RejectedExecutionException) { }
        }
    }

    /** scrcpy 5 reset restarts the encoder and sends config + IDR, at most once/sec. */
    fun requestKeyframe() {
        synchronized(pendingLock) { pendingKeyframe = true }
        scheduleReset()
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        pinchRevision.incrementAndGet()
        physicalPower.close()
        Thread({ cleanup() }, "scrcpy-stop").start()
    }

    private fun readVideo(channel: AdbChannel) {
        val reader = ScrcpyProtocol.VideoReader(BufferedInputStream(channel.input, 64 * 1024))
        reader.readCodec()
        while (!closed.get()) {
            when (val event = reader.read()) {
                is ScrcpyProtocol.VideoEvent.Session -> {
                    streamWidth = event.width
                    streamHeight = event.height
                    ready = true
                    listener.onReady(event.width, event.height, displayId)
                    if (closed.get()) return
                    status("scrcpy: видео ${event.width}x${event.height}, отдельный дисплей ${displayId ?: "создан"}")
                    flushPending()
                }
                is ScrcpyProtocol.VideoEvent.Frame -> {
                    if (!ready) throw IOException("scrcpy frame received before session metadata")
                    if (event.config) listener.onConfig(event.data)
                    else {
                        firstFrame = true
                        listener.onFrame(event.data, event.key, event.ptsUs)
                    }
                }
            }
        }
    }

    private fun flushPending() {
        val app: String?
        synchronized(pendingLock) {
            app = pendingPackage
            pendingPackage = null
        }
        val power = physicalPower.takePending()
        if (app != null) submit {
            writeControl(ScrcpyProtocol.startApp(app))
            status("scrcpy: запрос запуска $app на отдельном дисплее отправлен")
        }
        if (power != null) submit {
            if (physicalPower.isCurrent(power)) applyPhysicalScreenPower(power.on)
        }
        scheduleReset()
    }

    private fun isRecoveryCurrent(recovery: PhysicalScreenPowerState.Recovery): Boolean =
        !closed.get() && ready && firstFrame && physicalPower.isCurrent(recovery)

    private fun scheduleRecoveryPanel(recovery: PhysicalScreenPowerState.Recovery, delayMs: Long,
                                      finalPass: Boolean, complete: () -> Unit) {
        try {
            controls.schedule({
                if (!isRecoveryCurrent(recovery)) {
                    complete()
                    return@schedule
                }
                try {
                    applyPhysicalScreenPower(recovery.on)
                    if (finalPass) {
                        // Do not reset the encoder on every phone power press:
                        // RESET_VIDEO replaces the VD surface and disrupts OEM UI.
                        status(if (recovery.on) "Экран телефона включён; автоматическое повторное гашение отменено"
                            else "Экран телефона погашен; ГУ продолжает работу")
                        complete()
                    } else {
                        // Waking is asynchronous. Reapply once after the normal
                        // wake animation, then stop; no permanent wake/off loop.
                        scheduleRecoveryPanel(recovery, 650L, finalPass = true, complete = complete)
                    }
                } catch (error: Exception) {
                    if (isRecoveryCurrent(recovery)) {
                        Log.w(TAG, "Could not apply requested physical panel state", error)
                        status("Не удалось переключить экран телефона; используйте кнопки экрана в CarBridge")
                    }
                    complete()
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            complete()
        }
    }

    private fun scheduleReset() {
        if (!ready || closed.get() || !resetScheduled.compareAndSet(false, true)) return
        val delay = synchronized(pendingLock) {
            if (!pendingKeyframe) {
                resetScheduled.set(false)
                return
            }
            maxOf(0L, 1000L - (SystemClock.elapsedRealtime() - lastResetMs))
        }
        try {
            controls.schedule({
                try {
                    if (closed.get()) return@schedule
                    synchronized(pendingLock) {
                        if (!pendingKeyframe) return@schedule
                        // Re-check at execution time as requests may race with a
                        // reset already waiting in the single control executor.
                        if (SystemClock.elapsedRealtime() - lastResetMs < 1000L) return@schedule
                        pendingKeyframe = false
                        lastResetMs = SystemClock.elapsedRealtime()
                    }
                    cancelPinchOnControls()
                    writeControl(ScrcpyProtocol.resetVideo())
                    SessionDiagnostics.event("scrcpy encoder reset requested")
                } catch (e: Exception) {
                    fail("scrcpy: запрос ключевого кадра не удался", e)
                } finally {
                    resetScheduled.set(false)
                    scheduleReset()
                }
            }, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            resetScheduled.set(false)
        }
    }

    private fun applyPhysicalScreenPower(on: Boolean) {
        // Mark before writing: an interrupted write might have reached the server.
        if (!on) screenOffSent = true
        writeControl(ScrcpyProtocol.displayPower(on))
        if (on) screenOffSent = false
        status("scrcpy: запрос ${if (on) "включения" else "гашения"} физического экрана отправлен")
    }

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        try {
            controls.execute {
                if (closed.get()) return@execute
                try { task() } catch (e: Exception) { fail("scrcpy control: ${e.message}", e) }
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun writeControl(packet: ByteArray) {
        synchronized(controlLock) {
            if (closed.get()) return
            val output = control?.output ?: throw IOException("scrcpy control is not connected")
            output.write(packet)
            output.flush()
        }
    }

    private fun readControl(channel: AdbChannel) {
        try {
            val input = DataInputStream(BufferedInputStream(channel.input))
            while (!closed.get()) ScrcpyProtocol.readDeviceMessage(input)
        } catch (e: Exception) {
            if (!closed.get()) fail("scrcpy: управляющий канал закрыт", e)
        }
    }

    private fun readServerLog(channel: AdbChannel) {
        try {
            channel.input.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!closed.get()) {
                    val line = reader.readLine() ?: break
                    lastServerLine = line.take(512)
                    Log.i(TAG, lastServerLine)
                    if (!closed.get()) {
                        SessionDiagnostics.event("scrcpy-server: $lastServerLine")
                        Regex("Using video encoder: '([^']+)'").find(line)?.groupValues?.get(1)?.let { name ->
                            val description = runCatching {
                                MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull {
                                    it.name.equals(name, ignoreCase = true) || it.canonicalName.equals(name, ignoreCase = true)
                                }?.let { "hardware=${it.isHardwareAccelerated}; software=${it.isSoftwareOnly}; vendor=${it.isVendor}" }
                            }.getOrNull() ?: "hardware=unknown"
                            SessionDiagnostics.event("Video encoder=$name; $description")
                        }
                    }
                    Regex("New display:.*\\(id=(\\d+)\\)").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                        displayId = it
                    }
                    if (line.contains("[server] ERROR") || line.contains("[server] WARN") ||
                        line.contains("ERROR:") || line.contains("WARN:")) status(lastServerLine)
                }
            }
            if (!closed.get()) fail("scrcpy-server завершился: $lastServerLine", EOFException("scrcpy server shell EOF"))
        } catch (e: Exception) {
            if (!closed.get()) fail("scrcpy: потерян серверный канал", e)
        }
    }

    private fun openSocket(destination: String): AdbChannel {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var last: IOException? = null
        while (!closed.get() && SystemClock.elapsedRealtime() < deadline) {
            try { return AdbSession.open(destination, 2000L) }
            catch (e: IOException) { last = e }
            Thread.sleep(150L)
        }
        throw IOException("scrcpy socket не открылся: $lastServerLine", last)
    }

    private fun keep(channel: AdbChannel, store: (AdbChannel) -> Unit): AdbChannel {
        synchronized(channelsLock) {
            if (closed.get()) {
                runCatching { channel.close() }
                throw IOException("scrcpy stopped")
            }
            store(channel)
        }
        return channel
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("scrcpy stopped")
    }

    private fun fail(message: String, error: Throwable?) {
        if (closed.get() || !failed.compareAndSet(false, true)) return
        Log.e(TAG, message, error)
        runCatching { listener.onError(message, error) }
        closed.set(true)
        Thread({ cleanup() }, "scrcpy-error-cleanup").start()
    }

    private fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        ready = false
        applicationInput?.close(); applicationInput = null
        physicalPower.close()
        startWorker?.takeIf { it !== Thread.currentThread() }?.interrupt()
        controls.shutdownNow()
        displayCommands.shutdownNow()
        densityRevision.incrementAndGet()
        // Restore before closing control, while the server can still consume it.
        // Server cleanup=true independently restores display power after process death.
        synchronized(controlLock) {
            activePinch?.let { run ->
                run.nextStep?.cancel(false)
                runCatching { releasePinchPointers(run) }
                    .onFailure { Log.w(TAG, "Pinch release delegated to display destruction: $it") }
            }
            activePinch = null
            pinchReserved.set(false)
            if (screenOffSent) {
                runCatching {
                    control?.output?.apply {
                        write(ScrcpyProtocol.displayPower(true))
                        flush()
                    }
                }.onFailure { Log.w(TAG, "Physical screen restore delegated to scrcpy cleanup: $it") }
                screenOffSent = false
            }
        }
        val held = synchronized(channelsLock) {
            listOfNotNull(sync, video, control, shell, wakeupCommand, densityCommand).also {
                sync = null
                video = null
                control = null
                shell = null
                wakeupCommand = null
                densityCommand = null
            }
        }
        held.forEach { runCatching { it.close() } }
        // Official cleanup unlinks the jar on normal startup. Remove our exact
        // generated temporary file as well if startup failed before it could run.
        val remote = remoteServerPath
        if (remote != null && AdbSession.isConnected()) {
            runCatching {
                AdbSession.open("shell:rm -f '$remote'", 2000L).use { it.input.readBytes() }
            }.onFailure { Log.w(TAG, "Could not remove temporary scrcpy server: $it") }
        }
        status("scrcpy остановлен; отдельный дисплей освобождается сервером")
    }

    private fun status(message: String) {
        Log.i(TAG, message)
        runCatching { listener.onStatus(message) }
    }

    private fun serverCommand(remote: String, scid: String): String {
        // Only validated numeric values and our generated private path enter the shell.
        val options = listOf(
            "log_level=debug", "scid=$scid", "video=true", "audio=false", "control=true",
            "tunnel_forward=true", "send_dummy_byte=true", "send_device_meta=false",
            "send_stream_meta=true", "send_frame_meta=true", "video_codec=h264",
            "video_bit_rate=${videoProfile.bitRate}", "max_fps=${videoProfile.maxFps}", "downsize_on_error=false",
            "new_display=${width}x$height/$dpi", "capture_orientation=@0",
            "vd_destroy_content=true", "vd_system_decorations=false", "display_ime_policy=local",
            // API 30's userActivity has no display id and affects the phone.
            "stay_awake=true", "keep_active=${Build.VERSION.SDK_INT >= 31}", "power_on=false",
            "power_off_on_close=false", "clipboard_autosync=false", "cleanup=true",
            // Pin the encoder's SDR color contract. Device defaults produced
            // nonstandard VUI metadata and failed RGB conversion in HU decoders.
            "video_codec_options=profile=1,i-frame-interval=${videoProfile.iFrameIntervalSeconds}," +
                "color-standard=${MediaFormat.COLOR_STANDARD_BT709}," +
                "color-transfer=${MediaFormat.COLOR_TRANSFER_SDR_VIDEO}," +
                "color-range=${MediaFormat.COLOR_RANGE_LIMITED}"
        ) + listOfNotNull(maxVideoSize?.let { "max_size=$it" })
        SessionDiagnostics.event("H.264 encoder color requested: standard=BT709; transfer=SDR_VIDEO; range=LIMITED; " +
            "androidValues=${MediaFormat.COLOR_STANDARD_BT709}/${MediaFormat.COLOR_TRANSFER_SDR_VIDEO}/${MediaFormat.COLOR_RANGE_LIMITED}")
        return "CLASSPATH='$remote' app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
            options.joinToString(" ") + " 2>&1"
    }

    companion object {
        const val SERVER_VERSION = "5.0.1"
        const val ASSET_NAME = "scrcpy-server-v5.0.1"
        private const val FIRST_HU_POINTER_ID = 1L
        private const val SECOND_HU_POINTER_ID = 2L
        private const val SHELL_COMMAND_TIMEOUT_MS = 8000L
        private const val MAX_SHELL_OUTPUT_BYTES = 1024 * 1024
        private val SHELL_ERROR_MARKERS = listOf(
            "Error:", "Exception", "Permission Denial", "Unknown command", "not supported"
        )
        private const val SERVER_SHA256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
        private const val TAG = "CarBridge/Scrcpy"
    }
}
