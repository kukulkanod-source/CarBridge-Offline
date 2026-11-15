package com.example.carbridge.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import com.example.carbridge.SessionDiagnostics
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import net.prototux.aare.aap.*
import net.prototux.aare.aap.cert.SelfSignedCertificate
import net.prototux.aare.aap.channels.*
import net.prototux.aare.aap.wireless.WirelessBootstrap
import net.prototux.aare.transport.wireless.WirelessConnector
import aap_protobuf.service.ServiceOuterClass.Service
import aap_protobuf.service.control.message.AudioFocusNotificationOuterClass.AudioFocusNotification
import aap_protobuf.service.control.message.AudioFocusRequestTypeOuterClass.AudioFocusRequestType
import aap_protobuf.service.control.message.AudioFocusStateTypeOuterClass.AudioFocusStateType
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import aap_protobuf.service.inputsource.message.InputReportOuterClass.InputReport
import aap_protobuf.service.media.shared.message.MediaCodecTypeOuterClass.MediaCodecType
import aap_protobuf.service.media.sink.message.VideoConfigurationOuterClass.VideoConfiguration
import aap_protobuf.service.media.sink.message.AudioStreamTypeOuterClass.AudioStreamType
import aap_protobuf.service.mediaplayback.message.MediaPlaybackMetadataOuterClass.MediaPlaybackMetadata
import aap_protobuf.service.mediaplayback.message.MediaPlaybackStatusOuterClass.MediaPlaybackStatus
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

/** Phone-side AAP to a bonded CarPlay→AA adapter. No Google AA app or CarLife USB changes. */
class AaWirelessHuLink(context: Context, private val address: String) : HuLink {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    @Volatile private var stopped = false
    @Volatile private var session: AapSession? = null
    @Volatile private var transport: Transport? = null
    @Volatile private var listener: HuLink.Listener? = null
    @Volatile private var video: VideoChannel? = null
    @Volatile private var audio: AudioChannel? = null
    @Volatile private var playback: MediaPlaybackChannel? = null
    @Volatile private var audioWanted = false
    @Volatile private var audioGranted = false
    private var audioFocusRequested = false
    private var focusRequestedAt = 0L
    private var audioSamples = 0L
    private var width = 1280
    private var height = 720
    private var inputChannel: InputChannel? = null
    private var sourceName = "CarBridge"
    private val pointerIds = ArrayList<Int>(2)
    private var disconnected = false

