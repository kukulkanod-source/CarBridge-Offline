package com.example.carbridge.link

import android.util.Log

/**
 * Заглушка для отладки захвата/кодирования без машины.
 * Заменить реальной реализацией HuLink (USB AOA + CarLife-сессия).
 */
class StubHuLink(
    private val width: Int = 1280,
    private val height: Int = 720
) : HuLink {

    private var frames = 0

    override fun start(listener: HuLink.Listener) {
        Log.i(TAG, "StubHuLink.start -> имитирую готовность ГУ ${width}x$height")
        listener.onHuReady(width, height)
    }

    override fun stop() { Log.i(TAG, "StubHuLink.stop, кадров: $frames") }

    override fun sendConfig(csd: ByteArray) { Log.i(TAG, "config ${csd.size} байт") }

    override fun sendVideoFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        frames++
        if (keyFrame || frames % 120 == 0) {
            Log.i(TAG, "frame #$frames ${data.size} байт key=$keyFrame")
        }
    }

    private companion object { const val TAG = "CarBridge/Stub" }
}
