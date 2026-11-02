package com.example.carbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Durable snapshot for the generic HU media widget, including its last cover. */
internal object HuMediaStateStore {
    data class Snapshot(
        val packageName: String? = null,
        val title: String = "",
        val artist: String = "",
        val source: String = "",
        val wasPlaying: Boolean = false,
        val hasArtwork: Boolean = false,
    )

    private const val PREFS = "carbridge_hu_media_state"
    private const val COVER = "last-hu-media-cover.jpg"
    private val fileLock = Any()

    fun read(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            packageName = prefs.getString("package", null),
            title = prefs.getString("title", "").orEmpty(),
            artist = prefs.getString("artist", "").orEmpty(),
            source = prefs.getString("source", "").orEmpty(),
            wasPlaying = prefs.getBoolean("playing", false),
            hasArtwork = prefs.getBoolean("has_artwork", false),
        )
    }

    /** Metadata writes are asynchronous during playback; close() asks for a final durable flush. */
    fun save(
        context: Context,
        snapshot: Snapshot,
        artwork: Bitmap?,
        artworkChanged: Boolean,
        flush: Boolean,
    ) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("package", snapshot.packageName)
            .putString("title", snapshot.title)
            .putString("artist", snapshot.artist)
            .putString("source", snapshot.source)
            .putBoolean("playing", snapshot.wasPlaying)
            .putBoolean("has_artwork", if (artworkChanged) artwork != null else snapshot.hasArtwork)
        if (flush) editor.commit() else editor.apply()

        if (artworkChanged) {
            writeArtwork(app, artwork)
        }
    }

    fun readArtwork(context: Context, snapshot: Snapshot): Bitmap? {
        if (!snapshot.hasArtwork) return null
        val file = File(context.applicationContext.filesDir, COVER)
        return synchronized(fileLock) { runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() }
    }

    private fun writeArtwork(context: Context, artwork: Bitmap?) {
        synchronized(fileLock) {
            val file = File(context.filesDir, COVER)
            if (artwork == null || artwork.isRecycled) {
                runCatching { file.delete() }
                return
            }
            val maxSide = maxOf(artwork.width, artwork.height)
            val stored = if (maxSide > 512) {
                val factor = 512f / maxSide
                runCatching {
                    Bitmap.createScaledBitmap(artwork,
                        (artwork.width * factor).toInt().coerceAtLeast(1),
                        (artwork.height * factor).toInt().coerceAtLeast(1), true)
                }.getOrNull() ?: artwork
            } else artwork
            val temp = File(context.filesDir, "$COVER.tmp")
            try {
                FileOutputStream(temp).use { output ->
                    check(stored.compress(Bitmap.CompressFormat.JPEG, 88, output))
                    output.fd.sync()
                }
                if (!temp.renameTo(file)) {
                    file.delete()
                    check(temp.renameTo(file))
                }
                Unit
            } catch (_: Exception) {
                runCatching { temp.delete() }
                Unit
            } finally {
                if (stored !== artwork) runCatching { stored.recycle() }
                Unit
            }
        }
    }
}
