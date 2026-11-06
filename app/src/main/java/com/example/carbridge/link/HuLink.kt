package com.example.carbridge.link

/**
 * ШОВ между проектом и протоколом автомобиля.
 *
 * Всё, что относится к USB-аксессуару, framing, protobuf, сессии и авторизации перед ГУ,
 * реализуется в CarLifeHuLink; авторизация — в CarLifeAuthenticator.
 * Остальной проект про протокол ничего не знает.
 *
 * Контракт:
 *  - после успешной сессии вызвать listener.onHuReady(w, h) с размером кадра, который хочет ГУ;
 *  - прислать SPS/PPS через sendConfig(), затем кадры через sendVideoFrame();
 *  - входящий тач/кнопки отдавать в listener.
 */
interface HuLink {
    fun start(listener: Listener)
    fun stop()

    fun sendConfig(csd: ByteArray)
    fun sendVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long)
    fun startMediaAudio(): Boolean = false
    fun sendMediaPcm(pcm: ByteArray): Boolean = false
    fun stopMediaAudio() { }
    data class MediaInfo(val source: String, val title: String, val artist: String, val album: String,
        val durationSeconds: Int, val mediaId: String, val artwork: ByteArray = ByteArray(0))
    fun sendMediaInfo(info: MediaInfo): Boolean = false
    fun sendMediaProgress(seconds: Int): Boolean = false
    data class NavigationInfo(val action: Int, val nextTurn: Int, val roadName: String,
        val totalDistance: Int, val remainingDistance: Int, val turnImage: ByteArray = ByteArray(0))
    fun sendNavigation(info: NavigationInfo): Boolean = false
    fun setNavigationActive(active: Boolean): Boolean = false
    val navigationEpoch: Long get() = 0L
    fun startMicrophoneProbe(): Boolean = false
    fun stopMicrophoneProbe() { }

    data class TouchEvent(
        val action: Int,
        val actionIndex: Int,
        val x: Int,
        val y: Int,
        val pointerX: Int? = null,
        val pointerY: Int? = null,
    )

    interface Listener {
        /** Сессия поднята, ГУ ждёт видео заданного размера. */
        fun onHuReady(width: Int, height: Int)

        /** Android touch action and up to two HU pointers in encoded-video coordinates. */
        fun onTouch(event: TouchEvent)

        fun onHardKey(code: Int)
        fun onAudioSuspend() { }
        fun onAudioResume() { }
        fun onDiagnosticStatus(message: String) { }

        /** ГУ потерял кадры и просит ключевой. */
        fun onRequestKeyframe()

        fun onTransportBackpressure(queuedBytes: Int) { }

        fun onHuDisconnected(reason: String)
    }
}
