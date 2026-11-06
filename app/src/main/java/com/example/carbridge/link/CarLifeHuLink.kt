package com.example.carbridge.link

import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.link.proto.PbReader
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * HuLink поверх USB-аксессуара (AOA).
 *
 * Порядок рукопожатия сверён с CarLifeSession.onCmd, Handshake и onVideoInit рабочего
 * приложения headswap 2.2.0. Возможны различия конкретных ГУ; проверять
 * по логам реального ГУ: все неизвестные команды пишутся в Logcat (тег CarBridge/Link).
 */
class CarLifeHuLink internal constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeTransport: () -> Unit,
    private val auth: HuAuthenticator,
    private val defaultFps: Int
) : HuLink {
    constructor(
        pfd: ParcelFileDescriptor,
        auth: HuAuthenticator = HuAuthProvider.authenticator,
        defaultFps: Int = 30
    ) : this(FileInputStream(pfd.fileDescriptor), FileOutputStream(pfd.fileDescriptor),
        { pfd.close() }, auth, defaultFps)

    constructor(
        transport: CarLifeWifiTransport,
        auth: HuAuthenticator = HuAuthProvider.authenticator,
        defaultFps: Int = 30
    ) : this(transport.input, transport.output, { transport.close() }, auth, defaultFps)

    init { require(defaultFps in 1..30) }

    private lateinit var listener: HuLink.Listener
    private val closed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "hu-writer") }
    private val sched = Executors.newSingleThreadScheduledExecutor { Thread(it, "hu-sched") }
    private val packets = CarLifePacketQueue()
    private val writerRunning = AtomicBoolean(false)
    private val videoLock = Any()
    private val audioLock = Any()
    @Volatile private var mediaAudioStarted = false
    private var mediaPcmBytes = 0L
    private var lastAudioStatsNs = 0L
    private val keyframeRequested = AtomicBoolean(false)
    private val lastWriteProgressNs = AtomicLong(System.nanoTime())
    private val profile = HuSessionProfile()
    private val completedWireBytes = AtomicLong()
    private val completedVideoFrames = AtomicLong()
    private val writeBusyNs = AtomicLong()
    private val lastVideoCompletedNs = AtomicLong()
    private val heartbeatPending = AtomicBoolean()
    private var lastWireReportNs = System.nanoTime()
    private var reportedWireBytes = 0L
    private var reportedBusyNs = 0L
    private var heartbeatCount = 0L
    private val navigationLock = Any()
    private var navigationActive = false
    private var navigationSubscribed: Boolean? = null
    private var navigationStartedNs = 0L
    private var navigationShowRequired = true
    private var navigationFallbackLogged = false
    private val navEpoch = AtomicLong()
    override val navigationEpoch: Long get() = navEpoch.get()
    private val microphoneLock = Any()
    private var probeActive = false
    private var probeRevision = 0L
    private var probeStartedNs = 0L
    private var probeBytes = 0L
    private var probePackets = 0L
    private var probePeak = 0
    private var probeMalformed = 0L

    private val frames = FrameReader()
    private val inner = InnerAssembler()

    @Volatile private var started = false
    @Volatile private var paused = false
    @Volatile private var needKey = true
    @Volatile private var csd: ByteArray? = null
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0
    @Volatile private var videoInited = false
    private var protoVersion = 1
    @Volatile private var matchSent = false
    private var videoFps = defaultFps
    private val reading = AtomicBoolean(false)
    private val disconnected = AtomicBoolean(false)
    @Volatile private var lastVideoTxNs = System.nanoTime()
    private var lastQueueLogNs = 0L
    @Volatile private var lastReadNs = System.nanoTime()
    @Volatile private var lastCommandType = 0
    @Volatile private var writingChannel = -1
    @Volatile private var writingFrameBytes = 0

    // ------------------------------------------------------------------ HuLink

    override fun start(listener: HuLink.Listener) {
        if (closed.get() || !reading.compareAndSet(false, true)) return
        this.listener = listener
        Log.i(TAG, "CarLife transport start; ожидаю HU_PROTOCOL_VERSION")
        SessionDiagnostics.event("CarLife transport start; waiting HU_PROTOCOL_VERSION")
        schedule(HANDSHAKE_WARNING_MS) {
            if (!matchSent) {
                Log.w(TAG, "За 5 с нет HU_PROTOCOL_VERSION: проверьте CarLife transport and HU")
                SessionDiagnostics.event("HU_PROTOCOL_VERSION not received within 5s; CarLife handshake not established")
            }
        }
        schedule(HANDSHAKE_TIMEOUT_MS) {
            if (!matchSent) disconnect("ГУ не прислало HU_PROTOCOL_VERSION за ${HANDSHAKE_TIMEOUT_MS / 1000} с")
        }
        schedule(1000L, ::keepalive)
        Thread(::readLoop, "hu-reader").start()
    }

    override fun stop() {
        finishMicrophoneProbe("сессия закрыта")
        if (!closed.compareAndSet(false, true)) return
        packets.close()
        runCatching { sched.shutdownNow() }
        runCatching { writer.shutdownNow() }
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { closeTransport() }
        SessionDiagnostics.event("HU input summary; messages=$touchRx; twoPointerPayloads=$twoPointerRx; types=${touchTypes.entries.joinToString { "0x${it.key.toString(16)}:${it.value}" }}")
        Log.i(TAG, "closed")
    }

    override fun startMediaAudio(): Boolean = synchronized(audioLock) {
        if (closed.get() || !matchSent) return@synchronized false
        if (mediaAudioStarted) return@synchronized true
        packets.discardAudio()
        sendCmd(Msg.MODULE_STATUS, Msgs.musicStatus(true))
        val accepted = send(Wire.CH_MEDIA, Wire.dataInner(Msg.MEDIA_INIT, 0, Msgs.mediaAudioInit()))
        mediaAudioStarted = accepted
        SessionDiagnostics.event("CarLife MEDIA_INIT sent=$accepted; pcm=48000/stereo/16")
        accepted
    }
    override fun sendMediaPcm(pcm: ByteArray): Boolean = synchronized(audioLock) {
        if (!mediaAudioStarted || closed.get() || pcm.isEmpty() || pcm.size > 10240 || pcm.size % 4 != 0) return@synchronized false
        val packet = Wire.outer(Wire.CH_MEDIA, Wire.dataInner(Msg.MEDIA_DATA, System.currentTimeMillis().toInt(), pcm))
        if (packets.bytes == 0) lastWriteProgressNs.set(System.nanoTime())
        val accepted = packets.offerAudio(CarLifePacketQueue.Packet(Wire.CH_MEDIA, packet))
        if (accepted) { mediaPcmBytes += pcm.size; startWriter() }
        val now = System.nanoTime()
        if (now - lastAudioStatsNs >= 10_000_000_000L) {
            lastAudioStatsNs = now
            SessionDiagnostics.event("CarLife audio queued; pcmBytes=$mediaPcmBytes; queueBytes=${packets.bytes}; droppedAudioBytes=${packets.droppedAudioBytes}")
        }
        accepted
    }
    override fun stopMediaAudio() = synchronized(audioLock) {
        packets.discardAudio()
        if (mediaAudioStarted) {
            mediaAudioStarted = false
            send(Wire.CH_MEDIA, Wire.dataInner(Msg.MEDIA_STOP, 0, null))
            sendCmd(Msg.MODULE_STATUS, Msgs.musicStatus(false))
            SessionDiagnostics.event("CarLife MEDIA_STOP sent")
        }
    }

    override fun sendMediaInfo(info: HuLink.MediaInfo): Boolean =
        !closed.get() && matchSent && info.artwork.size <= 30720 && sendCmd(Msg.MEDIA_INFO, Msgs.mediaInfo(info))
    override fun sendMediaProgress(seconds: Int): Boolean =
        !closed.get() && matchSent && sendCmd(Msg.MEDIA_PROGRESS_BAR, Msgs.mediaProgress(seconds))

    override fun setNavigationActive(active: Boolean): Boolean = synchronized(navigationLock) {
        if (closed.get() || !matchSent) return@synchronized false
        if (navigationActive == active) return@synchronized true
        if (!sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(1, active))) return@synchronized false
        navigationActive = active
        navigationStartedNs = System.nanoTime()
        navigationShowRequired = true
        navigationFallbackLogged = false
        navEpoch.incrementAndGet()
        SessionDiagnostics.event("CarLife navigation module; active=$active; subscription=${navigationSubscribed ?: "unknown"}")
        true
    }
    override fun sendNavigation(info: HuLink.NavigationInfo): Boolean = synchronized(navigationLock) {
        if (closed.get() || !matchSent || info.action !in 1..3 || info.turnImage.size > 8192) return@synchronized false
        if (navigationSubscribed == false) return@synchronized false
        if (info.action != 3) {
            if (!setNavigationActive(true)) return@synchronized false
            if (navigationSubscribed == null) {
                if (System.nanoTime() - navigationStartedNs < 1_500_000_000L) return@synchronized false
                if (!navigationFallbackLogged) {
                    navigationFallbackLogged = true
                    SessionDiagnostics.event("CarLife navigation subscription not advertised; proceeding after 1500ms; explicit STOP still respected")
                }
            }
        }
        val outgoing = if (info.action != 3 && navigationShowRequired) info.copy(action = 1) else info
        val sent = sendCmd(Msg.NAVI_NEXT_TURN_INFO, Msgs.navigationInfo(outgoing))
        if (sent) navigationShowRequired = outgoing.action == 3
        sent
    }

    override fun startMicrophoneProbe(): Boolean = synchronized(microphoneLock) {
        if (closed.get() || !matchSent || !started) {
            listener.onDiagnosticStatus("Проверка микрофона: сначала подключитесь к ГУ через CarLife")
            return@synchronized false
        }
        if (probeActive) {
            listener.onDiagnosticStatus("Проверка микрофона уже выполняется; дождитесь результата или остановите её")
            return@synchronized false
        }
        if (profile.voiceMic == 2) {
            SessionDiagnostics.event("HU microphone probe refused; VOICE_MIC=2")
            listener.onDiagnosticStatus("ГУ сообщает VOICE_MIC=2: голосовой вход CarLife не поддерживается")
            return@synchronized false
        }
        probeBytes = 0; probePackets = 0; probePeak = 0; probeMalformed = 0
        probeStartedNs = System.nanoTime()
        probeActive = true
        val revision = ++probeRevision
        if (!sendCmd(Msg.MIC_RECORD_RECOG_START)) {
            probeActive = false
            listener.onDiagnosticStatus("Не удалось отправить запрос микрофона ГУ")
            return@synchronized false
        }
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(4, true))
        SessionDiagnostics.event("HU microphone probe started; durationMs=10000; VOICE_MIC=${profile.voiceMic ?: "unknown"}; expectedPcm=16000/mono/s16le; storage=none")
        listener.onDiagnosticStatus("Микрофон ГУ: проверка 10 секунд. Произнесите несколько слов; запись в файл не ведётся")
        schedule(3000L) { synchronized(microphoneLock) {
            if (probeActive && probeRevision == revision) {
                SessionDiagnostics.event("HU microphone probe progress; packets=$probePackets; bytes=$probeBytes; peak=$probePeak")
                if (probeBytes == 0L) listener.onDiagnosticStatus("Микрофон ГУ: данных пока нет, ожидаю до конца проверки")
            }
        } }
        schedule(10_000L) { synchronized(microphoneLock) {
            if (probeActive && probeRevision == revision) finishMicrophoneProbe("время проверки истекло")
        } }
        true
    }
    override fun stopMicrophoneProbe() { finishMicrophoneProbe("остановлено пользователем") }
    private fun finishMicrophoneProbe(reason: String) = synchronized(microphoneLock) {
        if (!probeActive) return@synchronized
        probeActive = false
        probeRevision++
        sendCmd(Msg.MIC_RECORD_END)
        sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(4, false))
        val elapsedMs = (System.nanoTime() - probeStartedNs) / 1_000_000L
        SessionDiagnostics.event("HU microphone probe finished; reason=$reason; elapsedMs=$elapsedMs; packets=$probePackets; bytes=$probeBytes; peak=$probePeak; malformed=$probeMalformed; storage=none; assistantConnected=false")
        val result = when {
            probeBytes == 0L -> "данные от ГУ не пришли"
            probePeak == 0 -> "получено $probeBytes байт, сигнал нулевой при интерпретации PCM16"
            else -> "получено $probeBytes байт, ненулевой сигнал; пик $probePeak (PCM16)"
        }
        listener.onDiagnosticStatus("Проверка микрофона: $result. Это ещё не подключение микрофона к Алисе")
    }
    private fun onMicrophonePacket(msg: ByteArray) = synchronized(microphoneLock) {
        if (!probeActive) return@synchronized
        if (msg.size < 12 || Wire.u32(msg, 8) != Msg.VR_MIC_DATA || Wire.u32(msg, 0) != msg.size - 12) {
            probeMalformed++; return@synchronized
        }
        probePackets++; probeBytes += msg.size - 12
        for (index in 12 until msg.size - 1 step 2) {
            val sample = ((msg[index].toInt() and 255) or (msg[index + 1].toInt() shl 8)).toShort().toInt()
            probePeak = maxOf(probePeak, kotlin.math.abs(sample))
        }
    }

    override fun sendConfig(csd: ByteArray) {
        this.csd = H264.normalize(csd)
    }

    override fun sendVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        synchronized(videoLock) { enqueueVideoFrame(data, keyFrame) }
    }

    private fun enqueueVideoFrame(data: ByteArray, keyFrame: Boolean) {
        if (!started || paused || closed.get()) return
        if (needKey && !keyFrame) return
        // A whole keyframe may exceed the soft limit, but is admitted only alone.
        // Never discard an in-flight packet or resume an H.264 GOP after dropping a delta frame.
        if (packets.bytes > 0 && packets.bytes + data.size + (csd?.size ?: 0) + 64 > MAX_QUEUE_BYTES) {
            val discarded = packets.discardVideo()
            heartbeatPending.set(false)
            val now = System.nanoTime()
            if (now - lastQueueLogNs > 5_000_000_000L) {
                lastQueueLogNs = now
                SessionDiagnostics.event("CarLife video backpressure; queuedBytes=${packets.bytes}; " +
                    "discardedBytes=$discarded; limit=$MAX_QUEUE_BYTES; waiting natural keyframe; encoder retained")
                listener.onTransportBackpressure(packets.bytes)
            }
            needKey = true
            if (!keyFrame || packets.bytes > 0) return
        }
        var p = H264.normalize(data)
        if (keyFrame) {
            val c = csd
            if (c != null && !H264.startsWithSps(p)) p = c + p
            needKey = false
            keyframeRequested.set(false)
        }
        p = H264.fixSps(p, videoW, videoH)
        if (send(Wire.CH_VIDEO, Wire.dataInner(Msg.VIDEO_DATA, unixSeconds(), p))) {
            lastVideoTxNs = System.nanoTime()
        }
    }

    // ------------------------------------------------------------------ IO

    private fun readLoop() {
        val buf = ByteArray(16384)
        var firstRead = true
        var endReason = "CarLife input closed"
        try {
            while (!closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    endReason = "transport EOF"
                    break
                }
                if (n > 0) lastReadNs = System.nanoTime()
                if (firstRead && n > 0) {
                    Log.i(TAG, "USB RX первые $n байт: ${hex(buf.copyOf(n), 24)}")
                    firstRead = false
                }
                frames.feed(buf, 0, n) { ch, payload ->
                    inner.feed(ch, payload) { c, msg -> dispatch(c, msg) }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "readLoop", e)
                endReason = "read exception ${e.javaClass.simpleName}: ${e.message}"
                SessionDiagnostics.event("CarLife read failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        } finally {
            if (!closed.get()) {
                disconnect(endReason)
            }
        }
    }

    private fun send(channel: Int, innerBytes: ByteArray): Boolean {
        if (closed.get()) return false
        val frame = Wire.outer(channel, innerBytes)
        if (packets.bytes == 0) lastWriteProgressNs.set(System.nanoTime())
        if (!packets.offer(CarLifePacketQueue.Packet(channel, frame))) return false
        startWriter()
        return true
    }

    private fun startWriter() {
        if (closed.get() || !writerRunning.compareAndSet(false, true)) return
        try {
            writer.execute {
              try {
               while (!closed.get()) {
                val packet = packets.poll() ?: break
                val channel = packet.channel
                val frame = packet.bytes
                writingChannel = channel
                writingFrameBytes = frame.size
                val writeStartedNs = System.nanoTime()
                var writtenBytes = 0
                try {
                    // Как Wire.writeSequence в headswap: отдельная запись заголовка 8 байт,
                    // затем фрагменты inner <= 16384. Это сохраняет границы USB-записей.
                    output.write(frame, 0, 8)
                    writtenBytes = 8
                    lastWriteProgressNs.set(System.nanoTime())
                    var off = 8
                    while (off < frame.size) {
                        val n = minOf(Wire.WRITE_CHUNK, frame.size - off)
                        output.write(frame, off, n)
                        off += n
                        writtenBytes = off
                        // Watchdog следит за реальным продвижением записи, а не только за
                        // завершением целого H.264 кадра, который может быть крупным.
                        lastWriteProgressNs.set(System.nanoTime())
                    }
                    output.flush()
                    lastWriteProgressNs.set(System.nanoTime())
                    completedWireBytes.addAndGet(frame.size.toLong())
                    writeBusyNs.addAndGet(System.nanoTime() - writeStartedNs)
                    if (channel == Wire.CH_VIDEO) {
                        lastVideoCompletedNs.set(System.nanoTime())
                        if (frame.size > 20 && Wire.u32(frame, 16) == Msg.VIDEO_DATA) completedVideoFrames.incrementAndGet()
                    }
                    if (channel == Wire.CH_CMD && frame.size >= 16) {
                        val type = Wire.u32(frame, 12)
                        if (type == Msg.MEDIA_INFO || type == Msg.NAVI_NEXT_TURN_INFO)
                            SessionDiagnostics.event("CarLife metadata USB write complete; type=0x${type.toString(16)}; bytes=${frame.size}; huAck=false")
                    }
                } catch (e: Exception) {
                    if (!closed.get()) {
                        Log.e(TAG, "write: $e")
                        SessionDiagnostics.event("CarLife write failed: ${e.javaClass.simpleName}: ${e.message}; " +
                            "channel=$channel frameBytes=${frame.size} writtenBytes=$writtenBytes")
                        disconnect("write exception ${e.javaClass.simpleName}: ${e.message}")
                    }
                } finally {
                    val durationMs = (System.nanoTime() - writeStartedNs) / 1_000_000L
                    if (durationMs >= SLOW_WRITE_LOG_THRESHOLD_MS) {
                        SessionDiagnostics.event("CarLife slow write; channel=$channel frameBytes=${frame.size}; " +
                            "writtenBytes=$writtenBytes duration_ms=$durationMs")
                    }
                    packets.complete(packet)
                    if (channel == Wire.CH_VIDEO && frame.size == 20) heartbeatPending.set(false)
                    writingChannel = -1
                    writingFrameBytes = 0
                }
               }
              } finally {
                writerRunning.set(false)
                if (!closed.get() && packets.hasPending) startWriter()
              }
            }
        } catch (e: RejectedExecutionException) {
            writerRunning.set(false)
            if (!closed.get()) disconnect("writer executor unavailable")
        }
    }

    private fun sendCmd(type: Int, body: ByteArray? = null): Boolean {
        if (closed.get()) return false
        Log.i(TAG, "TX CMD 0x${type.toString(16)} body=${body?.size ?: 0}")
        SessionDiagnostics.event("CarLife TX CMD 0x${type.toString(16)} bytes=${body?.size ?: 0}")
        return send(Wire.CH_CMD, Wire.cmdInner(type, body))
    }

    // ------------------------------------------------------------------ dispatch

    private fun dispatch(channel: Int, msg: ByteArray) {
        if (closed.get()) return
        when (channel) {
            Wire.CH_CMD -> Wire.parseCmd(msg)?.let { onCmd(it.type, it.body) }
            Wire.CH_TOUCH -> Wire.parseCmd(msg)?.let { onTouchMsg(it.type, it.body) }
            Wire.CH_VR -> onMicrophonePacket(msg)
            Wire.CH_MEDIA -> {
                if (msg.size >= 12) {
                    val type = Wire.u32(msg, 8)
                    SessionDiagnostics.event("CarLife RX MEDIA type=0x${type.toString(16)} bytes=${msg.size - 12}")
                    when (type) {
                        Msg.MEDIA_PAUSE, Msg.MEDIA_STOP -> if (mediaAudioStarted) {
                            stopMediaAudio()
                            listener.onAudioSuspend()
                        }
                        Msg.MEDIA_RESUME -> listener.onAudioResume()
                    }
                }
            }
            Wire.CH_VIDEO -> Log.d(TAG, "video-канал от ГУ: ${hex(msg, 24)}")
            else -> Log.d(TAG, "канал $channel проигнорирован")
        }
    }

    private fun onCmd(type: Int, body: ByteArray) {
        lastCommandType = type
        Log.i(TAG, "RX CMD 0x${type.toString(16)} body=${body.size}")
        SessionDiagnostics.event("CarLife RX CMD 0x${type.toString(16)} bytes=${body.size}")
        when (type) {
            Msg.HU_PROTOCOL_VERSION -> {
                if (matchSent) return
                matchSent = true
                protoVersion = PbReader.parse(body)?.let { PbReader.int(it, 1) } ?: 1
                Log.i(TAG, "HU protocol v$protoVersion")
                sendCmd(Msg.PROTOCOL_VERSION_MATCH_STATUS, Msgs.matchStatus(1))
                sendCmd(Msg.FOREGROUND)
                sendCmd(Msg.SCREEN_ON)
                sendCmd(Msg.MD_INFO, Msgs.deviceInfo())
                schedule(MODULE_STATUS_DELAY_MS) {
                    sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatusList(protoVersion))
                    synchronized(navigationLock) {
                        if (navigationActive) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(1, true))
                    }
                    synchronized(microphoneLock) {
                        if (probeActive) sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(4, true))
                    }
                }
            }

            Msg.HU_INFO -> {
                profile.readDevice(body)
                SessionDiagnostics.event("HU identity; ${profile.summary()}")
            }

            Msg.HU_AUTHEN_REQUEST -> {
                val random = PbReader.parse(body)?.let { PbReader.str(it, 1) }
                Log.i(TAG, "HU_AUTHEN_REQUEST branch=${if (random?.startsWith("2.0;") == true) "2.0" else "RSA"}")
                val answer = if (random != null) runCatching { auth.respond(random) }.getOrNull() else null
                if (answer == null) {
                    Log.e(TAG, "авторизация: ответа нет, закрываю сессию"); disconnect("HU authentication response unavailable")
                } else {
                    sendCmd(Msg.MD_AUTHEN_RESPONSE, Msgs.authenResponse(answer))
                }
            }

            Msg.HU_AUTHEN_RESULT -> {
                val result = PbReader.parse(body)?.let { PbReader.int(it, 1) }
                Log.i(TAG, "HU_AUTHEN_RESULT $result")
                SessionDiagnostics.event("HU_AUTHEN_RESULT $result")
                // headswap записывает результат и продолжает протокол. Не закрываем
                // USB по пустому/отрицательному сообщению: причину покажут следующие команды ГУ.
            }

            Msg.STATISTIC_INFO -> {
                profile.readStatistics(body)
                SessionDiagnostics.event("HU compatibility selected; ${profile.summary()}")
                sendCmd(Msg.MD_AUTHEN_RESULT, Msgs.authenResult(true))
                sendCmd(Msg.MD_FEATURE_CONFIG_REQUEST)
                sendCmd(Msg.CAR_DATA_SUBSCRIBE)
            }

            Msg.HU_FEATURE_CONFIG_RESPONSE -> {
                val capabilities = profile.readFeatures(body)
                SessionDiagnostics.event("HU capabilities; values=$capabilities; voiceMicMeaning=${when (profile.voiceMic) { 0 -> "hu"; 1 -> "phone"; 2 -> "unsupported"; else -> "unknown" }}")
                Log.i(TAG, "FEATURE_CONFIG ${hex(body, 96)}")
                SessionDiagnostics.event("HU feature config bytes=${body.size}; data=${hex(body, 192)}")
            }

            Msg.CARLIFE_DATA_SUBSCRIBE ->
                Msgs.subscribeDone(body)?.let { sendCmd(Msg.CARLIFE_DATA_SUBSCRIBE_DONE, it) }

            Msg.CARLIFE_DATA_SUBSCRIBE_START, Msg.CARLIFE_DATA_SUBSCRIBE_STOP -> {
                val ids = Msgs.subscriptionIds(body)
                SessionDiagnostics.event("HU metadata subscription; enabled=${type == Msg.CARLIFE_DATA_SUBSCRIBE_START}; modules=${ids ?: "malformed"}")
                if (ids?.contains(0) == true) synchronized(navigationLock) {
                    navigationSubscribed = type == Msg.CARLIFE_DATA_SUBSCRIBE_START
                    navigationShowRequired = true
                    navEpoch.incrementAndGet()
                }
            }

            Msg.VIDEO_ENCODER_INIT -> onVideoInit(body)

            Msg.VIDEO_ENCODER_START -> startVideo()

            Msg.VIDEO_ENCODER_PAUSE -> synchronized(videoLock) {
                paused = true
                needKey = true
                val dropped = packets.discardVideo()
                heartbeatPending.set(false)
                SessionDiagnostics.event("HU video PAUSE; discardedBytes=$dropped; display and encoder retained")
                schedule(0L, ::maybeSendVideoKeepalive)
            }

            Msg.VIDEO_ENCODER_RESET -> synchronized(videoLock) {
                needKey = true
                packets.discardVideo()
                heartbeatPending.set(false)
                if (!paused) requestKeyframeOnce()
            }

            Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE -> {
                val fps = PbReader.parse(body)?.let { PbReader.int(it, 1) } ?: defaultFps
                if (fps in 3..30) {
                    // Как headswap с фиксированной настройкой FPS: ACK без перестройки энкодера.
                    Log.i(TAG, "Запрос FPS=$fps; фиксированный энкодер FPS=$defaultFps")
                    sendCmd(Msg.VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE, Msgs.frameRate(fps))
                }
            }

            Msg.MODULE_CONTROL -> {
                val fields = PbReader.parse(body)
                val module = fields?.let { PbReader.int(it, 1) }
                val state = fields?.let { PbReader.int(it, 2) }
                SessionDiagnostics.event("HU module control; module=$module; state=$state")
                if (module == 3) {
                    if (state == 0 && mediaAudioStarted) {
                        stopMediaAudio()
                        listener.onAudioSuspend()
                    } else {
                        // Do not treat an idle STOP echo as a new user pause.
                        // State 1 reports status and never starts playback or capture.
                        sendCmd(Msg.MODULE_STATUS, Msgs.musicStatus(mediaAudioStarted))
                    }
                }
                if (module == 1) synchronized(navigationLock) {
                    sendCmd(Msg.MODULE_STATUS, Msgs.moduleStatus(1, navigationActive))
                }
            }
            Msg.MD_EXIT -> disconnect("HU sent MD_EXIT")

            Msg.CONNECT_EXCEPTION, Msg.ERROR_CODE -> {
                Log.w(TAG, "ошибка от ГУ type=$type ${hex(body, 32)}")
                SessionDiagnostics.event("HU reported CarLife error type=0x${type.toString(16)} data=${hex(body, 32)}")
            }

            else -> Log.i(TAG, "необработанная команда $type (0x${Integer.toHexString(type)}) ${hex(body, 32)}")
        }
    }

    private fun onVideoInit(body: ByteArray) {
        val f = PbReader.parse(body)
        val w = f?.let { PbReader.int(it, 1) } ?: 0
        val h = f?.let { PbReader.int(it, 2) } ?: 0
        val requestedFps = f?.let { PbReader.int(it, 3) } ?: 0
        // INIT_DONE advertises the selected encoder limit.
        val fps = defaultFps
        Log.i(TAG, "ГУ просит видео ${w}x$h fps=$requestedFps; выбран fps=$fps")
        SessionDiagnostics.event("HU requests ${w}x$h fps=$requestedFps; selected=$fps")

        if (videoInited) {
            sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(videoW, videoH, videoFps))
            return
        }
        if (w <= 0 || h <= 0) {
            Log.e(TAG, "Некорректный размер видео ${w}x$h")
            disconnect("invalid HU video size ${w}x$h")
            return
        }
        videoFps = fps
        videoW = w
        videoH = h
        videoInited = true
        Log.i(TAG, "Формат CarLife: запрошено и выбрано ГУ ${videoW}x$videoH")
        SessionDiagnostics.event("HU requested ${w}x$h; CarLife output=${videoW}x$videoH")
        sendCmd(Msg.VIDEO_ENCODER_INIT_DONE, Msgs.videoEncoderInfo(videoW, videoH, fps))
        sendCmd(Msg.FOREGROUND)
        if (!started) {
            listener.onHuReady(videoW, videoH)
            // Если ГУ не пришлёт START, стартуем сами.
            schedule(START_FALLBACK_MS) { if (!started && !paused) startVideo() }
        }
    }

    private fun startVideo() = synchronized(videoLock) {
        if (closed.get() || (started && !paused)) return@synchronized
        val fallback = !videoInited
        if (fallback) {
            // Как headswap с фиксированным режимом, если ГУ прислал START без INIT.
            videoW = STANDARD_VIDEO_WIDTH
            videoH = STANDARD_VIDEO_HEIGHT
            videoFps = defaultFps
            videoInited = true
            Log.w(TAG, "START без INIT: выбран резервный режим ${videoW}x$videoH")
        }
        started = true; paused = false; needKey = true
        keyframeRequested.set(false)
        lastVideoTxNs = System.nanoTime()
        Log.i(TAG, "video start")
        packets.discardVideo()
        heartbeatPending.set(false)
        SessionDiagnostics.event("HU video START/resume; awaiting keyframe; display retained")
        if (fallback) listener.onHuReady(videoW, videoH)
        requestKeyframeOnce()
    }

    private var touchRx = 0
    private var twoPointerRx = 0
    private val touchTypes = java.util.concurrent.ConcurrentHashMap<Int,Int>()

    private fun onTouchMsg(type: Int, body: ByteArray) {
        // Диагностика: первые сообщения тача целиком, дальше раз в 100.
        touchRx++
        val firstType = !touchTypes.containsKey(type)
        touchTypes[type] = (touchTypes[type] ?: 0) + 1
        if (firstType || type == Msg.TOUCH_ACTION_POINTER_DOWN || type == Msg.TOUCH_ACTION_POINTER_UP || type == Msg.TOUCH_ACTION_OTHERPOINTER_UP) {
            SessionDiagnostics.event("HU touch wire type=0x${type.toString(16)}; fields=" +
                (PbReader.parse(body)?.joinToString { "${it.num}:${it.wire}" } ?: "invalid") + "; bytes=${hex(body,48)}")
        }
        if (touchRx <= 15 || touchRx % 100 == 0) {
            Log.i(TAG, "RX TOUCH #$touchRx 0x${type.toString(16)} ${hex(body, 16)}")
        }
        if (type == Msg.TOUCH_CAR_HARD_KEY_CODE) {
            PbReader.parse(body)?.let { PbReader.int(it, 1) }?.let { listener.onHardKey(it) }
            return
        }
        val event = CarLifeTouch.parse(type, body)
        if (event == null) {
            if (type in TOUCH_ACTION_TYPES && (touchRx <= 15 || touchRx % 100 == 0)) {
                SessionDiagnostics.event("HU touch parse failed: type=0x${type.toString(16)} bytes=${hex(body, 20)}")
            }
            return
        }
        if(event.pointerX != null && event.pointerY != null) {
            twoPointerRx++
            if(twoPointerRx <= 3) SessionDiagnostics.event("HU two-pointer payload #$twoPointerRx; action=${event.action}; bytes=${hex(body,48)}")
        }
        if (touchRx <= 10 || event.action == 5 || event.action == 6 || touchRx % 100 == 0) {
            val second = if (event.pointerX != null && event.pointerY != null) {
                "${event.pointerX},${event.pointerY}"
            } else "none"
            SessionDiagnostics.event("HU touch #$touchRx action=${event.action} index=${event.actionIndex} " +
                "p0=${event.x},${event.y} p1=$second")
        }
        listener.onTouch(event)
    }

    // ------------------------------------------------------------------ utils

    private fun keepalive() {
        val queued = packets.bytes
        if (queued > 0 && System.nanoTime() - lastWriteProgressNs.get() >= WRITE_STALL_TIMEOUT_NS) {
            SessionDiagnostics.event("CarLife writer stalled for ${WRITE_STALL_TIMEOUT_MS}ms; queuedBytes=$queued; " +
                "readIdleMs=${(System.nanoTime() - lastReadNs) / 1_000_000L}; lastCmd=0x${lastCommandType.toString(16)}; " +
                "writingChannel=$writingChannel; frameBytes=$writingFrameBytes; paused=$paused; closing session")
            disconnect("writer stalled for ${WRITE_STALL_TIMEOUT_MS}ms; queuedBytes=$queued")
            return
        }
        maybeSendVideoKeepalive()
        val now = System.nanoTime()
        val interval = now - lastWireReportNs
        if (interval >= 10_000_000_000L) {
            val bytes = completedWireBytes.get()
            val busy = writeBusyNs.get()
            SessionDiagnostics.event("CarLife physical write stats; completedBytes=$bytes; wire_kbps=${(bytes - reportedWireBytes) * 8_000_000L / interval}; write_busy_pct=${((busy - reportedBusyNs) * 100 / interval).coerceIn(0L, 100L)}; queuedBytes=${packets.bytes}; videoFrames=${completedVideoFrames.get()}; videoWriteAgeMs=${if (lastVideoCompletedNs.get() == 0L) -1 else (now - lastVideoCompletedNs.get()) / 1_000_000L}; profile=${profile.name}; paused=$paused")
            lastWireReportNs = now; reportedWireBytes = bytes; reportedBusyNs = busy
        }
        schedule(100L, ::keepalive)
    }

    private fun maybeSendVideoKeepalive() = synchronized(videoLock) {
        if (closed.get() || !started || writingChannel == Wire.CH_VIDEO) return@synchronized
        val periodMs = when {
            paused && profile.pauseHeartbeat -> 100L
            paused -> 2000L
            profile.idleHeartbeat && completedVideoFrames.get() > 0 -> 1000L
            else -> return@synchronized
        }
        val now = System.nanoTime()
        val anchor = maxOf(lastVideoTxNs, lastVideoCompletedNs.get())
        if (now - anchor < periodMs * 1_000_000L || !heartbeatPending.compareAndSet(false, true)) return@synchronized
        val type = if (profile.pauseHeartbeat || !paused) Msg.VIDEO_HEARTBEAT else Msg.VIDEO_DATA
        if (send(Wire.CH_VIDEO, Wire.dataInner(type, unixSeconds(), ByteArray(0)))) {
            lastVideoTxNs = now
            heartbeatCount++
            if (heartbeatCount <= 3 || heartbeatCount % 100 == 0L)
                SessionDiagnostics.event("CarLife keepalive queued; type=0x${type.toString(16)}; periodMs=$periodMs; profile=${profile.name}; paused=$paused; count=$heartbeatCount")
        } else heartbeatPending.set(false)
    }

    private fun requestKeyframeOnce() {
        if (keyframeRequested.compareAndSet(false, true)) listener.onRequestKeyframe()
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        if (closed.get()) return
        try { sched.schedule({ if (!closed.get()) action() }, delayMs, TimeUnit.MILLISECONDS) }
        catch (_: RejectedExecutionException) { /* stop() одновременно с входящей командой */ }
    }

    private fun disconnect(reason: String) {
        if (!disconnected.compareAndSet(false, true)) return
        SessionDiagnostics.event("CarLife disconnect reason=$reason")
        Log.w(TAG, "CarLife disconnect: $reason")
        stop()
        if (::listener.isInitialized) listener.onHuDisconnected(reason)
    }

    private fun unixSeconds() = (System.currentTimeMillis() / 1000).toInt()

    private fun hex(b: ByteArray, max: Int): String =
        b.take(max).joinToString(" ") { "%02x".format(it) } + if (b.size > max) " …(${b.size})" else ""

    private companion object {
        const val STANDARD_VIDEO_WIDTH = 1280
        const val STANDARD_VIDEO_HEIGHT = 720
        val TOUCH_ACTION_TYPES = setOf(
            Msg.TOUCH_ACTION, Msg.TOUCH_ACTION_DOWN, Msg.TOUCH_ACTION_UP, Msg.TOUCH_ACTION_MOVE,
            Msg.TOUCH_ACTION_BEGIN, Msg.TOUCH_ACTION_POINTER_DOWN,
            Msg.TOUCH_ACTION_POINTER_UP, Msg.TOUCH_ACTION_OTHERPOINTER_UP,
        )
        const val TAG = "CarBridge/Link"
        const val MAX_QUEUE_BYTES = 600_000
        const val WRITE_STALL_TIMEOUT_MS = 15_000L
        const val WRITE_STALL_TIMEOUT_NS = WRITE_STALL_TIMEOUT_MS * 1_000_000L
        const val SLOW_WRITE_LOG_THRESHOLD_MS = 500L
        const val MODULE_STATUS_DELAY_MS = 500L
        const val START_FALLBACK_MS = 1500L
        const val HANDSHAKE_WARNING_MS = 5000L
        const val HANDSHAKE_TIMEOUT_MS = 12_000L
    }
}