    private val log = object : AapLog {
        override fun i(msg: String) {
            SessionDiagnostics.event("AA: ${msg.take(240)}")
            if (!stopped) when {
                msg.startsWith("wireless: RFCOMM connected") -> listener?.onDiagnosticStatus("AA: Bluetooth адаптера подключён, получаю параметры Wi-Fi…")
                msg.startsWith("wireless: requesting network") -> listener?.onDiagnosticStatus("AA: подтвердите подключение к Wi-Fi адаптера в запросе Android")
            }
        }
        override fun w(msg: String, t: Throwable?) { i("warning: $msg");if(t!=null)i("cause: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}") }
        override fun e(msg: String, t: Throwable?) { i("error: $msg");if(t!=null)i("cause: ${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}") }
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: HuLink.Listener) {
        this.listener = listener
        scope.launch {
            try {
                val identity = loadIdentity()
                val prefs = context.getSharedPreferences("aa_wireless", 0)
                val id = prefs.getString("instance", null) ?: UUID.randomUUID().toString().also {
                    check(prefs.edit().putString("instance", it).commit())
                }
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                    ?: error("На телефоне нет Bluetooth")
                val device = adapter.bondedDevices.firstOrNull { it.address == address }
                    ?: error("Сначала выполните сопряжение с адаптером в настройках Bluetooth")
                listener.onDiagnosticStatus("AA: обращаюсь к адаптеру по Bluetooth…")
                val connector = WirelessConnector(context, log, WirelessConnector.Options(
                    bootstrap = WirelessBootstrap.Options(deviceSerial = id, instanceId = id, deviceName = "CarBridge")))
                val pipe = connector.connect(device)
                synchronized(gate) {
                    if (stopped || !isActive) { pipe.close(); return@launch }
                    transport = pipe
                    listener.onDiagnosticStatus("AA: Wi-Fi адаптера подключён, согласую экран…")
                    val pins = object : PinStore {
                        override fun get(peerKey: String): Set<String> = prefs.getStringSet("pin_$peerKey", emptySet()).orEmpty().toSet()
                        override fun add(peerKey: String, sha256: String) {
                            check(prefs.edit().putStringSet("pin_$peerKey", get(peerKey) + sha256).commit())
                        }
                    }
                    session = AapSession(pipe, identity, PhoneDescription("CarBridge", "CarBridge Offline", instanceId = id),
                        callbacks, log, SessionOptions(pins = pins, peerKey = address, trustOnFirstUse = true))
                    session!!.start()
                }
            } catch (e: CancellationException) {
                // stop() closes both an in-progress bootstrap and an established transport.
            } catch (e: Exception) {
                disconnect("AA: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private val callbacks = object : AapSession.Callbacks {
        override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> {
            val services = response.channelsList
            val out = ArrayList<ServiceChannel>()
            val screen = services.firstOrNull { it.hasMediaSinkService() &&
                it.mediaSinkService.videoConfigsCount > 0 && it.mediaSinkService.displayType.number == 0 &&
                it.mediaSinkService.videoConfigsList.any { c -> !c.hasVideoCodecType() || c.videoCodecType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP } }
                ?: error("Адаптер не предложил основной экран H.264")
            video = VideoChannel(screen, object : VideoChannel.Listener {
                override fun chooseConfiguration(configs: List<VideoConfiguration>, preferred: List<Int>): Int {
                    val candidates = preferred.filter { it in configs.indices }.ifEmpty { configs.indices.toList() }
                    // Match a standard landscape mode the adapter actually advertises; no forced 1920×720.
                    return candidates.filter { configs[it].codecResolution.number in 1..3 &&
                        (!configs[it].hasVideoCodecType() || configs[it].videoCodecType == MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP) }
                        .maxByOrNull { configs[it].codecResolution.number }
                        ?: error("Адаптер не предложил поддерживаемый альбомный видеорежим")
                }
                override fun onVideoStart(index: Int, config: VideoConfiguration) {
                    val size = when (config.codecResolution.number) {
                        1 -> 800 to 480
                        2 -> 1280 to 720
                        3 -> 1920 to 1080
                        else -> error("Неподдерживаемый размер экрана AA")
                    }
                    width = size.first; height = size.second
                    if (!stopped) listener?.onHuReady(width, height)
                    log.i("video mode ${width}x$height; margins=${config.widthMargin}x${config.heightMargin}; fps=${config.frameRate.number}")
                }
                override fun onVideoFocus(projected: Boolean) { if (projected && !stopped) listener?.onRequestKeyframe() }
                override fun onKeyFrameNeeded() { if (!stopped) listener?.onRequestKeyframe() }
            }).also(out::add)
            val inputs = services.filter { it.hasInputSourceService() }
            fun hasTouch(s: Service) = s.inputSourceService.touchscreenList.any { !it.isSecondary && it.width > 0 && it.height > 0 }
            (inputs.firstOrNull { it.inputSourceService.displayId == screen.mediaSinkService.displayId && hasTouch(it) }
                ?: inputs.firstOrNull(::hasTouch) ?: inputs.firstOrNull())?.let { service ->
                inputChannel = InputChannel(service, object : InputChannel.Listener {
                    override fun onInput(report: InputReport) { if (!stopped) receiveInput(report) }
                }).also(out::add)
            }
            services.firstOrNull { it.hasMediaSinkService() && it.mediaSinkService.hasAudioType() &&
                it.mediaSinkService.audioType == AudioStreamType.AUDIO_STREAM_MEDIA && it.mediaSinkService.availableType == MediaCodecType.MEDIA_CODEC_AUDIO_PCM }?.let { service ->
                audio = AudioChannel(service).also(out::add)
            }
            services.firstOrNull { it.hasMediaPlaybackService() }?.let { playback = MediaPlaybackChannel(it).also(out::add) }
            log.i("services=${services.size}; touch=${inputChannel != null}; mediaAudio=${audio != null}; metadata=${playback != null}")
            return out
        }
        override fun onAudioFocus(session: AapSession, notification: AudioFocusNotification) {
            // Guidance-only gain does not grant media playback. Never override phone call suspension.
            audioGranted = notification.focusState in setOf(AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN,
                AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, AudioFocusStateType.AUDIO_FOCUS_STATE_GAIN_MEDIA_ONLY)
            log.i("audio focus=${notification.focusState.number}")
        }
        override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
            val causes=generateSequence(error){it.cause}.take(3).joinToString(" / "){"${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}"}
            SessionDiagnostics.event("AA close details; phase=${session.closedDuring}; peerRejected=${session.certificateRejected}; pinRefused=${session.pinRefusal!=null}; causes=$causes")
            disconnect("AA ${session.closedDuring}: $reason")
        }
    }

    private fun receiveInput(report: InputReport) {
        if (report.hasTouchEvent()) {
            val t = report.touchEvent
            val points = t.pointerDataList
            if (points.isNotEmpty()) {
                if (t.action.number == 0) pointerIds.clear()
                for (p in points) if (p.pointerId !in pointerIds && pointerIds.size < 2) pointerIds.add(p.pointerId)
                val ordered = pointerIds.mapNotNull { id -> points.firstOrNull { it.pointerId == id } }
                if (ordered.isNotEmpty()) {
                    val panel = inputChannel?.touchScreen
                    val inputWidth = panel?.width?.takeIf { it > 0 } ?: width
                    val inputHeight = panel?.height?.takeIf { it > 0 } ?: height
                    fun x(i: Int) = (ordered[i].x.toLong() * width / inputWidth).toInt().coerceIn(0, width - 1)
                    fun y(i: Int) = (ordered[i].y.toLong() * height / inputHeight).toInt().coerceIn(0, height - 1)
                    val changedId = points.getOrNull(t.actionIndex)?.pointerId
                    val index = ordered.indexOfFirst { it.pointerId == changedId }.coerceAtLeast(0)
                    listener?.onTouch(HuLink.TouchEvent(t.action.number, index, x(0), y(0),
                        if (ordered.size > 1) x(1) else null, if (ordered.size > 1) y(1) else null))
                    if (t.action.number == 6) pointerIds.remove(changedId)
                    if (t.action.number == 1) pointerIds.clear()
                }
            }
        }
        for (key in report.keyEvent.keysList) if (!key.down) {
            val mapped = when (key.keycode) {
                3 -> 30; 4 -> 14; 85 -> 15; 87 -> 31; 88 -> 32; 126 -> 15; 127 -> 16
                84, 219, 231 -> 33
                else -> null
            }
            log.i("key=${key.keycode}; mapped=$mapped")
            mapped?.let { listener?.onHardKey(it) }
        }
    }

    override fun sendConfig(csd: ByteArray) { if (!stopped) video?.sendCodecConfig(csd, 0, csd.size) }
    override fun sendVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        val channel = video ?: return
        if (stopped) return
        val buffer = ByteArray(data.size + 10)
        data.copyInto(buffer, 10)
        channel.sendFrame(buffer, 10, data.size, ptsUs, keyFrame) { }
    }
    override fun startMediaAudio(): Boolean = synchronized(gate) {
        if (stopped) return false
        val channel = audio ?: return false
        val cfg = channel.config ?: return false
        // Existing scrcpy capture produces 48 kHz stereo signed 16-bit PCM.
        if (!channel.ready || cfg.samplingRate != 48000 || cfg.numberOfChannels != 2 || cfg.numberOfBits != 16) {
            log.w("audio requires adapter PCM 48000/16/2, offered ${cfg.samplingRate}/${cfg.numberOfBits}/${cfg.numberOfChannels}")
            return false
        }
        audioWanted = true; audioSamples = 0
        if (!audioFocusRequested) {
            session?.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_GAIN)
            audioFocusRequested = true
            focusRequestedAt = SystemClock.elapsedRealtime()
        }
        channel.startPlayback()
        channel.started
    }
    override fun sendMediaPcm(pcm: ByteArray): Boolean = synchronized(gate) {
        if (stopped || !audioWanted) return false
        // Wait for explicit audio focus; missing response must not send audio over car radio/calls.
        if (!audioGranted) {
            if (SystemClock.elapsedRealtime() - focusRequestedAt > 5000) return false
            return true
        }
        val buf = ByteArray(10 + pcm.size); pcm.copyInto(buf, 10)
        val timestamp = audioSamples * 1_000_000L / 48000
        audioSamples += pcm.size / 4
        audio?.sendPcm(buf, 10, pcm.size, timestamp) ?: false
    }
    override fun stopMediaAudio() = synchronized(gate) {
        audioWanted = false
        audio?.stopPlayback()
        if (audioFocusRequested) session?.requestAudioFocus(AudioFocusRequestType.AUDIO_FOCUS_RELEASE, priority = Priority.AUDIO)
        audioFocusRequested = false; audioGranted = false
    }
    override fun sendMediaInfo(info: HuLink.MediaInfo): Boolean {
        val ch = playback ?: return false
        if (stopped) return false
        sourceName = info.source
        ch.metadata(MediaPlaybackMetadata.newBuilder().setSong(info.title).setArtist(info.artist).setAlbum(info.album)
            .setDurationSeconds(info.durationSeconds.coerceAtLeast(0)).setAlbumArt(ByteString.copyFrom(info.artwork)).build())
        return true
    }
    override fun sendMediaProgress(seconds: Int): Boolean {
        val ch = playback ?: return false
        if (stopped) return false
        ch.status(MediaPlaybackStatus.newBuilder().setMediaSource(sourceName).setPlaybackSeconds(seconds.coerceAtLeast(0))
            .setState(if (audioWanted) MediaPlaybackStatus.State.PLAYING else MediaPlaybackStatus.State.PAUSED).build())
        return true
    }

