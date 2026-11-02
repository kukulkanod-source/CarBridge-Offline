package com.example.carbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadata
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Main-thread cache; decoding and JPEG compression never run in the USB/audio poll. */
internal class HuCarLifeArtwork(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "carlife-artwork").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy()).apply { allowCoreThreadTimeOut(true) }
    private var revision = 0
    private var key: String? = null
    private var jpeg = ByteArray(0)
    private var closed = false
    private var requestedAt = 0L

    fun current(packageName: String, metadata: MediaMetadata, enabled: Boolean): ByteArray {
        if (!enabled) { if (key != null) reset(); return jpeg }
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: metadata.description.iconBitmap
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).firstNotNullOfOrNull { metadata.getString(it)?.takeIf(String::isNotBlank) }
        val nextKey = listOf(packageName, metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            metadata.description.title, metadata.description.subtitle, metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            bitmap?.width, bitmap?.height, uri).joinToString("|")
        val now = android.os.SystemClock.elapsedRealtime()
        if (closed || (nextKey == key && now - requestedAt < 30000)) return jpeg
        if (nextKey != key) jpeg = ByteArray(0)
        key = nextKey
        requestedAt = now
        // Never send artwork belonging to a previous track/source.
        val token = ++revision
        if (bitmap == null && uri == null) return jpeg
        worker.execute {
            val bytes = runCatching {
                val owned = if (bitmap == null && uri != null) decode(Uri.parse(uri)) else null
                try { (bitmap ?: owned)?.let(::encode) } finally { owned?.recycle() }
            }.onFailure { SessionDiagnostics.event("CarLife artwork unavailable; error=${it.javaClass.simpleName}") }
                .getOrNull() ?: ByteArray(0)
            main.post {
                if (!closed && token == revision && key == nextKey) {
                    if (!jpeg.contentEquals(bytes)) jpeg = bytes
                    SessionDiagnostics.event("CarLife artwork prepared; bytes=${bytes.size}; format=jpeg; maxSide=256")
                }
            }
        }
        return jpeg
    }

    fun reset() { revision++; key = null; jpeg = ByteArray(0); worker.queue.clear() }
    fun close() { closed = true; reset(); worker.shutdownNow() }

    private fun decode(uri: Uri): Bitmap? {
        val bytes = when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { boundedBytes(it) }
            "https", "http" -> {
                val connection = URL(uri.toString()).openConnection() as HttpURLConnection
                connection.connectTimeout = 3000; connection.readTimeout = 3000
                try {
                    if (connection.responseCode !in 200..299 || connection.contentLengthLong > MAX_DOWNLOAD) null
                    else connection.inputStream.use { boundedBytes(it) }
                } finally { connection.disconnect() }
            }
            else -> null
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
    private fun boundedBytes(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val deadline = android.os.SystemClock.elapsedRealtime() + 6000
        while (!Thread.currentThread().isInterrupted && android.os.SystemClock.elapsedRealtime() < deadline) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size() + count > MAX_DOWNLOAD) return null
            output.write(buffer, 0, count)
        }
        return null
    }
    private fun encode(source: Bitmap): ByteArray? {
        if (source.isRecycled) return null
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            if (source.width.toLong() * source.height > 4L * 1024 * 1024) return null
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else source
        return try { encodeSoftware(software) } finally { if (software !== source) software.recycle() }
    }
    private fun encodeSoftware(source: Bitmap): ByteArray? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        for (side in listOf(256, 160, 96)) {
            val scale = minOf(1f, side.toFloat() / maxOf(source.width, source.height))
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                Canvas(image).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(source, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                for (quality in listOf(85, 65, 45)) {
                    val output = ByteArrayOutputStream()
                    if (image.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_BYTES)
                        return output.toByteArray()
                }
            } finally { image.recycle() }
        }
        return null
    }
    companion object {
        const val MAX_BYTES = 30720
        private const val MAX_DOWNLOAD = 2L * 1024 * 1024
    }
}
