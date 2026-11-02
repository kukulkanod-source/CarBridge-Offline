package com.example.carbridge.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * VirtualDisplay -> H.264 (Surface input).
 * Дисплей даёт либо MediaProjection (зеркало экрана), либо displayFactory (собственный дисплей).
 */
class ScreenSource(
    private val projection: MediaProjection?,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int,
    private val fps: Int = 30,
    private val bitrate: Int = 4_000_000,
    private val listener: Listener,
    private val displayFactory: ((Surface) -> VirtualDisplay?)? = null
) {
    interface Listener {
        fun onConfig(csd: ByteArray)
        fun onFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long)
    }

    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    val outputDisplay: android.view.Display? get() = display?.display
    @Volatile var outputFrames: Long = 0
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stop() }
    }

    fun start() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // Статичная картинка: повторять кадр, чтобы ГУ не решил, что связь потеряна.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
            // Экран телефона обновляется на 60-120 Гц: ограничиваем частоту кадров ДО энкодера.
            // Отбрасывать P-кадры после энкодера нельзя, цепочка ссылок порвётся.
            setFloat("max-fps-to-encoder", fps.toFloat())
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec = c
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        inputSurface = surface
        c.start()

        val factory = displayFactory
        if (factory != null) {
            display = factory(surface)
                ?: throw IllegalStateException("не удалось создать виртуальный дисплей")
        } else {
            val p = projection ?: throw IllegalStateException("нет ни проекции, ни фабрики дисплея")
            // Android 14+: callback нужно зарегистрировать до createVirtualDisplay.
            p.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            display = p.createVirtualDisplay(
                "carbridge", width, height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )
        }

        running = true
        thread = Thread({ drain(c) }, "enc-drain").also { it.start() }
        Log.i(TAG, "started ${width}x$height @${fps}fps ${bitrate / 1000}kbps")
    }

    fun requestKeyframe() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (e: Exception) { Log.w(TAG, "requestKeyframe: $e") }
    }

    private var statFrames = 0
    private var statBytes = 0L
    private var statStart = SystemClock.elapsedRealtime()

    private fun logStats(size: Int) {
        statFrames++; statBytes += size
        val now = SystemClock.elapsedRealtime()
        val ms = now - statStart
        if (ms >= 5000) {
            Log.i(TAG, "fps=%.1f bitrate=%d kbps".format(statFrames * 1000f / ms, statBytes * 8 / ms))
            statFrames = 0; statBytes = 0; statStart = now
        }
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var firstFrame = true
        while (running) {
            val i = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (i < 0) continue
            val buf = c.getOutputBuffer(i)
            if (buf != null && info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size).also { buf.get(it) }
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    listener.onConfig(bytes)
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    outputFrames++
                    if (firstFrame) {
                        Log.i(TAG, "первый видеокадр: ${bytes.size} байт key=$key")
                        firstFrame = false
                    }
                    logStats(bytes.size)
                    listener.onFrame(bytes, key, info.presentationTimeUs)
                }
            }
            c.releaseOutputBuffer(i, false)
        }
    }

    fun stop() {
        if (!running && codec == null) return
        running = false
        runCatching { thread?.join(500) }
        runCatching { display?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        display = null; codec = null; thread = null; inputSurface = null
        Log.i(TAG, "stopped")
    }

    private companion object { const val TAG = "CarBridge/Screen" }
}