    private fun disconnect(reason: String) {
        val target = synchronized(gate) {
            if (stopped || disconnected) return
            disconnected = true
            listener
        }
        target?.onHuDisconnected(reason.take(240))
        stop()
    }
    override fun stop() {
        synchronized(gate) {
            if (stopped) return
            stopped = true
            listener = null
        }
        scope.cancel()
        // Close TCP/RFCOMM first so a blocked session writer cannot hold shutdown up.
        runCatching { transport?.close() }; transport = null
        runCatching { session?.stop() }; session = null
        video = null; audio = null; playback = null
    }

    private fun loadIdentity(): PhoneIdentity {
        val prefs = context.getSharedPreferences("aa_identity", 0)
        val alias=prefs.getString("android_alias",null)
        if(!alias.isNullOrBlank()) {
            val privateKey=android.security.KeyChain.getPrivateKey(context,alias)
                ?: error("Ключ AA недоступен. Повторно выберите сертификат Android на экране AA.")
            val chain=android.security.KeyChain.getCertificateChain(context,alias)
            check(!chain.isNullOrEmpty()) { "Цепочка сертификата AA недоступна. Повторите выбор." }
            check(privateKey.algorithm.equals("RSA",true) && chain[0].publicKey.algorithm.equals("RSA",true)) { "Для этой ветки AA требуется RSA-сертификат." }
            chain.forEach { it.checkValidity() }
            return reportIdentity(PhoneIdentity(chain[0],privateKey,chain.drop(1)),AaPhoneCredentialSummary.Source.ANDROID)
        }
        val cert = prefs.getString("certificate", null)
        val key = prefs.getString("key", null)
        if (cert != null && key != null) {
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(cert, Base64.NO_WRAP).inputStream()) as X509Certificate
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(key, Base64.NO_WRAP)))
            return reportIdentity(PhoneIdentity(certificate, privateKey),AaPhoneCredentialSummary.Source.OWN)
        }
        val generated = SelfSignedCertificate.generate("CarBridge Offline")
        check(prefs.edit().putString("certificate", Base64.encodeToString(generated.certificate.encoded, Base64.NO_WRAP))
            .putString("key", Base64.encodeToString(generated.keyPair.private.encoded, Base64.NO_WRAP)).commit())
        return reportIdentity(PhoneIdentity(generated.certificate, generated.keyPair.private),AaPhoneCredentialSummary.Source.OWN)
    }

    private fun reportIdentity(identity:PhoneIdentity,source:AaPhoneCredentialSummary.Source):PhoneIdentity {
        SessionDiagnostics.event(AaPhoneCredentialSummary.format(identity.certificate,1+identity.chain.size,source))
        return identity
    }
}
