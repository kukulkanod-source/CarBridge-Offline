package com.example.carbridge

import android.app.Notification
import android.content.Context
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Live controls belong to this notification's session, not a guessed global player. */
internal class HuShadeMediaCard(context: Context, private val item: StatusBarNotification,
    private val density: Float, private val controller: MediaController, open: () -> Unit
) : LinearLayout(context) {
    private val cover = ImageView(context)
    private val title = text(17f)
    private val artist = text(14f)
    private val source = text(12f)
    private val previous = control("|◀", "Предыдущий трек") { controller.transportControls.skipToPrevious() }
    private val play = control("▶", "Воспроизведение / пауза") {
        if (isPlaying()) controller.transportControls.pause() else controller.transportControls.play()
    }
    private val next = control("▶|", "Следующий трек") { controller.transportControls.skipToNext() }
    private var dead = false
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onPlaybackStateChanged(state: PlaybackState?) = render()
        override fun onSessionDestroyed() { dead = true; render() }
    }
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(8), px(10), px(8), px(10))
        setBackgroundColor(Color.rgb(30, 43, 59))
        contentDescription = "Музыкальный плеер"
        setOnClickListener { open() }
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(cover, LayoutParams(px(80), px(80)).apply { rightMargin = px(12) })
        val info = LinearLayout(context).apply { orientation = VERTICAL }
        info.addView(source); info.addView(title); info.addView(artist)
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        listOf(previous, play, next).forEach { controls.addView(it, LayoutParams(px(62), px(40))) }
        info.addView(controls)
        addView(info, LayoutParams(0, -2, 1f))
        render()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { controller.registerCallback(callback, Handler(Looper.getMainLooper())) }
        render()
    }
    override fun onDetachedFromWindow() {
        runCatching { controller.unregisterCallback(callback) }
        super.onDetachedFromWindow()
    }
    private fun isPlaying() = controller.playbackState?.state in setOf(PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
    private fun render() {
        val metadata = controller.metadata
        val n = item.notification
        val appName = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(item.packageName, 0)).toString() }.getOrDefault(item.packageName)
        source.text = if (dead) "$appName · источник недоступен" else appName
        title.text = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE) ?: n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "Музыка"
        artist.text = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) cover.setImageBitmap(art)
        else cover.setImageDrawable(runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()
            ?: runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull())
        play.text = if (isPlaying()) "Ⅱ" else "▶"
        val actions = controller.playbackState?.actions ?: 0L
        previous.isEnabled = !dead && actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        next.isEnabled = !dead && actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        play.isEnabled = !dead && actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
        listOf(previous, play, next).forEach { it.alpha = if (it.isEnabled) 1f else 0.35f }
    }
    private fun control(value: String, description: String, action: () -> Unit) = Button(context).apply {
        text = value; contentDescription = description
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 23f * density)
        setTextColor(Color.WHITE); setBackgroundColor(Color.TRANSPARENT)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0; setPadding(0, 0, 0, 0)
        setOnClickListener { runCatching(action) }
    }
    private fun text(size: Float) = TextView(context).apply {
        setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun px(value: Int) = (value * density).roundToInt()
    companion object {
        fun controller(context: Context, item: StatusBarNotification): MediaController? = runCatching {
            val token = androidx.core.os.BundleCompat.getParcelable(item.notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            token?.let { MediaController(context, it) }?.takeIf { it.packageName == item.packageName }
        }.getOrNull()
    }
}
