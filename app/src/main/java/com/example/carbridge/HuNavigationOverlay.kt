package com.example.carbridge

import android.app.Notification
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.abs

/** Permanent HU dock and app-owned shade. Every window belongs to a positive display id. */
class HuNavigationOverlay(
    context: Context,
    private val onBack: () -> Unit,
    private val onHome: () -> Unit,
    private val onApps: () -> Unit,
    private val onMediaToggle: () -> Unit,
    private val onPhoneWake: () -> Unit,
    private val onPhoneDim: () -> Unit,
    private val onLaunch: (String) -> Unit,
    private val onSettings: (String) -> Unit,
    private val onStopApp: (String) -> Unit,
    private val onText: (String) -> Boolean,
    private val onInputKey: (Int) -> Unit,
    private val onNotificationOpen: (StatusBarNotification) -> Unit,
    private val onNavigationCueVisibility: (Boolean) -> Unit = {},
    private val onNavigationForegroundCheck: () -> Unit = {},
    private val onWeatherToggle: () -> Unit = {},
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var windowContext: Context? = null
    private var view: LinearLayout? = null
    private var shade: LinearLayout? = null
    private var shadeBody: LinearLayout? = null
    private var shadeBackdrop: View? = null
    private var keyboard: HuKeyboardPanel? = null
    private var favorites: HuFavoritesPanel? = null
    private var banner: LinearLayout? = null
    private var navigationCue: HuNavigationCue? = null
    private var navigationCueNotificationKey = ""
    private var navigationCueMissingAt = 0L
    private var navigationCueSignState = ""
    private var pendingCueKey=""
    private var pendingCueAt=0L
    private var pendingCueCommitAt=0L
    private val revealCue=Runnable { renderNavigationCue() }
    private var expectedForeground: String?=null
    private var expectedUntil=0L
    private val finishExpectedForeground=Runnable {
        expectedForeground=null; expectedUntil=0L
        onNavigationForegroundCheck()
    }
    fun expectForegroundPackage(pkg: String) {
        expectedForeground=pkg; expectedUntil=android.os.SystemClock.elapsedRealtime()+2500L
        main.removeCallbacks(finishExpectedForeground); main.postDelayed(finishExpectedForeground,2500L)
    }
    fun cancelExpectedForeground(pkg: String) {
        if(expectedForeground!=pkg) return
        expectedForeground=null; expectedUntil=0L; main.removeCallbacks(finishExpectedForeground)
        renderDock(); renderNavigationCue(); onNavigationForegroundCheck()
    }
    private fun cancelPendingCue() {
        main.removeCallbacks(revealCue); pendingCueKey=""; pendingCueAt=0L; pendingCueCommitAt=0L
    }
    private var cueVisible = false
    private var cueHidden=context.getSharedPreferences("navigation_cue",0).getBoolean("hidden",false)
    private var cueButton: ImageView?=null
    private fun setCueHidden(hidden: Boolean) {
        cueHidden=hidden
        context.getSharedPreferences("navigation_cue",0).edit().putBoolean("hidden",hidden).apply()
        updateCueButton()
        renderNavigationCue()
    }
    private fun updateCueButton() {
        cueButton?.apply {
            alpha=if(cueHidden) 0.5f else 1f
            contentDescription=if(cueHidden) "Показать подсказки навигации" else "Скрыть подсказки навигации"
        }
    }
    private var navigationOnRight = true
    private var navigationCardHeight = 0
    private var navigationCardInset = 0
    private var navigationCardTop = 0
    fun hideNavigationCard() { setCueHidden(true) }
    private var weatherButton: ImageView?=null
    fun setWeatherVisible(visible: Boolean) { weatherButton?.alpha=if(visible) 1f else 0.6f }
    fun isNavigationCueVisible(): Boolean = cueVisible
    private fun publishCueVisibility(visible: Boolean) {
        if(cueVisible==visible) return
        cueVisible=visible
        onNavigationCueVisibility(visible)
    }
    fun setNavigationCardPlacement(right: Boolean, cardHeight: Int, inset: Int, top: Int=inset) {
        navigationCardTop=top
        navigationOnRight=right; navigationCardHeight=cardHeight; navigationCardInset=inset
        navigationCue?.setPlacement(right,cardHeight,inset,top)
        banner?.let { card ->
            val params=card.layoutParams as? WindowManager.LayoutParams ?: return@let
            val gravity=Gravity.TOP or if(right) Gravity.START else Gravity.END
            if(params.gravity!=gravity) {
                params.gravity=gravity
                runCatching { manager?.updateViewLayout(card,params) }
            }
        }
    }
    private val finishMissingNavigation = Runnable { renderNavigationCue(allowGrace = false) }
    private var phoneButton: ImageView? = null
    private var mediaButton: ImageView? = null
    private var statusButton: Button? = null
    private var batteryIcon: ImageView? = null
    private var dock: LinearLayout? = null
    private var notificationIcons: LinearLayout? = null
    private var connectionIcons: LinearLayout? = null
    private var appMenu: LinearLayout? = null
    private var phoneVisible = false
    private var mediaVisible = false
    private var resizeForMusic = true
    private var displayId = -1
    private var density = 1f
    private var barHeight = 0
    private var dockIconSize = 1
    private var width = 0
    private var height = 0
    private var registered = false
    private var activePackage: String? = null
    private var navigationForegroundPackage: String? = null
    private var historyKey = ""
    private var shadeVersion = ""
    private var lastPostedKey: String? = null
    private var lastPostedTime = 0L
    private var autoHide = false
    private var dockHidden = false
    private var handle: View? = null
    private var alertSignature = ""
    private var alertAt = 0L
    private val hideDock = object : Runnable {
        override fun run() {
            if (!autoHide || view == null) return
            if (shade != null || appMenu != null || keyboard != null || favorites?.isOpen == true) { main.postDelayed(this, 6000); return }
            dockHidden = true
            view?.let { root ->
                root.visibility = View.INVISIBLE
                runCatching { manager?.updateViewLayout(root, layout(1, 1, Gravity.BOTTOM).apply {
                    flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                }) }
            }
            handle?.visibility = View.VISIBLE
            inspectDock("auto hide")
        }
    }
    private fun holdDock() {
        if (!autoHide) return
        if (dockHidden) {
            dockHidden = false
            view?.let { root ->
                root.visibility = View.VISIBLE
                runCatching { manager?.updateViewLayout(root, layout(width, barHeight, Gravity.BOTTOM)) }
            }
            handle?.visibility = View.GONE
            inspectDock("user reveal")
        }
        main.removeCallbacks(hideDock)
        main.postDelayed(hideDock, 6000)
    }
    private var dockState=""
    private var dockStateAt=0L
    private var inspectingDock=false
    private fun inspectDock(reason: String) {
        val root=view ?: return
        if(inspectingDock) return
        inspectingDock=true
        try {
            val params=root.layoutParams as? WindowManager.LayoutParams
            if(!autoHide && root.isAttachedToWindow && params!=null) {
                val wrongWindow=params.width!=width || params.height!=barHeight || params.gravity!=Gravity.BOTTOM ||
                    params.x!=0 || params.y!=0 || (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0
                val wrongView=root.visibility!=View.VISIBLE || root.alpha<0.99f || root.translationY!=0f
                if(wrongWindow || wrongView) {
                    SessionDiagnostics.event("HU permanent dock restore requested; reason=$reason; viewWrong=$wrongView; windowWrong=$wrongWindow")
                    root.visibility=View.VISIBLE; root.alpha=1f; root.translationY=0f; dockHidden=false
                    runCatching { manager?.updateViewLayout(root,layout(width,barHeight,Gravity.BOTTOM)) }
                        .onFailure { SessionDiagnostics.event("HU permanent dock restore failed: ${it.javaClass.simpleName}") }
                }
            }
            val position=IntArray(2)
            root.getLocationOnScreen(position)
            val actual=root.layoutParams as? WindowManager.LayoutParams
            val state="display=$displayId; autoHide=$autoHide; hidden=$dockHidden; attached=${root.isAttachedToWindow}; " +
                "view=${root.visibility}; window=${root.windowVisibility}; shown=${root.isShown}; alpha=${root.alpha}; " +
                "bounds=${position[0]},${position[1]},${root.width},${root.height}; " +
                "requested=${actual?.width}x${actual?.height}; gravity=${actual?.gravity}; flags=${actual?.flags}"
            val now=android.os.SystemClock.elapsedRealtime()
            if(state!=dockState || now-dockStateAt>=30000L) {
                dockState=state; dockStateAt=now
                SessionDiagnostics.event("HU dock state; reason=$reason; $state")
            }
        } finally { inspectingDock=false }
    }
    private val hideBanner = Runnable { removeBanner() }
    private val update = object : Runnable {
        override fun run() {
            if (view == null) return
            renderStatus()
            renderDock()
            renderNavigationCue()
            inspectDock("periodic")
            main.postDelayed(this, 5000)
        }
    }
    private val notifications = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            renderStatus()
            renderNotifications()
            renderNavigationCue()
            val key = intent?.getStringExtra("posted_key")
            val item = if (key != null) visibleNotifications().firstOrNull { it.key == key }
                else visibleNotifications().firstOrNull { HuMediaNotificationListener.isStrelka(it.packageName) }
            if (item == null) return
            if (item.key == lastPostedKey && item.postTime == lastPostedTime) return
            lastPostedKey = item.key
            lastPostedTime = item.postTime
            val strelka = HuMediaNotificationListener.isStrelka(item.packageName)
            if (!strelka && item.isOngoing) return
            if (!strelka && item.notification.category == Notification.CATEGORY_TRANSPORT) return
            if (!strelka && HuNotificationContent.isNavigation(item, HuNotificationContent.read(windowContext ?: context,item))) return
            if (strelka) {
                val content = HuNotificationContent.read(windowContext ?: context,item)
                if(content.title.isBlank() && content.text.isBlank() && content.navigationHints.isBlank() && content.icon==null) return
                val signature = "${content.title}:${content.text}"
                val now = android.os.SystemClock.elapsedRealtime()
                if ((signature == alertSignature && now - alertAt < 15000) || now - alertAt < 2000) return
                alertSignature = signature; alertAt = now
                SessionDiagnostics.event("HU Strelka notification banner; ongoing=${item.isOngoing}")
            }
            showBanner(item)
        }
    }

    fun show(id: Int, interfaceDpi: Int = 200, outputWidth: Int = 0, hideAutomatically: Boolean = false,
        resizeForMusic: Boolean = true): Boolean {
        if (id <= 0 || !Settings.canDrawOverlays(context)) return false
        if (displayId == id && view != null && this.resizeForMusic == resizeForMusic) return true
        close()
        autoHide = hideAutomatically
        this.resizeForMusic = resizeForMusic
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(id)
            ?.takeIf { it.isValid } ?: return false
        return try {
            val wc = context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val wm = wc.getSystemService(WindowManager::class.java)
            windowContext = wc
            manager = wm
            displayId = id
            width = wc.resources.displayMetrics.widthPixels
            height = wc.resources.displayMetrics.heightPixels
            val scale = if (outputWidth > 0) width.toFloat() / outputWidth else 1f
            // Keep room for controls, the app dock, and the clock even on small head units.
            density = (interfaceDpi.coerceIn(80, 320) / 160f * scale).coerceAtMost(width / 700f)
            barHeight = px(HuDockDimensions.HEIGHT_DP)
            dockIconSize = px(24).coerceAtLeast(1)
            val root = object : LinearLayout(wc) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) holdDock()
                    return super.dispatchTouchEvent(event)
                }
                override fun onWindowVisibilityChanged(visibility: Int) {
                    super.onWindowVisibilityChanged(visibility)
                    if(this===view) inspectDock("window visibility=$visibility")
                }
                override fun onDetachedFromWindow() {
                    if(this===view) SessionDiagnostics.event("HU dock window detached; display=$displayId; autoHide=$autoHide")
                    super.onDetachedFromWindow()
                }
            }.apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(6), px(3), px(6), px(3))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 23, 32))
                    cornerRadius = px(10).toFloat()
                }
                clipToOutline = true
            }
            fun section() = LinearLayout(wc).apply { gravity = Gravity.CENTER_VERTICAL }.also {
                root.addView(it, LinearLayout.LayoutParams(0, -1, 1f))
            }
            fun divider() { root.addView(View(wc).apply { setBackgroundColor(Color.rgb(68, 88, 110)) },
                LinearLayout.LayoutParams(px(1), -1).apply { setMargins(px(3), px(4), px(3), px(4)) }) }
            val controls = section()
            controls.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateControlIcons(controls)
            }
            fun control(kind: String, description: String, action: () -> Unit): ImageView =
                ImageView(wc).apply {
                    contentDescription = description
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(HuPanelIcons.drawable(context, kind))
                    setOnClickListener { action() }
                }.also { controls.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            control("back", "Назад", onBack)
            control("home", "Домой", onHome)
            control("apps", "Все приложения", onApps)
            control("favorites", "Избранные приложения") { favorites?.open() }
            mediaButton = control("music", "Музыка / карта", onMediaToggle)
            cueButton = control("location", "Подсказки навигации") { setCueHidden(!cueHidden) }
            updateCueButton()
            weatherButton=control("weather", "Погода",onWeatherToggle)
            setWeatherVisible(false)
            phoneButton = control("phone_off", "Экран телефона") {
                if (phoneVisible) onPhoneDim() else onPhoneWake()
            }
            control("keyboard", "Ввод текста на ГУ") { toggleKeyboard() }
            divider()
            dock = LinearLayout(wc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left != oldRight - oldLeft) main.post { renderDock() }
                }
            }.also { root.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            divider()
            val statusGroup = section()
            notificationIcons = LinearLayout(wc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setOnClickListener { toggleShade() }
                addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (right - left != oldRight - oldLeft) main.post {
                        if (view != null) renderStatus()
                    }
                }
            }.also { statusGroup.addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
            connectionIcons = LinearLayout(wc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setOnClickListener { toggleShade() }
            }.also { statusGroup.addView(it, LinearLayout.LayoutParams(-2, -1)) }
            batteryIcon = ImageView(wc).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { toggleShade() }
            }.also { statusGroup.addView(it, LinearLayout.LayoutParams(dockIconSize,dockIconSize)) }
            statusButton = button("", "Подключения, уведомления и быстрые настройки") { toggleShade() }
                .apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * density); maxLines = 1 }
                .also { statusGroup.addView(it, LinearLayout.LayoutParams(px(76), -1)) }
            wm.addView(root, layout(width, barHeight, Gravity.BOTTOM))
            view = root
            root.post { if(view===root) updateControlIcons(controls) }
            favorites = HuFavoritesPanel(wc, wm, width, height, barHeight, density, onLaunch, { dismissApplications() })
            if (autoHide) {
                val edge = View(wc).apply {
                    setBackgroundColor(Color.rgb(107, 183, 245))
                    contentDescription = "Показать панель CarBridge"
                    visibility = View.GONE
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) holdDock()
                        true
                    }
                }
                wm.addView(edge, layout(px(88), px(8), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                handle = edge
                holdDock()
            }
            context.registerReceiver(notifications, IntentFilter(HuMediaNotificationListener.ACTION_NOTIFICATIONS_CHANGED),
                Context.RECEIVER_NOT_EXPORTED)
            registered = true
            if (!HuMediaNotificationListener.connected) runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(context, HuMediaNotificationListener::class.java))
            }
            setPhoneVisible(phoneVisible)
            setMediaVisible(mediaVisible)
            main.post(update)
            main.post {
                if (displayId == id && view != null) notifications.onReceive(context, null)
            }
            SessionDiagnostics.event("HU bottom dock attached; display=$id; height=$barHeight; autoHide=$autoHide")
            true
        } catch (e: Exception) {
            SessionDiagnostics.event("HU bottom dock unavailable: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    private fun button(value: String, description: String, action: () -> Unit) = Button(windowContext!!).apply {
        text = value
        contentDescription = description
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * density)
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(px(3), 0, px(3), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float = 17f) = TextView(windowContext!!).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * density)
        setPadding(px(10), px(6), px(10), px(6))
    }

    private fun layout(w: Int, h: Int, gravityValue: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = gravityValue; title = "CarBridge HU dock" }

    /** Fit artwork to the actual control cells, not DPI-sized padding in a fixed third.
     * All three sections share the resulting glyph size; touch targets keep their full cells. */
    private fun updateControlIcons(controls: LinearLayout) {
        if(controls.width<=0 || controls.height<=0 || controls.childCount==0) return
        val cellWidth=(0 until controls.childCount).map { controls.getChildAt(it).width }
            .filter { it>0 }.minOrNull() ?: return
        val size=minOf(px(24), (cellWidth*0.82f).roundToInt(),
            (controls.height*0.8f).roundToInt()).coerceAtLeast(1)
        for(index in 0 until controls.childCount) {
            val icon=controls.getChildAt(index) as? ImageView ?: continue
            val horizontal=(icon.width-size).coerceAtLeast(0)
            val vertical=(icon.height-size).coerceAtLeast(0)
            val left=horizontal/2; val top=vertical/2
            if(icon.paddingLeft!=left || icon.paddingTop!=top ||
                icon.paddingRight!=horizontal-left || icon.paddingBottom!=vertical-top) {
                icon.setPadding(left,top,horizontal-left,vertical-top)
            }
        }
        if(dockIconSize==size) return
        dockIconSize=size
        batteryIcon?.layoutParams=LinearLayout.LayoutParams(size,size)
        renderDock()
        renderStatus()
        SessionDiagnostics.event("HU dock icon geometry; display=$displayId; barHeight=$barHeight; controlCell=$cellWidth; glyphSize=$size")
    }

    private fun renderDock() {
        val target = dock ?: return
        val recent = HuLauncherStore.recent(context)
        val key = recent.joinToString() + activePackage + target.width + ":$dockIconSize"
        if (historyKey == key) return
        historyKey = key
        target.removeAllViews()
        val slotWidth=(dockIconSize*2).coerceAtLeast(1)
        val maxIcons = (target.width / slotWidth).coerceIn(0, 8)
        val shown = (listOfNotNull(activePackage) + recent).distinct().filter { it != context.packageName }.take(maxIcons)
        shown.forEach { pkg ->
            val icon = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return@forEach
            val name = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            val cell = LinearLayout(windowContext!!).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                contentDescription = "$name: ${if (pkg == activePackage) "активно" else "открыть снова"}"
                setOnClickListener { dismissApplications(); onLaunch(pkg) }
                setOnLongClickListener { showAppMenu(pkg, name, it); true }
            }
            cell.addView(ImageView(windowContext!!).apply {
                scaleType=ImageView.ScaleType.FIT_CENTER
                setImageDrawable(icon)
            }, LinearLayout.LayoutParams(dockIconSize,dockIconSize))
            cell.addView(View(windowContext!!).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (pkg == activePackage) Color.rgb(117, 199, 255) else Color.GRAY)
                }
            }, LinearLayout.LayoutParams(px(3), px(3)).apply { topMargin = px(2) })
            target.addView(cell, LinearLayout.LayoutParams(slotWidth, -1))
        }
    }

    // Only a display snapshot (or our visible home Presentation) can commit this state.
    // Sending an Intent must not reveal/reorder navigation widgets by itself.
    fun setForegroundPackage(pkg: String?) {
        if (pkg != null && pkg == expectedForeground) {
            expectedForeground=null; expectedUntil=0L; main.removeCallbacks(finishExpectedForeground)
        }
        activePackage = pkg
        HuGuidanceSource.foreground(pkg)
        if(pkg!=null) navigationForegroundPackage=pkg
        renderDock()
        renderNavigationCue()
    }

    fun isForegroundPackage(pkg: String): Boolean = activePackage == pkg

    fun forgetApplication(pkg: String) {
        HuLauncherStore.remove(context, pkg)
        if (activePackage == pkg) activePackage = null
        historyKey = ""
        renderDock()
    }

    private fun showAppMenu(pkg: String, name: String, anchor: View) {
        dismissApplications()
        val menu = LinearLayout(windowContext!!).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(6), px(8), px(6))
            setBackgroundColor(Color.rgb(30, 42, 57))
            addView(label(name, 16f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            addView(button("Открыть", "Открыть $name") { dismissApplications(); onLaunch(pkg) }
                .apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * density) }, LinearLayout.LayoutParams(-1, px(44)))
            addView(button("Остановить", "Остановить $name на ГУ и телефоне") { dismissApplications(); onStopApp(pkg) }
                .apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * density) }, LinearLayout.LayoutParams(-1, px(44)))
            addView(label("Остановка также завершит приложение на телефоне", 12f))
        }
        val position = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val menuWidth = px(250).coerceAtMost(width)
        val params = layout(menuWidth, -2, Gravity.BOTTOM or Gravity.START).apply {
            y = barHeight + px(4); x = position[0].coerceIn(0, width - menuWidth)
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        menu.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissApplications(); true } else false
        }
        runCatching { manager?.addView(menu, params) }.onSuccess { appMenu = menu }
    }

    private fun visibleNotifications(): List<StatusBarNotification> {
        val all = HuMediaNotificationListener.snapshot()
        return all.filter { item ->
            item.packageName != context.packageName &&
                (item.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 ||
                    ((HuMediaNotificationListener.isStrelka(item.packageName) || item.packageName in HuNotificationContent.navigationPackages) && all.none {
                        it.packageName == item.packageName && it.groupKey == item.groupKey &&
                            it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
                    }))
        }
    }

    private fun renderStatus() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val charge = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        val bt = runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
        connectionIcons?.let { row ->
            row.removeAllViews()
            fun add(kind: String, description: String) {
                row.addView(ImageView(windowContext!!).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(HuPanelIcons.drawable(context, kind)); contentDescription = description
                }, LinearLayout.LayoutParams(dockIconSize,dockIconSize).apply { marginStart=px(2) })
            }
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }) add("wifi", "Wi-Fi подключён")
            if (bt) add("bluetooth", "Bluetooth включён")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }) {
                add("cellular", "Мобильная сеть подключена")
                val generation = runCatching {
                    when (context.getSystemService(TelephonyManager::class.java).dataNetworkType) {
                        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                        TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
                        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
                        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
                        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
                        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
                        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_CDMA -> "2G"
                        else -> ""
                    }
                }.getOrDefault("")
                if (generation.isNotBlank()) row.addView(label(generation, 11f).apply { setPadding(0, 0, px(2), 0) })
            }
        }
        val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "—"
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        batteryIcon?.apply {
            setImageDrawable(HuBatteryIcon(batteryPercent, charge))
            contentDescription = "Батарея $percent${if (charge) ", подключено питание" else ""}"
        }
        statusButton?.text = "$percent  ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}"
        val icons = notificationIcons ?: return
        icons.removeAllViews()
        // Charging/USB system notices are not application notifications in the dock.
        val notices = visibleNotifications().filter { it.packageName !in setOf("android", "com.android.systemui") }
            .distinctBy { it.packageName }
        // Connectivity, battery and clock reserve their natural width. Notifications grow
        // leftwards into the remaining space of this section, never into the app dock.
        val slotWidth = (dockIconSize+px(2)).coerceAtLeast(1)
        val capacity = (icons.width / slotWidth).coerceAtLeast(0)
        val overflow = notices.size > capacity
        val shownCount = if (overflow) (capacity - 1).coerceAtLeast(0) else notices.size
        notices.take(shownCount).forEach { item ->
            val icon = ImageView(windowContext!!).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(0,0,px(2),0)
                setImageDrawable(runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull())
                contentDescription = "Уведомления ${item.packageName}"
                setOnClickListener { toggleShade() }
            }
            icons.addView(icon, LinearLayout.LayoutParams(slotWidth,dockIconSize))
        }
        if (overflow && capacity > 0) {
            val remaining = notices.size - shownCount
            icons.addView(label("+$remaining", 10f).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                contentDescription = "Ещё $remaining приложений с уведомлениями"
                setOnClickListener { toggleShade() }
            }, LinearLayout.LayoutParams(slotWidth,dockIconSize))
        }
    }

    private fun toggleShade() {
        if (shade != null) { dismissApplications(); return }
        dismissApplications()
        removeBanner()
        val wc = windowContext ?: return
        val root = LinearLayout(wc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
            setBackgroundColor(Color.rgb(24, 32, 44))
        }
        val heading = LinearLayout(wc).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label("Уведомления и настройки", 22f), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(button("×", "Закрыть шторку") { dismissApplications() }, LinearLayout.LayoutParams(px(48), px(48)))
        root.addView(heading)
        fun row(tiles: List<Pair<String, String>>) {
            val line = LinearLayout(wc)
            tiles.forEach { (name, action) ->
                val kind = when (action) {
                    Settings.ACTION_WIFI_SETTINGS -> "wifi"
                    Settings.ACTION_BLUETOOTH_SETTINGS -> "bluetooth"
                    Settings.ACTION_WIRELESS_SETTINGS -> "cellular"
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS -> "location"
                    Settings.ACTION_DISPLAY_SETTINGS -> "display"
                    else -> "settings"
                }
                val tile = LinearLayout(wc).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(px(3), px(6), px(3), px(6))
                    contentDescription = name
                    setOnClickListener { dismissApplications(); onSettings(action) }
                    addView(ImageView(wc).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE; setImageDrawable(HuPanelIcons.drawable(context, kind)) },
                        LinearLayout.LayoutParams(px(23), px(23)))
                    addView(label(name, 13f).apply {
                        gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, px(4), 0, 0)
                    }, LinearLayout.LayoutParams(-1, px(23)))
                }
                line.addView(tile, LinearLayout.LayoutParams(0, px(66), 1f))
            }
            root.addView(line)
        }
        row(listOf("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "Bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "Мобильная сеть" to Settings.ACTION_WIRELESS_SETTINGS))
        row(listOf("Геолокация" to Settings.ACTION_LOCATION_SOURCE_SETTINGS, "Экран" to Settings.ACTION_DISPLAY_SETTINGS,
            "Настройки" to Settings.ACTION_SETTINGS))
        val sound = LinearLayout(wc).apply { gravity = Gravity.CENTER_VERTICAL }
        val audio = context.getSystemService(AudioManager::class.java)
        sound.addView(button("Звук", "Настройки звука") { dismissApplications(); onSettings(Settings.ACTION_SOUND_SETTINGS) }
            .apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * density) }, LinearLayout.LayoutParams(px(90), px(45)))
        sound.addView(SeekBar(wc).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            contentDescription = "Громкость мультимедиа телефона"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0) }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) { }
                override fun onStopTrackingTouch(bar: SeekBar?) { }
            })
        }, LinearLayout.LayoutParams(0, px(45), 1f))
        root.addView(sound)
        val location = runCatching { context.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
        root.addView(label("Геолокация: ${if (location) "включена" else "выключена"} · Настройки относятся к телефону", 13f))
        val scroll = ScrollView(wc)
        shadeBody = LinearLayout(wc).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(shadeBody)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val panelWidth = (width * 0.65f).roundToInt().coerceAtLeast(px(420)).coerceAtMost(width)
        val params = layout(panelWidth, height - barHeight, Gravity.BOTTOM or Gravity.END).apply { y = barHeight }
        val backdrop = View(wc).apply {
            contentDescription = "Закрыть шторку"
            setOnClickListener { dismissApplications() }
        }
        runCatching {
            manager?.addView(backdrop, layout(width, height, Gravity.TOP or Gravity.START))
            shadeBackdrop = backdrop
            manager?.addView(root, params)
        }.onSuccess {
            shade = root; shadeVersion = ""; renderNotifications()
        }.onFailure {
            shadeBody = null
            shadeBackdrop?.let { backdropView -> runCatching { manager?.removeViewImmediate(backdropView) } }
            shadeBackdrop = null
            SessionDiagnostics.event("HU shade unavailable: ${it.javaClass.simpleName}")
        }
    }

    private fun renderNotifications() {
        val body = shadeBody ?: return
        val items = visibleNotifications()
        val version = HuMediaNotificationListener.connected.toString() + items.joinToString { "${it.key}:${it.postTime}" }
        if (version == shadeVersion) return
        shadeVersion = version
        body.removeAllViews()
        if (!HuMediaNotificationListener.connected) {
            body.addView(label("Нет доступа к уведомлениям. Включите доступ CarBridge на телефоне."))
            body.addView(button("Разрешить уведомления", "Открыть доступ к уведомлениям") {
                dismissApplications(); onSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }.apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f * density) })
        } else if (items.isEmpty()) body.addView(label("Нет уведомлений"))
        items.take(60).forEach { item ->
            val controller = HuShadeMediaCard.controller(context, item)
            val card = if (controller != null) HuShadeMediaCard(windowContext!!, item, density, controller) { openNotification(item) }
                else notificationCard(item)
            if (controller != null) installNotificationGesture(card, item)
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        }
    }

    private fun notificationCard(item: StatusBarNotification, compact: Boolean = false): LinearLayout {
        val n = item.notification
        val appName = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(item.packageName, 0)).toString() }.getOrDefault(item.packageName)
        return LinearLayout(windowContext!!).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(if (compact) 10 else 5), px(if (compact) 8 else 5), px(5), px(if (compact) 8 else 7))
            val heading = LinearLayout(windowContext!!).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(ImageView(windowContext!!).apply {
                setImageDrawable(runCatching { context.packageManager.getApplicationIcon(item.packageName) }.getOrNull()
                    ?: runCatching { n.smallIcon?.loadDrawable(context) }.getOrNull())
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = appName
            }, LinearLayout.LayoutParams(px(if (compact) 22 else 28), px(if (compact) 22 else 28)).apply {
                leftMargin = px(if (compact) 0 else 10); rightMargin = px(6)
            })
            heading.addView(label(appName, 14f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(Color.rgb(137, 198, 255))
                if (compact) { setPadding(0, 0, 0, 0); includeFontPadding = false }
            }, LinearLayout.LayoutParams(0, px(if (compact) 24 else 34), 1f))
            addView(heading)
            val content = HuNotificationContent.read(windowContext!!, item)
            val title = content.title
            val text = content.text
            addView(label(title.ifBlank { "Уведомление" }, 18f).apply {
                maxLines = if (compact) 1 else 2; ellipsize = TextUtils.TruncateAt.END
                if (compact) { setPadding(0, 0, 0, 0); includeFontPadding = false }
            })
            if (text.isNotBlank()) addView(label(text, 16f).apply {
                maxLines = if (compact) 2 else 4; ellipsize = TextUtils.TruncateAt.END
                if (compact) { setPadding(0, 0, 0, 0); includeFontPadding = false }
            })
            setOnClickListener { openNotification(item) }
            installNotificationGesture(this, item)
        }
    }

    private fun installNotificationGesture(card: View, item: StatusBarNotification) {
        val slop = ViewConfiguration.get(windowContext!!).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var swiping = false
        card.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX; startY = event.rawY; swiping = false
                    v.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!swiping && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f) {
                        swiping = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (swiping) {
                        v.translationX = dx
                        v.alpha = (1f - abs(dx) / v.width.coerceAtLeast(1)).coerceAtLeast(0.3f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (swiping && abs(dx) >= maxOf(px(65).toFloat(), v.width * 0.22f)) {
                        val dismissed = HuMediaNotificationListener.dismiss(item.key)
                        if (!dismissed) v.announceForAccessibility("Это уведомление нельзя убрать")
                        else if (banner != null && shade == null) removeBanner()
                    } else if (!swiping && abs(dx) < slop && abs(dy) < slop) v.performClick()
                    // The real removal callback updates the shade. If Android refuses,
                    // keep the original card instead of pretending it was cancelled.
                    v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun openNotification(item: StatusBarNotification) {
        dismissApplications()
        removeBanner()
        // Use the same display-scoped launcher path as dock icons. PendingIntents
        // can silently launch on display 0 or open a phone-side chooser.
        onNotificationOpen(item)
        SessionDiagnostics.event("HU notification app requested=${item.packageName}; display=$displayId")
    }

    private fun renderNavigationCue(allowGrace: Boolean = true) {
        if(cueHidden) {
            cancelPendingCue()
            main.removeCallbacks(finishMissingNavigation); navigationCueMissingAt=0L
            navigationCue?.hide(); publishCueVisibility(false)
            return
        }
        val wc=windowContext ?: return
        val wm=manager ?: return
        val foreground=navigationForegroundPackage ?: return
        val items=HuGuidanceSource.filter(visibleNotifications().filter {
            HuNotificationContent.isNavigation(it,HuNotificationContent.read(wc,it))
        })
        // A visible map owns its own prompts; hide our compact card immediately.
        if(items.any { it.packageName==foreground }) {
            cancelPendingCue()
            main.removeCallbacks(finishMissingNavigation); navigationCueMissingAt=0L
            navigationCue?.hide(); navigationCueNotificationKey=""
            publishCueVisibility(false)
            return
        }
        val candidate=items.firstOrNull { it.key==navigationCueNotificationKey } ?: items.firstOrNull()
        if(candidate==null) {
            cancelPendingCue()
            if(navigationCue?.visible==true && allowGrace) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(navigationCueMissingAt==0L) {
                    navigationCueMissingAt=now
                    main.postDelayed(finishMissingNavigation,1500L)
                }
                if(now-navigationCueMissingAt<1500L) return
            }
            main.removeCallbacks(finishMissingNavigation); navigationCueMissingAt=0L
            navigationCue?.hide(); navigationCueNotificationKey=""
            publishCueVisibility(false)
            return
        }
        // Do not resize for a transient notification while the navigator is coming to the front.
        if(!cueVisible) {
            val now=android.os.SystemClock.elapsedRealtime()
            if(pendingCueKey!=candidate.key) {
                cancelPendingCue(); pendingCueKey=candidate.key; pendingCueAt=now
                onNavigationForegroundCheck()
            }
            val remaining=650L-(now-pendingCueAt)
            if(remaining>0L) {
                main.removeCallbacks(revealCue); main.postDelayed(revealCue,remaining)
                return
            }
            // Re-sample after the settling period too: the first snapshot may precede
            // an external intent bringing the navigator to the front.
            if(pendingCueCommitAt==0L) {
                pendingCueCommitAt=now+150L
                onNavigationForegroundCheck()
            }
            val verifyRemaining=pendingCueCommitAt-now
            if(verifyRemaining>0L) {
                main.removeCallbacks(revealCue); main.postDelayed(revealCue,verifyRemaining)
                return
            }
        }
        main.removeCallbacks(finishMissingNavigation); navigationCueMissingAt=0L
        val content=HuNotificationContent.read(wc,candidate)
        val card=navigationCue ?: HuNavigationCue(wc,wm,width,density,resizeForMusic,
            onHide={ setCueHidden(true) },open={ item ->
                dismissApplications()
                SessionDiagnostics.event("HU navigation card app launch requested; package=${item.packageName}; overlayDisplay=$displayId; method=explicit_application_launch")
                onLaunch(item.packageName)
            })
            .also { navigationCue=it }
        val geometry=HuTopCardLayout(width,density,resizeForMusic)
        card.setPlacement(navigationOnRight,
            navigationCardHeight.takeIf { it>0 } ?: geometry.navigationHeight,
            navigationCardInset.takeIf { navigationCardHeight>0 } ?: geometry.inset,
            navigationCardTop.takeIf { navigationCardHeight>0 } ?: geometry.inset)
        card.show(candidate,content)
        publishCueVisibility(card.visible)
        cancelPendingCue()
        navigationCueNotificationKey=candidate.key
        val sceneContent=HuNavigationSceneContent.read(content)
        val signState="${candidate.packageName}:${content.roadSign!=null}:${content.speedLimitKmh!=null}:${content.graphicRole}:${sceneContent.turn}"
        if(signState!=navigationCueSignState) {
            navigationCueSignState=signState
            SessionDiagnostics.event("HU navigation cue content; package=${candidate.packageName}; roadImage=${content.roadSign!=null}; explicitSpeedLimit=${content.speedLimitKmh!=null}; graphic=${content.graphicRole}; turn=${sceneContent.turn}; distance=${sceneContent.distance.orEmpty()}")
        }
    }

    private fun showBanner(item: StatusBarNotification) {
        if (view == null || shade != null) return
        removeBanner()
        val card = notificationCard(item, compact = true)
        val geometry = HuTopCardLayout(width, density, resizeForMusic)
        val line = LinearLayout(windowContext!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(23, 39, 56)); cornerRadius = geometry.radius
            }
            clipToOutline = true
            addView(card, LinearLayout.LayoutParams(0, -1, 1f))
            addView(button("×", "Скрыть уведомление") { removeBanner() }, LinearLayout.LayoutParams(px(44), px(44)))
        }
        val params = layout(geometry.notificationWidth, geometry.height,
            Gravity.TOP or if(navigationOnRight) Gravity.START else Gravity.END).apply {
            x = geometry.inset; y = geometry.inset
        }
        runCatching { manager?.addView(line, params) }.onSuccess { banner = line; main.postDelayed(hideBanner, 6500) }
    }

    private fun removeBanner() {
        main.removeCallbacks(hideBanner)
        banner?.let { runCatching { manager?.removeViewImmediate(it) } }
        banner = null
    }

    fun setPhoneVisible(visible: Boolean) { phoneVisible = visible; phoneButton?.setImageDrawable(HuPanelIcons.drawable(context, if (visible) "phone_on" else "phone_off")) }
    fun setMediaVisible(visible: Boolean) { mediaVisible = visible; mediaButton?.setImageDrawable(HuPanelIcons.drawable(context, if (visible) "display" else "music")) }
    private fun toggleKeyboard() {
        if (keyboard != null) { keyboard?.close(); keyboard = null; return }
        dismissApplications()
        removeBanner()
        val wc = windowContext ?: return
        val wm = manager ?: return
        holdDock()
        val panel = HuKeyboardPanel(wc, wm, width, height, barHeight, density, onText, onInputKey,
            onClose = { keyboard = null; holdDock() })
        keyboard = panel
        panel.show()
    }
    fun isApplicationPickerOpen(): Boolean = shade != null || appMenu != null || keyboard != null || favorites?.isOpen == true
    fun dismissApplications() {
        favorites?.dismiss()
        keyboard?.close(); keyboard = null
        shadeBackdrop?.let { runCatching { manager?.removeViewImmediate(it) } }
        shadeBackdrop = null
        appMenu?.let { runCatching { manager?.removeViewImmediate(it) } }
        appMenu = null
        shade?.let { runCatching { manager?.removeViewImmediate(it) } }
        shade = null; shadeBody = null; shadeVersion = ""
    }
    fun close() {
        if(view!=null) SessionDiagnostics.event("HU dock close; display=$displayId; autoHide=$autoHide; hidden=$dockHidden")
        cancelPendingCue()
        main.removeCallbacks(finishExpectedForeground); expectedForeground=null; expectedUntil=0L
        dockState=""; dockStateAt=0L
        main.removeCallbacks(update)
        main.removeCallbacks(hideDock)
        handle?.let { runCatching { manager?.removeViewImmediate(it) } }; handle = null
        dockHidden = false
        dismissApplications()
        favorites?.close(); favorites = null
        removeBanner()
        if (registered) runCatching { context.unregisterReceiver(notifications) }
        registered = false
        view?.let { runCatching { manager?.removeViewImmediate(it) } }
        main.removeCallbacks(finishMissingNavigation)
        navigationCue?.close(); navigationCue=null; navigationCueNotificationKey=""
        publishCueVisibility(false)
        navigationCardHeight=0; navigationCardInset=0
        navigationCueMissingAt=0L; navigationCueSignState=""
        view = null; manager = null; windowContext = null; displayId = -1
        dock = null; phoneButton = null; mediaButton = null; cueButton=null; weatherButton=null; statusButton = null; batteryIcon = null; notificationIcons = null
        connectionIcons = null
        activePackage = null; navigationForegroundPackage=null
        historyKey = ""
        lastPostedKey = ""; lastPostedTime = 0L
        alertSignature = ""; alertAt = 0L
    }
    private fun px(value: Int) = (value * density).roundToInt()
}

