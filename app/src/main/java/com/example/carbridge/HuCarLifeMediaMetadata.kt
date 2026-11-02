package com.example.carbridge

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.example.carbridge.link.HuLink

/** Small metadata messages use the CarLife command channel, independently of PCM. */
internal class HuCarLifeMediaMetadata(private val context: Context) {
    private var lastLink: HuLink? = null
    private var lastInfo: HuLink.MediaInfo? = null
    private var progressAt = 0L
    private var sentAt = 0L
    private var selected: String? = null
    private val artwork = HuCarLifeArtwork(context)
    fun update(link: HuLink?, artworkEnabled: Boolean = false) {
        if (link !== lastLink) {
            if (lastInfo != null) lastLink?.sendMediaInfo(HuLink.MediaInfo("CarBridge", "", "", "", 0, ""))
            lastLink = link; lastInfo = null; progressAt = 0L; sentAt = 0L; selected = null; artwork.reset()
        }
        if (link == null) return
        val sessions = runCatching { context.getSystemService(MediaSessionManager::class.java).getActiveSessions(
            ComponentName(context, HuMediaNotificationListener::class.java)) }.getOrDefault(emptyList())
        fun MediaController.playing() = playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
        val player = sessions.firstOrNull { it.playing() && it.packageName == selected }
            ?: sessions.firstOrNull { it.playing() }
            ?: sessions.firstOrNull { it.packageName == selected }
        if (player == null) {
            artwork.reset()
            if (lastInfo != null && link.sendMediaInfo(HuLink.MediaInfo("CarBridge", "", "", "", 0, ""))) lastInfo = null
            return
        }
        selected = player.packageName
        val metadata = player.metadata ?: run { clearMetadata(link); return }
        val title = (metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata.description.title?.toString()).orEmpty()
        if (title.isBlank()) { clearMetadata(link); return }
        val source = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(player.packageName,0)).toString() }.getOrDefault(player.packageName)
        val info = HuLink.MediaInfo(source, title, metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(), artwork.current(player.packageName, metadata, artworkEnabled))
        val now = SystemClock.elapsedRealtime()
        if (info != lastInfo || now-sentAt >= 30000) {
            if (link.sendMediaInfo(info)) {
                lastInfo=info; sentAt=now
                SessionDiagnostics.event("CarLife media metadata sent; source=${player.packageName}; titlePresent=true; durationSeconds=${info.durationSeconds}; artworkBytes=${info.artwork.size}; queued=true")
            }
        }
        if (now-progressAt >= 5000) {
            val state=player.playbackState ?: return
            val elapsed=if(state.state==PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime>0) (now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed else 0f
            var seconds=((state.position.coerceAtLeast(0)+elapsed.toLong())/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if(info.durationSeconds>0) seconds=seconds.coerceAtMost(info.durationSeconds)
            if(link.sendMediaProgress(seconds)) progressAt=now
        }
    }
    private fun clearMetadata(link: HuLink) {
        artwork.reset()
        if (lastInfo != null && link.sendMediaInfo(HuLink.MediaInfo("CarBridge", "", "", "", 0, ""))) lastInfo = null
    }
    fun close() { artwork.close() }

}
