package com.example.carbridge

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Music drawer; reports visibility and side so the application display can reserve space. */
class HuMediaOverlay(
    context: Context,
    private val onDismissPanel: () -> Unit,
    private val onOpenMediaSource: (String) -> Unit = {},
    private val onLayoutChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
    private val onNavigationPlacementChanged: (Boolean, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val artworkLoader = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carbridge-media-artwork").apply { isDaemon = true }
    }
    private var manager: WindowManager? = null
    private var windowContext: Context? = null
    private var root: FrameLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var displayId = -1
    private var controller: MediaController? = null
    private var isVisible = false
    private var handleDownX = 0f
    private var onRight = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ON_RIGHT, true)
    private var artwork: ImageView? = null
    private var title: TextView? = null
    private var artist: TextView? = null
    private var source: TextView? = null
    private var playPause: Button? = null
    private var previous: Button? = null
    private var next: Button? = null
    private var renderedPackage: String? = null
    private var cachedState = HuMediaStateStore.read(context)
    private var cachedArtwork: Bitmap? = null
    private var lastArtwork: Bitmap? = null
    private var artworkKey: String? = null
    private var persistedMetadataKey: String? = null
    private var artworkRequestGeneration = 0
    private var receiverRegistered = false
    private var designDensity = 200 / 160f
    private var navigationDensity = 1f
    private var normalHeight = 0
    private var normalControlSize = 0
    private var contentLayout: LinearLayout? = null
    private var navigationVisible = false
    private var stackedForNavigation = false
    private var sizeAnimator: ValueAnimator? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderCurrentSession()
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderCurrentSession()
        override fun onSessionDestroyed() = refreshSession()
    }

    private val sessionChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshSession()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (root == null) return
            refreshSession()
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /** Called on the main thread once scrcpy has created the phone-side HU display. */
    fun show(id: Int, interfaceDpi: Int = 200, outputWidth: Int = 0, resizeForMusic: Boolean = true, reserveDock: Boolean = true): Boolean {
        if (id <= 0 || !Settings.canDrawOverlays(context)) return false
        if (displayId == id && root != null) return true
        close()
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            ?.takeIf { it.isValid } ?: return false
        return try {
            val window = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = window.getSystemService(WindowManager::class.java)
            val metrics = window.resources.displayMetrics
            val logicalWidth = window.resources.displayMetrics.widthPixels
            val outputScale = if (outputWidth > 0) logicalWidth.toFloat() / outputWidth else 1f
            val density = interfaceDpi.coerceIn(80, 320) / 160f * outputScale
            designDensity = density
            val dockWidth = HuDockDimensions.musicWidth(metrics.widthPixels, resizeForMusic)
            val navigationDensity = density.coerceAtMost(metrics.widthPixels / 700f)
            this.navigationDensity = navigationDensity
            val dockHeight = (metrics.heightPixels - (if (reserveDock) (HuDockDimensions.HEIGHT_DP * navigationDensity).roundToInt() else 0)).coerceAtLeast(1)
            normalHeight = dockHeight
            val pad = (14 * density).roundToInt()
            val panel = FrameLayout(window).apply {
                background = roundedBackground(Color.rgb(23, 30, 38), HuWindowCorners.radiusPx(navigationDensity))
                clipToOutline = true
                elevation = 10 * density
                visibility = View.GONE
                isClickable = true
            }

            val art = ImageView(window).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(android.R.drawable.ic_media_play)
                clipToOutline = true
            }
            panel.addView(art, FrameLayout.LayoutParams(match, match))
            val scrim = View(window).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.argb(24, 0, 0, 0), Color.argb(210, 0, 0, 0)),
                )
            }
            panel.addView(scrim, FrameLayout.LayoutParams(match, match))

            val content = LinearLayout(window).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, (30 * density).roundToInt())
            }
            contentLayout = content
            panel.addView(content, FrameLayout.LayoutParams(match, match))

            val header = LinearLayout(window).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val heading = text(window, "МУЗЫКА", 13f, Color.LTGRAY).apply {
                letterSpacing = 0.08f
            }
            header.addView(heading, LinearLayout.LayoutParams(0, wrap, 1f))
            val swap = button(window, "↔", 21f, Color.WHITE) { toggleSide() }
            swap.contentDescription = "Сменить сторону музыкальной панели"
            header.addView(swap, LinearLayout.LayoutParams(buttonSize(density), buttonSize(density)))
            val dismiss = button(window, "×", 24f, Color.WHITE) { onDismissPanel() }
            dismiss.contentDescription = "Скрыть музыкальную панель"
            header.addView(dismiss, LinearLayout.LayoutParams(buttonSize(density), buttonSize(density)))
            content.addView(header, LinearLayout.LayoutParams(match, buttonSize(density)))

            val metadata = LinearLayout(window).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            val titleView = text(window, "Запустите музыку на телефоне", 16f, Color.WHITE).apply {
                maxLines = 2
                gravity = Gravity.CENTER
            }
            metadata.addView(titleView, LinearLayout.LayoutParams(match, wrap))
            val artistView = text(window, "", 13f, Color.LTGRAY).apply {
                maxLines = 1
                gravity = Gravity.CENTER
            }
            metadata.addView(artistView, LinearLayout.LayoutParams(match, wrap))
            val sourceView = text(window, "", 11f, Color.GRAY).apply {
                maxLines = 1
                gravity = Gravity.CENTER
            }
            metadata.addView(sourceView, LinearLayout.LayoutParams(match, wrap))
            content.addView(metadata, LinearLayout.LayoutParams(match, 0, 1f))

            val transportRow = LinearLayout(window).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            val controlSize = (dockWidth / 4f).roundToInt().coerceIn(
                (44 * density).roundToInt(), (70 * density).roundToInt())
            normalControlSize = controlSize
            val prev = button(window, "|◀", 20f, Color.WHITE) { transport { it.skipToPrevious() } }
            val play = button(window, "▶", 24f, Color.WHITE) {
                val active = controller
                if (active == null) {
                    cachedState.packageName?.let(onOpenMediaSource)
                } else {
                    runCatching {
                        if (active.playbackState?.state == PlaybackState.STATE_PLAYING) active.transportControls.pause()
                        else active.transportControls.play()
                    }
                    renderCurrentSession()
                }
            }
            val following = button(window, "▶|", 20f, Color.WHITE) { transport { it.skipToNext() } }
            transportRow.addView(prev, LinearLayout.LayoutParams(controlSize, controlSize))
            transportRow.addView(play, LinearLayout.LayoutParams(controlSize, controlSize))
            transportRow.addView(following, LinearLayout.LayoutParams(controlSize, controlSize))
            content.addView(transportRow, LinearLayout.LayoutParams(match, controlSize).apply {
                topMargin = (10 * density).roundToInt()
            })

            val layout = WindowManager.LayoutParams(
                dockWidth, dockHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
                title = "CarBridge music drawer"
            }
            wm.addView(panel, layout)
            manager = wm
            windowContext = window
            root = panel
            windowParams = layout
            displayId = id
            val handleWidth = (8 * density).roundToInt().coerceAtLeast(1)
            val handleHeight = (52 * density).roundToInt().coerceAtLeast(1)
            val edgeHandle = View(window).apply {
                contentDescription = "Открыть музыкальную панель; проведите пальцем от края экрана"
                background = roundedBackground(Color.argb(210, 65, 210, 125), (4 * density).roundToInt())
                elevation = 12 * density
                setOnClickListener { toggleVisibility() }
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            handleDownX = event.rawX
                            true
                        }
                        MotionEvent.ACTION_MOVE -> true
                        MotionEvent.ACTION_UP -> {
                            val delta = event.rawX - handleDownX
                            if (abs(delta) >= (28 * density)) {
                                val swipedIntoScreen = if (onRight) delta < 0 else delta > 0
                                setVisible(swipedIntoScreen)
                            } else {
                                v.performClick()
                            }
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> true
                        else -> true
                    }
                }
            }
            val edgeLayout = WindowManager.LayoutParams(
                handleWidth, handleHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or if (onRight) Gravity.END else Gravity.START
                title = "CarBridge music drawer handle"
            }
            runCatching { wm.addView(edgeHandle, edgeLayout) }
                .onSuccess { handle = edgeHandle; handleParams = edgeLayout }
                .onFailure { SessionDiagnostics.event("HU music drawer handle unavailable: ${it.javaClass.simpleName}") }
            artwork = art
            title = titleView
            artist = artistView
            source = sourceView
            playPause = play
            previous = prev
            next = following
            cachedState = HuMediaStateStore.read(context)
            cachedArtwork = HuMediaStateStore.readArtwork(context, cachedState)
            cachedArtwork?.let(art::setImageBitmap)
            lastArtwork = cachedArtwork
            applyNavigationLayout(animate = false)
            applyVisibility(visible = false, animate = false)
            registerSessionReceiver()
            main.post(poll)
            refreshSession()
            true
        } catch (_: Exception) {
            close()
            false
        }
    }

    fun isVisible(): Boolean = isVisible

    fun isAttached(): Boolean = root != null

    fun isOnRight(): Boolean = onRight

    fun setNavigationVisible(visible: Boolean) {
        val changed = navigationVisible != visible
        navigationVisible = visible
        applyNavigationLayout(animate = changed)
    }

    private fun applyNavigationLayout(animate: Boolean) {
        val panel = root ?: return
        val params = windowParams ?: return
        val wm = manager ?: return
        val metrics = windowContext?.resources?.displayMetrics ?: return
        val geometry = HuTopCardLayout(metrics.widthPixels,navigationDensity)
        val compact = navigationVisible && isVisible
        val roomForTwo = ((normalHeight - geometry.inset * 2 - geometry.gap) / 2).coerceAtLeast(1)
        val cardHeight = minOf(geometry.navigationHeight,
            if(compact) roomForTwo else (normalHeight - geometry.inset * 2).coerceAtLeast(1))
        val targetHeight = if(compact) cardHeight else normalHeight
        val targetY = if(compact) geometry.inset + cardHeight + geometry.gap else 0
        if(stackedForNavigation!=compact) {
            stackedForNavigation=compact
            SessionDiagnostics.event("HU music navigation layout; compact=$compact; cardHeight=$cardHeight; right=$onRight")
        }
        params.gravity = Gravity.TOP or if(onRight) Gravity.END else Gravity.START
        params.x = if(compact) geometry.inset else 0
        onNavigationPlacementChanged(onRight,cardHeight,geometry.inset)
        styleContent(compact,targetHeight)
        sizeAnimator?.cancel(); sizeAnimator=null
        if(animate && isVisible && panel.visibility==View.VISIBLE && ValueAnimator.areAnimatorsEnabled() &&
            (params.height!=targetHeight || params.y!=targetY)) {
            val fromHeight=params.height
            val fromY=params.y
            sizeAnimator=ValueAnimator.ofFloat(0f,1f).apply {
                duration=240L
                addUpdateListener { animation ->
                    if(root!==panel) return@addUpdateListener
                    val t=animation.animatedValue as Float
                    params.height=(fromHeight+(targetHeight-fromHeight)*t).roundToInt()
                    params.y=(fromY+(targetY-fromY)*t).roundToInt()
                    runCatching { wm.updateViewLayout(panel,params) }
                }
                start()
            }
        } else {
            params.height=targetHeight; params.y=targetY
            runCatching { wm.updateViewLayout(panel,params) }
        }
    }

    private fun styleContent(compact: Boolean, panelHeight: Int) {
        val content=contentLayout ?: return
        val header=content.getChildAt(0) as? LinearLayout ?: return
        val controls=content.getChildAt(2) as? LinearLayout ?: return
        val d=if(compact) navigationDensity else designDensity
        val pad=((if(compact) 8 else 14)*d).roundToInt()
        content.setPadding(pad,pad,pad,((if(compact) 8 else 30)*d).roundToInt())
        val headerSize=if(compact) minOf((32*d).roundToInt(),panelHeight/5).coerceAtLeast(1) else buttonSize(d)
        header.layoutParams=(header.layoutParams as LinearLayout.LayoutParams).apply { height=headerSize }
        for(i in 1 until header.childCount) header.getChildAt(i).let { child ->
            child.layoutParams=(child.layoutParams as LinearLayout.LayoutParams).apply { width=headerSize; height=headerSize }
            (child as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(compact) 20f else if(i==1) 21f else 24f)*d)
        }
        (header.getChildAt(0) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(compact) 11f else 13f)*d)
        val controlSize=if(compact) minOf((48*d).roundToInt(),panelHeight/4,
            ((windowParams?.width ?: 1)-pad*2)/3).coerceAtLeast(1) else normalControlSize
        controls.layoutParams=(controls.layoutParams as LinearLayout.LayoutParams).apply {
            height=controlSize; topMargin=((if(compact) 4 else 10)*d).roundToInt()
        }
        for(i in 0 until controls.childCount) controls.getChildAt(i).let { child ->
            child.layoutParams=(child.layoutParams as LinearLayout.LayoutParams).apply { width=controlSize; height=controlSize }
            (child as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(i==1) 24f else 20f)*d)
        }
        title?.setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(compact) 14f else 16f)*d)
        artist?.setTextSize(TypedValue.COMPLEX_UNIT_PX,(if(compact) 11f else 13f)*d)
        source?.visibility=if(compact) View.GONE else View.VISIBLE
    }

    fun toggleVisibility(): Boolean {
        if (root == null) return isVisible
        isVisible = !isVisible
        if(isVisible) applyNavigationLayout(animate = false)
        applyVisibility(isVisible, animate = true)
        if(!isVisible && root?.visibility!=View.VISIBLE) applyNavigationLayout(animate = false)
        onLayoutChanged(isVisible, onRight)
        return isVisible
    }

    fun setVisible(visible: Boolean) {
        if (root == null) return
        isVisible = visible
        if(visible) applyNavigationLayout(animate = false)
        applyVisibility(visible, animate = true)
        if(!visible && root?.visibility!=View.VISIBLE) applyNavigationLayout(animate = false)
        onLayoutChanged(isVisible, onRight)
    }

    private fun applyVisibility(visible: Boolean, animate: Boolean) {
        val panel = root ?: return
        val layout = windowParams ?: return
        val wm = manager ?: return
        panel.animate().cancel()
        layout.flags = BASE_PANEL_FLAGS or if (visible) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(panel, layout) }
        val hiddenX = if (onRight) layout.width.toFloat() else -layout.width.toFloat()
        if (visible) {
            panel.visibility = View.VISIBLE
            if (animate) {
                panel.translationX = hiddenX
                panel.animate().translationX(0f).setDuration(190L).start()
            } else {
                panel.translationX = 0f
            }
        } else if (animate && panel.visibility == View.VISIBLE) {
            panel.animate().translationX(hiddenX).setDuration(170L).withEndAction {
                if (!isVisible) {
                    panel.visibility = View.GONE
                    applyNavigationLayout(animate = false)
                }
            }.start()
        } else {
            panel.translationX = hiddenX
            panel.visibility = View.GONE
        }
        handle?.contentDescription = if (visible) "Скрыть музыкальную панель"
            else "Открыть музыкальную панель; проведите пальцем от края экрана"
    }

    fun close() {
        sizeAnimator?.cancel(); sizeAnimator=null
        persistCurrent(flush = true)
        main.removeCallbacks(poll)
        if (receiverRegistered) runCatching { context.unregisterReceiver(sessionChangedReceiver) }
        receiverRegistered = false
        controller?.unregisterCallback(callback)
        controller = null
        val oldRoot = root
        val oldHandle = handle
        val oldManager = manager
        root = null
        manager = null
        windowContext = null
        windowParams = null
        handle = null
        handleParams = null
        displayId = -1
        isVisible = false
        navigationVisible = false
        stackedForNavigation = false
        normalHeight = 0; normalControlSize = 0; contentLayout = null
        artwork = null
        title = null
        artist = null
        source = null
        playPause = null
        previous = null
        next = null
        renderedPackage = null
        cachedArtwork = null
        lastArtwork = null
        artworkKey = null
        persistedMetadataKey = null
        artworkRequestGeneration++
        if (oldHandle != null) runCatching { oldManager?.removeViewImmediate(oldHandle) }
        if (oldRoot != null) runCatching { oldManager?.removeViewImmediate(oldRoot) }
    }

    private fun toggleSide() {
        onRight = !onRight
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON_RIGHT, onRight).apply()
        val layout = windowParams ?: return
        sizeAnimator?.cancel(); sizeAnimator=null
        layout.gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
        manager?.let { wm ->
            root?.let { panel ->
                panel.animate().cancel()
                panel.translationX = if (isVisible) 0f else if (onRight) layout.width.toFloat() else -layout.width.toFloat()
                runCatching { wm.updateViewLayout(panel, layout) }
            }
            handle?.let handleUpdate@{ edge ->
                val edgeLayout = handleParams ?: return@handleUpdate
                edgeLayout.gravity = Gravity.CENTER_VERTICAL or if (onRight) Gravity.END else Gravity.START
                runCatching { wm.updateViewLayout(edge, edgeLayout) }
            }
        }
        applyNavigationLayout(animate = false)
        onLayoutChanged(isVisible, onRight)
    }

    /** Commands are CarLife hard-key mappings, not Android key codes. */
    fun dispatchWheelMedia(code: Int) {
        refreshSession()
        val target = controller
        if (target == null) {
            SessionDiagnostics.event("HU wheel media=$code ignored: no accessible media session; listener=${HuMediaNotificationListener.connected}")
            return
        }
        runCatching {
            when (code) {
                16 -> target.transportControls.skipToNext()
                15 -> target.transportControls.skipToPrevious()
                31 -> target.transportControls.play()
                32 -> target.transportControls.pause()
            }
            SessionDiagnostics.event("HU wheel media=$code dispatched; package=${target.packageName}; actions=${target.playbackState?.actions}")
        }.onFailure { SessionDiagnostics.event("HU wheel media failed: ${it.javaClass.simpleName}") }
    }

    private fun registerSessionReceiver() {
        val filter = IntentFilter(HuMediaNotificationListener.ACTION_MEDIA_SESSIONS_CHANGED)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(sessionChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(sessionChangedReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun refreshSession() {
        val activeSessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, HuMediaNotificationListener::class.java))
                .filter { it.metadata != null || it.playbackState != null }
        }.getOrDefault(emptyList())
        val playingSessions = activeSessions.filter {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val preferred = cachedState.packageName?.let { pkg -> activeSessions.firstOrNull { it.packageName == pkg } }
        val current = controller?.sessionToken?.let { token -> activeSessions.firstOrNull { it.sessionToken == token } }
        val chosen = current?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: preferred?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: playingSessions.firstOrNull()
            ?: preferred
            ?: current
            ?: activeSessions.firstOrNull()
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, main)
            SessionDiagnostics.event("HU media session selected: ${chosen?.packageName ?: "none"}")
        }
        renderCurrentSession()
    }

    private fun renderCurrentSession() {
        val active = controller
        val metadata = active?.metadata
        val state = active?.playbackState
        val hasAccess = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.mapNotNull { ComponentName.unflattenFromString(it) }
            ?.contains(ComponentName(context, HuMediaNotificationListener::class.java)) == true
        val activePackage = active?.packageName
        val titleValue = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artistValue = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val sourceValue = activePackage?.let(::appLabel)
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val image = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        val imageUri = metadata?.let(::mediaArtworkUri)
        val previousState = cachedState

        if (active != null) {
            val titleText = titleValue?.takeIf { it.isNotBlank() }
                ?: cachedState.title.takeIf { cachedState.packageName == activePackage && it.isNotBlank() }
                ?: "Воспроизведение"
            val artistText = artistValue?.takeIf { it.isNotBlank() }
                ?: cachedState.artist.takeIf { cachedState.packageName == activePackage }.orEmpty()
            val sourceText = sourceValue.orEmpty()
            val metadataKey = listOf(activePackage, titleText, artistText, sourceText, playing).joinToString("|")
            val packageChanged = activePackage != previousState.packageName
            val clearSavedArtwork = packageChanged
            if (metadataKey != persistedMetadataKey) {
                if (clearSavedArtwork) {
                    cachedArtwork = null
                    lastArtwork = null
                    artwork?.setImageResource(android.R.drawable.ic_media_play)
                }
                cachedState = HuMediaStateStore.Snapshot(
                    packageName = activePackage,
                    title = titleText,
                    artist = artistText,
                    source = sourceText,
                    wasPlaying = playing,
                    hasArtwork = if (clearSavedArtwork) false else previousState.hasArtwork,
                )
                persistedMetadataKey = metadataKey
                HuMediaStateStore.save(context, cachedState, null, artworkChanged = clearSavedArtwork,
                    flush = false)
            }
            if (activePackage != renderedPackage) {
                renderedPackage = activePackage
                source?.text = sourceText
            }
            title?.text = titleText
            artist?.text = artistText
        } else {
            title?.text = when {
                !hasAccess -> "Разрешите доступ к уведомлениям"
                cachedState.title.isNotBlank() -> cachedState.title
                else -> "Запустите музыку на телефоне"
            }
            artist?.text = cachedState.artist
            source?.text = cachedState.source.ifBlank { cachedState.packageName?.let(::appLabel).orEmpty() }
        }

        val nextArtworkKey = if (active == null) {
            "cached|${cachedState.packageName}|${cachedState.hasArtwork}"
        } else {
            val artworkIdentity = when {
                image != null -> "bitmap:${System.identityHashCode(image)}:${image.width}x${image.height}"
                imageUri != null -> "uri:$imageUri"
                else -> "none"
            }
            "${active.packageName}|${titleValue.orEmpty()}|${artistValue.orEmpty()}|$artworkIdentity"
        }
        if (nextArtworkKey != artworkKey) {
            artworkKey = nextArtworkKey
            when {
                image != null -> {
                    val artworkChanged = image !== cachedArtwork || !cachedState.hasArtwork
                    cachedArtwork = image
                    showArtwork(image)
                    cachedState = cachedState.copy(hasArtwork = true)
                    if (artworkChanged) {
                        HuMediaStateStore.save(context, cachedState, image,
                            artworkChanged = true, flush = false)
                    }
                }
                imageUri != null -> {
                    showArtwork(cachedArtwork.takeIf { cachedState.hasArtwork })
                    loadArtwork(imageUri, nextArtworkKey)
                }
                else -> showArtwork(cachedArtwork.takeIf { cachedState.hasArtwork })
            }
        }

        playPause?.text = if (playing) "Ⅱ" else "▶"
        val actions = state?.actions ?: 0L
        playPause?.isEnabled = active != null && (actions == 0L ||
            actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) ||
            active == null && !cachedState.packageName.isNullOrBlank()
        previous?.isEnabled = active != null && (actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        next?.isEnabled = active != null && (actions == 0L || actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
    }

    private fun showArtwork(image: Bitmap?) {
        if (image === lastArtwork) return
        lastArtwork = image
        if (image != null) artwork?.setImageBitmap(image)
        else artwork?.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun mediaArtworkUri(metadata: MediaMetadata): Uri? = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
    ).firstNotNullOfOrNull { key ->
        metadata.getString(key)?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { Uri.parse(value) }.getOrNull()
        }
    }

    private fun loadArtwork(uri: Uri, expectedKey: String) {
        val generation = ++artworkRequestGeneration
        artworkLoader.execute {
            val image = runCatching { decodeArtwork(uri) }.getOrNull()
            main.post {
                if (generation != artworkRequestGeneration || root == null || artworkKey != expectedKey) {
                    image?.recycle()
                    return@post
                }
                if (image == null) {
                    SessionDiagnostics.event("HU media artwork URI could not be loaded (${uri.scheme ?: "unknown"})")
                    return@post
                }
                cachedArtwork = image
                lastArtwork = image
                artwork?.setImageBitmap(image)
                cachedState = cachedState.copy(hasArtwork = true)
                HuMediaStateStore.save(context, cachedState, image, artworkChanged = true, flush = false)
                SessionDiagnostics.event("HU media artwork loaded from ${uri.scheme ?: "unknown"} URI")
            }
        }
    }

    private fun decodeArtwork(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (largestSide / sampleSize > 1024) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun persistCurrent(flush: Boolean) {
        val active = controller
        if (active == null) {
            if (flush && cachedState.packageName != null) {
                HuMediaStateStore.save(context, cachedState, cachedArtwork, artworkChanged = false, flush = true)
            }
            return
        }
        val metadata = active.metadata
        val currentTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: cachedState.title
        val currentArtist = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: cachedState.artist
        val image = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
        } ?: cachedArtwork
        val snapshot = HuMediaStateStore.Snapshot(
            packageName = active.packageName,
            title = currentTitle.orEmpty(),
            artist = currentArtist.orEmpty(),
            source = appLabel(active.packageName),
            wasPlaying = active.playbackState?.state == PlaybackState.STATE_PLAYING,
            hasArtwork = image != null,
        )
        HuMediaStateStore.save(context, snapshot, image,
            artworkChanged = flush || (image != null && image !== lastArtwork), flush = flush)
        cachedState = snapshot
        if (image != null) lastArtwork = image
    }

    private fun transport(action: (MediaController.TransportControls) -> Unit) {
        val active = controller ?: return
        runCatching { action(active.transportControls) }
        renderCurrentSession()
    }

    private fun appLabel(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun text(context: Context, value: String, size: Float, color: Int) =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * designDensity)
            setTextColor(color)
            setPadding(0, 2, 0, 2)
        }

    private fun button(context: Context, label: String, size: Float, color: Int, action: Button.() -> Unit) =
        Button(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * designDensity)
            setTextColor(color)
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
        }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun buttonSize(density: Float) = (38 * density).roundToInt()

    private companion object {
        const val PREFS = "carbridge_hu_layout"
        const val KEY_ON_RIGHT = "media_on_right"
        const val BASE_PANEL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        const val POLL_INTERVAL_MS = 5000L
        const val match = LinearLayout.LayoutParams.MATCH_PARENT
        const val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
