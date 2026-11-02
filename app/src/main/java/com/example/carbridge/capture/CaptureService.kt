package com.example.carbridge.capture

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.example.carbridge.scrcpy.ScrcpyAudioSource
import com.example.carbridge.HuMediaNotificationListener
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.carbridge.BridgeSettings
import com.example.carbridge.HuContentPresentation
import com.example.carbridge.HuHomePresentation
import com.example.carbridge.HuMediaOverlay
import com.example.carbridge.HuNavigationOverlay
import com.example.carbridge.SessionDiagnostics
import com.example.carbridge.VideoProfile
import com.example.carbridge.adb.AdbSession
import com.example.carbridge.adb.AdbConnectionMode
import com.example.carbridge.adb.OfflineAdbHealth
import com.example.carbridge.adb.OfflineDisconnectMonitor
import com.example.carbridge.input.AccessibilityTouchService
import com.example.carbridge.input.TouchMapper
import com.example.carbridge.link.CarLifeHuLink
import com.example.carbridge.link.CarLifeWifiTransport
import com.example.carbridge.link.HuLink
import com.example.carbridge.link.StubHuLink
import com.example.carbridge.scrcpy.ScrcpySource
import com.example.carbridge.scrcpy.PhonePanelState
import kotlin.math.roundToInt
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** CarLife transport plus either a shell-owned display (ADB) or a MediaProjection mirror. */
class CaptureService : Service(), HuLink.Listener, ScreenSource.Listener {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var link: HuLink? = null
    @Volatile private var mirror: ScreenSource? = null
    @Volatile private var adbSource: ScrcpySource? = null
    @Volatile private var mapper: TouchMapper? = null
    private var resultCode = 0
    private var resultData: Intent? = null
    private var mode = MODE_ADB
    private var selectedPackage = "ru.yandex.yandexmaps"
    private var connectionSettings = BridgeSettings.Config()
    @Volatile private var screenOffRequested = false
    @Volatile private var receivedFrame = false
    private var huWidth = 0
    private var huHeight = 0
    private var usingUsb = false
    private var usingWifiCarLife = false
    @Volatile private var generation = 0
    private var recoveryWakeLock: PowerManager.WakeLock? = null
    private val phonePanel = PhonePanelState()
    private var sessionActive = false
    private var usbRecoveryAttempts = 0
    private var usbRecoveryRunnable: Runnable? = null
    private var usbRecoveryStableRunnable: Runnable? = null
    @Volatile private var latestScrcpyConfig: ByteArray? = null
    private var thermalCapAppliedFps = Int.MAX_VALUE
    private var announcedDisplay = false
    private var openingApplicationPicker = false
    private var pickerRevision = 0
    private var homePresentation: HuHomePresentation? = null
    private var contentPresentation: HuContentPresentation? = null
    private var applicationDisplayId = -1
    private val usbAudioLock = Any()
    @Volatile private var usbAudioSource: ScrcpyAudioSource? = null
    private var usbAudioLink: HuLink? = null
    private var audioHuReadyAt = Long.MAX_VALUE
    private var audioSuspended = false
    private var audioRetryAt = 0L
    private var audioFailures = 0
    private val carLifeMetadata by lazy { com.example.carbridge.HuCarLifeMediaMetadata(applicationContext) }
    private val carLifeNavigation by lazy { com.example.carbridge.HuCarLifeNavigation(applicationContext) }
    private var strelkaDiagnosticAt = -30000L
    private val audioPoll = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            updateUsbAudio()
            val now = SystemClock.elapsedRealtime()
            if (now - strelkaDiagnosticAt >= 30000L) {
                strelkaDiagnosticAt = now
                com.example.carbridge.HuMediaNotificationListener.diagnoseStrelka(applicationContext)
            }
            updateCarLifeMetadata()
            main.postDelayed(this, 1000)
        }
    }
    private fun updateCarLifeMetadata() {
        val target = link.takeIf { receivedFrame && SystemClock.elapsedRealtime() >= audioHuReadyAt }
        carLifeMetadata.update(if (connectionSettings.usbAudio || connectionSettings.carLifeArtwork) target else null,
            connectionSettings.carLifeArtwork)
        carLifeNavigation.update(target, connectionSettings.carLifeNavigation)
    }
    private val navigation: HuNavigationOverlay by lazy {
        HuNavigationOverlay(applicationContext,
            onBack = { handleHuBack() },
            onHome = { showHomeScreen() },
            onApps = { showApplications() },
            onMediaToggle = { toggleMediaCard() },
            onPhoneWake = { applyPhoneChoice(phonePanel.explicit(true), wake = true) },
            onPhoneDim = { applyPhoneChoice(phonePanel.explicit(false), wake = true) },
            onLaunch = { launchSelectedApp(it) },
            onSettings = { action -> dismissHomePresentation(); adbSource?.launchSettings(action) },
            onStopApp = { packageName -> stopHuApplication(packageName) },
            onText = { text ->
                val accepted = adbSource?.pasteText(text) == true
                publishStatus(if (accepted) "Текст отправлен в выбранное поле ГУ" else "Не удалось отправить текст: дисплей не готов")
                accepted
            },
            onInputKey = { adbSource?.sendKey(it) },
            onNotificationOpen = { openNotificationOnHu(it) })
    }
    private val mediaOverlay: HuMediaOverlay by lazy {
        HuMediaOverlay(applicationContext,
            onDismissPanel = { setMediaCardVisible(false) },
            onOpenMediaSource = { packageName -> openMediaSourceOnPhone(packageName) },
            onLayoutChanged = { visible, right ->
                navigation.setMediaVisible(visible)
                contentPresentation?.setMusicLayout(visible && connectionSettings.resizeForMusic, right)
                homePresentation?.setMusicLayout(visible && connectionSettings.resizeForMusic, right)
            })
    }

    private val thermalStatusListener = object : PowerManager.OnThermalStatusChangedListener {
        override fun onThermalStatusChanged(status: Int) {
            if (!sessionActive || mode != MODE_ADB || status < PowerManager.THERMAL_STATUS_MODERATE) return
            val thermalTarget = if (status >= PowerManager.THERMAL_STATUS_SEVERE)
                VideoProfile.LOW_HEAT else VideoProfile.ECONOMY
            if (thermalTarget.maxFps >= thermalCapAppliedFps) return
            thermalCapAppliedFps = thermalTarget.maxFps
            val selected = VideoProfile.fromPreference(connectionSettings.videoProfile)
            val encoderAlreadyStarted = adbSource != null
            if (selected.maxFps > thermalTarget.maxFps) {
                BridgeSettings.setVideoProfile(applicationContext, thermalTarget, userSelected = false)
                connectionSettings = connectionSettings.copy(videoProfile = thermalTarget.preferenceValue)
            }
            SessionDiagnostics.event("Thermal status=$status; profile=${thermalTarget.preferenceValue}; " +
                "${if (selected.maxFps > thermalTarget.maxFps) "applied" else "already active"}; " +
                "encoder_already_started=$encoderAlreadyStarted")
            val message = when {
                selected.maxFps <= thermalTarget.maxFps ->
                    "Телефон нагрелся: уже выбран профиль «${thermalTarget.label}»."
                encoderAlreadyStarted ->
                    "Телефон нагрелся: профиль «${thermalTarget.label}» сохранён для следующего подключения."
                else ->
                    "Телефон нагрелся: для этой сессии выбран профиль «${thermalTarget.label}»."
            }
            publishStatus(message)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            SessionDiagnostics.event("power broadcast=${intent.action}; interactive=$interactive; dim=$screenOffRequested; panel_visible=${phonePanel.visible}")
            if (intent.action == Intent.ACTION_SCREEN_OFF && !interactive) recoverDisplayAfterSleep()
            // SCREEN_ON is deliberately observational: the user may use the phone.
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || adbSource == null) return
            val state = getSystemService(DisplayManager::class.java).getDisplay(displayId)?.state
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            SessionDiagnostics.event("physical display state=$state; interactive=$interactive")
            if (!interactive && state in listOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)) {
                recoverDisplayAfterSleep()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (usingUsb && intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                publishStatus("USB отключён; завершаю сессию и восстанавливаю экран телефона")
                shutdown(checkLocalAdbAfterUsb = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        com.example.carbridge.HuAppCatalog.warm(applicationContext)
        createChannel()
        ContextCompat.registerReceiver(this, usbReceiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        getSystemService(PowerManager::class.java).addThermalStatusListener(
            java.util.concurrent.Executor { command -> main.post(command); Unit }, thermalStatusListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            ACTION_LAUNCH_DISPLAY_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                    ?: intent.getStringExtra(EXTRA_COMPONENT)?.let(ComponentName::unflattenFromString)?.packageName
                if (adbSource != null && !pkg.isNullOrBlank()) {
                    try {
                        closeApplications()
                        selectedPackage = pkg
                        launchSelectedApp(pkg)
                    } catch (e: IllegalArgumentException) {
                        publishStatus("Некорректное имя приложения: ${e.message}")
                    }
                } else publishStatus("Нет активного ADB-дисплея: сначала подключитесь к ГУ")
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCREEN_POWER -> {
                screenOffRequested = intent.getBooleanExtra(EXTRA_SCREEN_OFF, false)
                val choice = phonePanel.preference(screenOffRequested)
                val source = adbSource
                if (source == null) {
                    phonePanel.complete(choice)
                    publishStatus("Гашение экрана доступно в активной ADB-сессии")
                }
                else if (!screenOffRequested || receivedFrame) applyPhoneChoice(choice,
                    wake = !getSystemService(PowerManager::class.java).isInteractive)
                else {
                    phonePanel.complete(choice)
                    publishStatus("Экран телефона погаснет после первого видеокадра на ГУ")
                }
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PHONE_WAKE, ACTION_PHONE_DIM -> {
                if (adbSource == null) publishStatus("Управление экраном телефона доступно после подключения к ГУ")
                else applyPhoneChoice(phonePanel.explicit(intent.action == ACTION_PHONE_WAKE), wake = true)
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_METADATA_MODE -> {
                val settings = BridgeSettings.read(this)
                connectionSettings = connectionSettings.copy(carLifeNavigation = settings.carLifeNavigation,
                    carLifeArtwork = settings.carLifeArtwork)
                updateCarLifeMetadata()
                SessionDiagnostics.event("CarLife experiments; navigation=${settings.carLifeNavigation}; artwork=${settings.carLifeArtwork}")
                if (link == null && !sessionActive) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUDIO_MODE -> {
                connectionSettings = connectionSettings.copy(usbAudio = BridgeSettings.read(this).usbAudio)
                audioSuspended = false; audioFailures = 0; audioRetryAt = 0L
                updateUsbAudio()
                publishStatus(if (connectionSettings.usbAudio) "USB-звук включён: запустите музыку при активном USB CarLife"
                    else "USB-звук выключен; звук телефона/Bluetooth восстановлен")
                if (link == null && !sessionActive) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY_DPI -> {
                val dpi = intent.getIntExtra(EXTRA_DPI, 0)
                if (!BridgeSettings.isValidAppContentDpi(dpi)) publishStatus("Недопустимый масштаб дисплея ГУ")
                else if (adbSource == null) publishStatus("Масштаб приложений $dpi dpi сохранён; применится при следующем подключении к ГУ")
                else { closeApplications(); adbSource?.applyDisplayDensity(dpi) }
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HARD_KEY -> {
                val key = intent.getIntExtra(EXTRA_KEY_CODE, -1)
                if (key >= 0 && adbSource != null) {
                    when (key) {
                        android.view.KeyEvent.KEYCODE_HOME -> showHomeScreen()
                        android.view.KeyEvent.KEYCODE_APP_SWITCH -> showApplications()
                        android.view.KeyEvent.KEYCODE_BACK -> handleHuBack()
                        else -> { closeApplications(); adbSource?.sendKey(key) }
                    }
                }
                else publishStatus("Управление отдельным дисплеем ещё не запущено")
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_HOME -> {
                showHomeScreen()
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_NAV -> {
                val newDpi = BridgeSettings.read(this).displayDpi
                if (newDpi == connectionSettings.displayDpi) { showNavigation(); return START_NOT_STICKY }
                if (!connectionSettings.dockAutoHide && contentPresentation != null) {
                    publishStatus("Размер постоянной панели применится после переподключения")
                    return START_NOT_STICKY
                }
                connectionSettings = connectionSettings.copy(displayDpi = newDpi)
                navigation.close()
                mediaOverlay.close()
                showNavigation()
                if (link == null) stopSelf()
                return START_NOT_STICKY
            }
        }
        if (link != null) {
            publishStatus("Сессия уже запущена. Можно выбрать другое приложение; для нового подключения нажмите Стоп")
            return START_NOT_STICKY
        }
        val settings = BridgeSettings.read(this)
        connectionSettings = settings
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ADB
        selectedPackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: settings.packageNameLaunch
        screenOffRequested = intent?.getBooleanExtra(EXTRA_SCREEN_OFF, settings.screenOff) ?: settings.screenOff
        phonePanel.preference(screenOffRequested).also(phonePanel::complete)
        startAsForeground()
        if (mode == MODE_ADB && !AdbSession.isConnected() &&
            settings.connectionMode != BridgeSettings.MODE_LOCAL_TCP) {
            publishStatus("ADB не подключён. откройте настройку ADB и выполните активацию режима без Wi-Fi")
            shutdown()
            return START_NOT_STICKY
        }
        resultCode = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        resultData = intent?.getParcelableExtra(EXTRA_DATA)
        if (mode == MODE_MIRROR && (resultData == null || resultCode != android.app.Activity.RESULT_OK)) {
            publishStatus("Захват экрана не разрешён")
            shutdown()
            return START_NOT_STICKY
        }
        receivedFrame = false
        generation++
        cancelUsbRecovery()
        usbRecoveryAttempts = 0
        latestScrcpyConfig = null
        sessionActive = true
        thermalCapAppliedFps = Int.MAX_VALUE
        thermalStatusListener.onThermalStatusChanged(getSystemService(PowerManager::class.java).currentThermalStatus)
        announcedDisplay = false
        audioSuspended = false; audioFailures = 0; audioRetryAt = 0L; audioHuReadyAt = Long.MAX_VALUE
        main.removeCallbacks(audioPoll); main.post(audioPoll)
        OfflineDisconnectMonitor.cancel()
        SessionDiagnostics.start(applicationContext,
            "source=$mode; carlife=${intent?.getStringExtra(EXTRA_TRANSPORT) ?: TRANSPORT_USB}; " +
                "adb=${settings.connectionMode}; app=$selectedPackage; " +
                "ui_dpi=${settings.displayDpi}; app_content_dpi=${settings.appContentDpi}; " +
                "profile=${connectionSettings.videoProfile}; dim=$screenOffRequested; audio=${if (settings.usbAudio) "usb_experiment" else "phone_or_bluetooth"}")
        val stub = intent?.getBooleanExtra(EXTRA_STUB, false) ?: false
        val transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: TRANSPORT_USB
        usingUsb = !stub && transport == TRANSPORT_USB
        usingWifiCarLife = !stub && transport == TRANSPORT_WIFI
        Log.i(TAG, "mode=${if (stub) "STUB" else transport}; source=$mode; adb=${settings.connectionMode}; app=$selectedPackage")
        link = when {
            stub -> StubHuLink()
            usingWifiCarLife -> openWifiLink()
            else -> openUsbLink()
        }
        if (link == null) { shutdown(); return START_NOT_STICKY }
        publishStatus("CarLife: ожидаю параметры видео от ГУ")
        startHuLink(link!!)
        return START_NOT_STICKY
    }

    private fun startHuLink(activeLink: HuLink) {
        val activeGeneration = generation
        latestScrcpyConfig?.let(activeLink::sendConfig)
        activeLink.start(object : HuLink.Listener {
            private fun active(action: () -> Unit) {
                main.post {
                    if (generation == activeGeneration && link === activeLink) action()
                }
            }
            override fun onHuReady(width: Int, height: Int) = active {
                this@CaptureService.onHuReady(width, height)
            }
            override fun onTouch(event: HuLink.TouchEvent) = active {
                this@CaptureService.onTouch(event)
            }
            override fun onHardKey(code: Int) = active { this@CaptureService.onHardKey(code) }
            override fun onAudioSuspend() = active {
                audioSuspended = true
                stopUsbAudio("HU selected another audio source or paused")
            }
            override fun onAudioResume() = active {
                audioSuspended = false
                updateUsbAudio()
            }
            override fun onTransportBackpressure(queuedBytes: Int) = active {
                adbSource?.diagnoseDisplay("CarLife backpressure bytes=$queuedBytes", force = true)
            }
            override fun onRequestKeyframe() = active {
                adbSource?.requestKeyframe()
                mirror?.requestKeyframe()
            }
            override fun onHuDisconnected(reason: String) = active {
                handleCarLifeDisconnect(activeLink, reason)
            }
        })
    }

    private fun playbackActive(): Boolean {
        val audio = getSystemService(AudioManager::class.java)
        if (audio.mode != AudioManager.MODE_NORMAL) return false
        val sessionPlaying = runCatching {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(
                ComponentName(this, HuMediaNotificationListener::class.java)).any {
                it.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING)
            }
        }.getOrDefault(false)
        return sessionPlaying || audio.isMusicActive ||
            (usbAudioSource?.let { SystemClock.elapsedRealtime() - it.lastAudibleMs < 2500 } == true)
    }

    private fun updateUsbAudio() {
        val activeLink = link
        val now = SystemClock.elapsedRealtime()
        val wanted = connectionSettings.usbAudio && usingUsb && mode == MODE_ADB && receivedFrame &&
            Build.VERSION.SDK_INT >= 31 && !audioSuspended && now >= audioHuReadyAt && activeLink != null
        if (!wanted || !playbackActive()) {
            if (usbAudioSource != null) stopUsbAudio(if (wanted) "playback idle or call" else "disabled or transport unavailable")
            return
        }
        if (usbAudioSource != null || now < audioRetryAt || activeLink == null) return
        val currentGeneration = generation
        var source: ScrcpyAudioSource? = null
        source = ScrcpyAudioSource(applicationContext, object : ScrcpyAudioSource.Listener {
            override fun onFormatReady(): Boolean = synchronized(usbAudioLock) {
                generation == currentGeneration && usbAudioSource === source && link === activeLink && activeLink.startMediaAudio()
            }
            override fun onPcm(pcm: ByteArray) {
                synchronized(usbAudioLock) {
                    if (generation == currentGeneration && usbAudioSource === source && link === activeLink)
                        activeLink.sendMediaPcm(pcm)
                }
            }
            override fun onError(message: String) {
                if (usbAudioSource !== source || generation != currentGeneration) return
                stopUsbAudio("capture failed: $message")
                audioFailures++
                audioRetryAt = SystemClock.elapsedRealtime() + 10000
                if (audioFailures >= 3) audioSuspended = true
                SessionDiagnostics.event("USB audio failed; attempts=$audioFailures; video retained; error=$message")
                publishStatus("USB-звук: $message. Видео продолжает работать. " +
                    if (audioSuspended) "Повторить — выключите и включите USB-звук в настройках." else "Повтор через 10 секунд.")
            }
        })
        usbAudioSource = source
        usbAudioLink = activeLink
        SessionDiagnostics.event("USB audio starting; independent shell output capture; playback active")
        source.start()
    }

    private fun stopUsbAudio(reason: String) {
        val source = synchronized(usbAudioLock) {
            usbAudioSource.also {
                usbAudioSource = null
                usbAudioLink?.stopMediaAudio()
                usbAudioLink = null
            }
        }
        source?.close()
        if (source != null) SessionDiagnostics.event("USB audio stopped; reason=$reason; local routing released; video retained")
    }

    private fun handleCarLifeDisconnect(activeLink: HuLink, reason: String) {
        if (link !== activeLink || !sessionActive) return
        audioHuReadyAt = Long.MAX_VALUE
        stopUsbAudio("CarLife disconnected")
        val transport = if (usingWifiCarLife) "wifi" else if (usingUsb) "usb" else "unknown"
        SessionDiagnostics.event("CaptureService received HU disconnect; transport=$transport; reason=$reason")
        adbSource?.diagnoseDisplay("CarLife disconnect: $reason", force = true)
        if (usingUsb && mode == MODE_ADB && isRecoverableUsbDisconnect(reason)) {
            beginUsbRecovery(activeLink, reason)
            return
        }
        publishStatus(if (usingWifiCarLife) "Wi-Fi CarLife разорвано: ${reason.take(140)}" else
            "USB CarLife разорвано: ${reason.take(140)}")
        shutdown(checkLocalAdbAfterUsb = usingUsb)
    }

    private fun isRecoverableUsbDisconnect(reason: String): Boolean {
        val normalized = reason.lowercase(java.util.Locale.ROOT)
        return normalized.startsWith("writer stalled") || normalized.startsWith("write exception") ||
            normalized.startsWith("read exception") || normalized == "transport eof" ||
            normalized == "carlife input closed"
    }

    /** Reopen only the failed CarLife USB transport; keep the scrcpy display and app alive. */
    private fun beginUsbRecovery(activeLink: HuLink, reason: String) {
        if (link !== activeLink || !sessionActive) return
        link = null // Ignore callbacks from the closed transport while retries are pending.
        activeLink.stop()
        if (usbRecoveryAttempts >= MAX_USB_RECOVERY_ATTEMPTS) {
            SessionDiagnostics.event("USB CarLife recovery exhausted after $usbRecoveryAttempts attempts; reason=$reason")
            publishStatus("USB CarLife не восстановилось после $usbRecoveryAttempts попыток. Останавливаю сеанс.")
            shutdown(checkLocalAdbAfterUsb = true)
            return
        }
        usbRecoveryStableRunnable?.let(main::removeCallbacks)
        usbRecoveryStableRunnable = null
        SessionDiagnostics.event("USB CarLife recovery begins; preserving display=${AppDisplayState.displayId}; reason=$reason")
        SessionDiagnostics.stop()
        scheduleUsbRecovery(reason)
    }

    private fun scheduleUsbRecovery(previousReason: String) {
        if (!sessionActive || link != null || usbRecoveryRunnable != null) return
        if (usbRecoveryAttempts >= MAX_USB_RECOVERY_ATTEMPTS) {
            publishStatus("USB CarLife не восстановилось после $usbRecoveryAttempts попыток. Останавливаю сеанс.")
            shutdown(checkLocalAdbAfterUsb = true)
            return
        }
        usbRecoveryAttempts++
        val attempt = usbRecoveryAttempts
        val delayMs = USB_RECOVERY_DELAYS_MS[attempt - 1]
        val token = generation
        SessionDiagnostics.start(applicationContext,
            "source=$mode; carlife=usb; adb=${connectionSettings.connectionMode}; app=$selectedPackage; " +
                "recovery_attempt=$attempt/$MAX_USB_RECOVERY_ATTEMPTS; previous_disconnect=${previousReason.take(100)}")
        publishStatus("USB CarLife прервалось. Повторное подключение $attempt/$MAX_USB_RECOVERY_ATTEMPTS через ${delayMs / 1000} с; экран сохраняю.")
        val task = Runnable {
            usbRecoveryRunnable = null
            if (!sessionActive || generation != token || link != null) return@Runnable
            val newLink = openUsbLink()
            if (newLink == null) {
                SessionDiagnostics.event("USB CarLife recovery attempt=$attempt failed: accessory unavailable")
                scheduleUsbRecovery("повторное открытие USB-аксессуара не удалось")
                return@Runnable
            }
            link = newLink
            usingUsb = true
            usingWifiCarLife = false
            SessionDiagnostics.event("USB CarLife recovery attempt=$attempt transport opened; preserving display=${AppDisplayState.displayId}")
            startHuLink(newLink)
        }
        usbRecoveryRunnable = task
        main.postDelayed(task, delayMs)
    }

    private fun cancelUsbRecovery() {
        usbRecoveryRunnable?.let(main::removeCallbacks)
        usbRecoveryRunnable = null
        usbRecoveryStableRunnable?.let(main::removeCallbacks)
        usbRecoveryStableRunnable = null
    }

    private fun scheduleUsbRecoveryCounterReset(activeLink: HuLink) {
        if (!usingUsb || usbRecoveryAttempts == 0) return
        usbRecoveryStableRunnable?.let(main::removeCallbacks)
        val token = generation
        val task = Runnable {
            if (sessionActive && generation == token && link === activeLink) {
                usbRecoveryAttempts = 0
                usbRecoveryStableRunnable = null
                SessionDiagnostics.event("USB CarLife stable for 60s; recovery counter reset")
            }
        }
        usbRecoveryStableRunnable = task
        main.postDelayed(task, USB_RECOVERY_STABLE_MS)
    }

    private fun openWifiLink(): HuLink? = try {
        val wifi = CarLifeWifiTransport(applicationContext, ::publishStatus)
        wifi.start()
        CarLifeHuLink(wifi, defaultFps = if (mode == MODE_ADB)
            VideoProfile.fromPreference(connectionSettings.videoProfile).maxFps else 30)
    } catch (error: Exception) {
        Log.e(TAG, "Ошибка открытия Wi-Fi CarLife", error)
        publishStatus("Ошибка Wi-Fi CarLife: ${error.message ?: error.javaClass.simpleName}")
        null
    }

    private fun openUsbLink(): HuLink? = try {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val accessory = manager.accessoryList?.firstOrNull()
        if (accessory == null) {
            publishStatus("USB-аксессуар не найден: включите CarLife на ГУ и подключите кабель")
            null
        } else if (!manager.hasPermission(accessory)) {
            publishStatus("Автоподключение остановлено: Android не выдал CarBridge разрешение на USB-аксессуар. Разрешите доступ и подключите кабель заново.")
            null
        } else {
            val fd = manager.openAccessory(accessory)
            if (fd == null) publishStatus("Не удалось открыть USB-аксессуар")
            else Log.i(TAG, "аксессуар открыт: ${accessory.manufacturer} / ${accessory.model} / ${accessory.version}")
            fd?.let { CarLifeHuLink(it, defaultFps = if (mode == MODE_ADB)
                VideoProfile.fromPreference(connectionSettings.videoProfile).maxFps else 30) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Ошибка открытия USB", e)
        publishStatus("Ошибка открытия USB: ${e.message}")
        null
    }

    override fun onHuReady(width: Int, height: Int) {
        val session = link ?: return
        main.post {
            if (link !== session || mirror != null) return@post
            audioHuReadyAt = SystemClock.elapsedRealtime() + 750
            val existingSource = adbSource
            if (mode == MODE_ADB && existingSource != null) {
                if (width == huWidth && height == huHeight && AppDisplayState.displayId > 0) {
                    latestScrcpyConfig?.let(session::sendConfig)
                    SessionDiagnostics.event("CarLife reconnected; reusing virtual display=${AppDisplayState.displayId} ${huWidth}x$huHeight")
                    publishStatus("CarLife восстановлено. Виртуальный экран и приложение сохранены.")
                    existingSource.requestKeyframe()
                    scheduleUsbRecoveryCounterReset(session)
                    return@post
                }
                SessionDiagnostics.event("CarLife reconnected with changed video size ${width}x$height; recreating virtual display")
                contentPresentation?.close(); contentPresentation = null; applicationDisplayId = -1
                navigation.close()
                mediaOverlay.close()
                adbSource = null
                AppDisplayState.displayId = -1
                announcedDisplay = false
                receivedFrame = false
                existingSource.stop()
                latestScrcpyConfig = null
            } else if (adbSource != null) {
                return@post
            }
            huWidth = width; huHeight = height
            val currentGeneration = generation
            val sourceSettings = connectionSettings
            // These belong to this source, even if an old callback races a reconnect.
            val acceptedDimensions = AtomicBoolean(false)
            val firstFramePosted = AtomicBoolean(false)
            try {
                if (mode == MODE_ADB) {
                    val profile = VideoProfile.fromPreference(sourceSettings.videoProfile)
                    val logicalSize = when (sourceSettings.logicalDisplayMode) {
                        BridgeSettings.DISPLAY_MODE_1920_1080 ->
                            if (width == 1280 && height == 720) 1920 to 1080 else width to height
                        BridgeSettings.DISPLAY_MODE_1600_900 ->
                            if (width % 4 == 0 && height % 4 == 0 &&
                                width.toLong() * 5L / 4L <= 65535L && height.toLong() * 5L / 4L <= 65535L)
                                width * 5 / 4 to height * 5 / 4 else width to height
                        else -> width to height
                    }
                    val logicalWidth = logicalSize.first
                    val logicalHeight = logicalSize.second
                    val canScaleLogicalDisplay = logicalWidth != width || logicalHeight != height
                    val maxVideoSize = if (canScaleLogicalDisplay) maxOf(width, height) else null
                    if (sourceSettings.logicalDisplayMode != BridgeSettings.DISPLAY_MODE_HU && !canScaleLogicalDisplay) {
                        SessionDiagnostics.event("Logical display mode=${sourceSettings.logicalDisplayMode} not applied: unsupported HU frame=${width}x$height")
                        publishStatus("Экспериментальный размер не применён: режим ГУ ${width}×${height} несовместим с выбранным профилем")
                    }
                    SessionDiagnostics.event("HU video=${width}x$height; ui_dpi=${sourceSettings.displayDpi}; " +
                        "virtual_display=${logicalWidth}x$logicalHeight; display_mode=${sourceSettings.logicalDisplayMode}; " +
                        "max_video_size=${maxVideoSize ?: "none"}; " +
                        "app_content_dpi=${sourceSettings.appContentDpi}; " +
                        "fps=${profile.maxFps}; bitrate=${profile.bitRate}")
                    var sourceForCallbacks: ScrcpySource? = null
                    val source = ScrcpySource(applicationContext, logicalWidth, logicalHeight, sourceSettings.appContentDpi, null,
                        object : ScrcpySource.Listener {
                            override fun onConfig(csd: ByteArray) {
                                if (generation == currentGeneration && adbSource === sourceForCallbacks) {
                                    latestScrcpyConfig = csd.copyOf()
                                    link?.sendConfig(csd)
                                }
                            }
                            override fun onFrame(data: ByteArray, key: Boolean, ptsUs: Long) {
                                if (generation != currentGeneration || adbSource !== sourceForCallbacks || !acceptedDimensions.get()) return
                                val activeLink = link ?: return
                                activeLink.sendVideoFrame(data, key, ptsUs)
                                SessionDiagnostics.frame(data.size, key)
                                if (firstFramePosted.compareAndSet(false, true)) {
                                    main.post firstFrameUi@{
                                        if (generation != currentGeneration || link !== session) return@firstFrameUi
                                        receivedFrame = true
                                        publishStatus("Видео на ГУ: ${huWidth}x$huHeight. Приложение $selectedPackage")
                                        adbSource?.setPhysicalScreenOn(phonePanel.visible)
                                        publishPhonePanelState()
                                    }
                                }
                            }
                            override fun onReady(width: Int, height: Int, displayId: Int?) {
                                if (generation != currentGeneration || link !== session) return
                                acceptedDimensions.set(width == this@CaptureService.huWidth && height == this@CaptureService.huHeight)
                                main.post displayReadyUi@{
                                    if (generation != currentGeneration || link !== session) return@displayReadyUi
                                    if (width != huWidth || height != huHeight) {
                                        publishStatus("Формат $width x $height не совпадает с ГУ ${huWidth}x$huHeight; сессия остановлена")
                                        shutdown()
                                        return@displayReadyUi
                                    }
                                    AppDisplayState.displayId = displayId ?: -1
                                    scheduleUsbRecoveryCounterReset(session)
                                    var launchInitialApp = false
                                    if (!announcedDisplay) {
                                        announcedDisplay = true
                                        launchInitialApp = true
                                        publishStatus("Отдельный дисплей $width x $height создан; запускаю $selectedPackage")
                                        sendBroadcast(Intent(ACTION_DISPLAY_READY).setPackage(packageName)
                                            .putExtra(EXTRA_DISPLAY_ID, displayId ?: -1))
                                    }
                                    if (launchInitialApp && displayId != null && displayId > 0) {
                                        showNavigation()
                                        createApplicationArea(displayId, sourceForCallbacks ?: return@displayReadyUi)
                                    } else showNavigation()
                                }
                            }
                            override fun onStatus(message: String) {
                                if (generation == currentGeneration) main.post {
                                    if (generation == currentGeneration) publishStatus(message)
                                }
                            }
                            override fun onError(message: String, error: Throwable?) {
                                Log.e(TAG, message, error)
                                main.post {
                                    if (generation == currentGeneration && link === session) {
                                        publishStatus("ADB-дисплей: $message")
                                        shutdown()
                                    }
                                }
                            }
                            override fun onDisplayDensityApplied(dpi: Int) {
                                main.post {
                                    if (generation == currentGeneration && link === session) {
                                        connectionSettings = connectionSettings.copy(appContentDpi = dpi)
                                        navigation.close()
                                        mediaOverlay.close()
                                        showNavigation()
                                    }
                                }
                            }
                            override fun onDisplayEmpty(displayId: Int) {
                                main.post {
                                    if (generation == currentGeneration && applicationDisplayId == displayId && homePresentation?.isShowing != true) {
                                        showHomeScreen()
                                    }
                                }
                            }
                            override fun onForegroundApp(packageName: String?) {
                                main.post {
                                    if (generation == currentGeneration && link === session) {
                                        navigation.setForegroundPackage(if (homePresentation?.isShowing == true)
                                            applicationContext.packageName else packageName)
                                    }
                                }
                            }
                        }, beforeStart = {
                            ensureAdbConnection(sourceSettings, currentGeneration, session)
                        }, videoProfile = profile, maxVideoSize = maxVideoSize,
                        touchSourceWidth = width, touchSourceHeight = height)
                    sourceForCallbacks = source
                    adbSource = source
                    publishStatus("Создаю отдельный ADB-дисплей ${logicalWidth}x$logicalHeight; видео для ГУ ${width}x$height")
                    source.start()
                } else {
                    val data = resultData ?: return@post
                    val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = manager.getMediaProjection(resultCode, data)
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
                    mapper = TouchMapper(width, height, metrics.widthPixels, metrics.heightPixels)
                    val source = ScreenSource(projection, width, height, metrics.densityDpi, listener = this)
                    mirror = source
                    source.start()
                    publishStatus("Зеркало ${width}x$height запущено")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Источник видео не запустился", e)
                publishStatus("Источник видео не запустился: ${e.message}")
                shutdown()
            }
        }
    }

    override fun onTouch(event: HuLink.TouchEvent) {
        val session = link ?: return
        if (mode == MODE_ADB) {
            adbSource?.sendTouch(event)
        } else {
            val (px, py) = if (event.x < 0 || event.y < 0) -1f to -1f else
                mapper?.map(event.x, event.y) ?: return
            main.post {
                if (link !== session) return@post
                val svc = AccessibilityTouchService.instance
                if (svc == null) Log.w(TAG, "Для тача зеркала включите Accessibility")
                else {
                    svc.targetDisplayId = android.view.Display.DEFAULT_DISPLAY
                    svc.onHuTouch(event.action, px, py)
                }
            }
        }
    }

    private var lastWheelCode = -1
    private var lastWheelAt = 0L
    override fun onHardKey(code: Int) {
        val token = generation
        main.post {
            if (!sessionActive || generation != token) return@post
            val now = android.os.SystemClock.elapsedRealtime()
            val repeatDelay = if (code == 33) 1200L else 250L
            if (code == lastWheelCode && now - lastWheelAt < repeatDelay) {
                lastWheelAt = now
                return@post
            }
            // Ignore OEM markers without letting them reset the debounce of a real button.
            if (code !in setOf(15, 16, 31, 32, 33, 14, 30)) {
                if (now - lastUnknownWheelAt > 5000) {
                    lastUnknownWheelAt = now
                    SessionDiagnostics.event("HU unhandled hard key=$code")
                }
                return@post
            }
            lastWheelCode = code; lastWheelAt = now
            SessionDiagnostics.event("HU hard key=$code; action dispatch; display=${AppDisplayState.displayId}")
            when (code) {
                15, 16, 31, 32 -> mediaOverlay.dispatchWheelMedia(code)
                33 -> { dismissHomePresentation(); adbSource?.launchVoiceAssistant() }
                14 -> handleHuBack()
                30 -> showHomeScreen()
            }
        }
    }
    private var lastUnknownWheelAt = -10000L
    override fun onRequestKeyframe() { adbSource?.requestKeyframe(); main.post { mirror?.requestKeyframe() } }
    override fun onHuDisconnected(reason: String) { main.post {
        publishStatus("CarLife отключилось: ${reason.take(140)}")
        shutdown(checkLocalAdbAfterUsb = usingUsb)
    } }
    override fun onConfig(csd: ByteArray) { link?.sendConfig(csd) }
    override fun onFrame(data: ByteArray, keyFrame: Boolean, ptsUs: Long) { link?.sendVideoFrame(data, keyFrame, ptsUs) }

    private fun recoverDisplayAfterSleep() {
        if (adbSource == null || mode != MODE_ADB || !receivedFrame || link == null) return
        val power = getSystemService(PowerManager::class.java)
        if (power.isInteractive) return
        val choice = phonePanel.sleep() ?: return
        SessionDiagnostics.event("Hardware sleep: choose physical panel ${if (choice.on) "ON" else "OFF"}; revision=${choice.revision}")
        applyPhoneChoice(choice, wake = true)
    }

    /** All callers run on main. The saved startup preference is unchanged by hardware presses. */
    private fun applyPhoneChoice(choice: PhonePanelState.Choice, wake: Boolean) {
        val source = adbSource ?: run { phonePanel.complete(choice); return }
        publishPhonePanelState()
        recoveryWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        if (!wake || !receivedFrame) {
            source.setPhysicalScreenOn(choice.on)
            phonePanel.complete(choice)
            return
        }
        val token = generation
        val power = getSystemService(PowerManager::class.java)
        // Only bridge a real sleep transition. No permanent CPU/display wake lock.
        runCatching {
            val lock = recoveryWakeLock ?: power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "CarBridge:screen-recovery"
            ).apply { setReferenceCounted(false) }.also { recoveryWakeLock = it }
            lock.acquire(6_000L)
        }.onFailure { Log.w(TAG, "Short recovery wake lock unavailable", it) }
        source.recoverFromDeviceSleep(choice.on) {
            main.post {
                if (generation == token && adbSource === source && phonePanel.isCurrent(choice)) {
                    phonePanel.complete(choice)
                    recoveryWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
                }
            }
        }
    }

    fun isPhoneVisible(): Boolean = phonePanel.visible

    private fun publishPhonePanelState() {
        navigation.setPhoneVisible(phonePanel.visible)
        SessionDiagnostics.event("Physical panel choice visible=${phonePanel.visible}; startup_dim=$screenOffRequested")
        sendBroadcast(Intent(ACTION_PHONE_STATE).setPackage(packageName)
            .putExtra(EXTRA_PHONE_VISIBLE, phonePanel.visible))
    }

    private fun handleHuBack() {
        if (navigation.isApplicationPickerOpen()) {
            closeApplications()
            return
        }
        closeApplications()
        if (homePresentation?.isShowing == true) homePresentation?.handleBack()
        else adbSource?.let { source ->
            source.sendKey(android.view.KeyEvent.KEYCODE_BACK)
            main.postDelayed({ if (adbSource === source) source.diagnoseDisplay("Back on HU", force = true) }, 600L)
        }
    }

    private fun showNavigation() {
        if (mode != MODE_ADB || adbSource == null) return
        val id = AppDisplayState.displayId
        val shown = navigation.show(id, connectionSettings.displayDpi, huWidth, connectionSettings.dockAutoHide)
        navigation.setPhoneVisible(phonePanel.visible)
        val mediaShown = mediaOverlay.show(id, connectionSettings.displayDpi, huWidth,
            connectionSettings.resizeForMusic, !connectionSettings.dockAutoHide)
        navigation.setMediaVisible(mediaOverlay.isVisible())
        contentPresentation?.setMusicLayout(mediaOverlay.isVisible() && connectionSettings.resizeForMusic, mediaOverlay.isOnRight())
        if (!shown) SessionDiagnostics.event("Independent HU navigation requires overlay permission and a valid display id")
        if (!mediaShown) SessionDiagnostics.event("HU media card requires overlay permission and an active display")
    }

    private fun createApplicationArea(shellId: Int, source: ScrcpySource) {
        if (contentPresentation != null) return
        val display = getSystemService(DisplayManager::class.java).getDisplay(shellId) ?: return
        val metrics = createDisplayContext(display).resources.displayMetrics
        val density = (connectionSettings.displayDpi / 160f * metrics.widthPixels / huWidth)
            .coerceAtMost(metrics.widthPixels / 700f)
        val inset = if (connectionSettings.dockAutoHide) 0 else
            (com.example.carbridge.HuDockDimensions.HEIGHT_DP * density).roundToInt()
        try {
            val presentation = HuContentPresentation(applicationContext, display, connectionSettings.appContentDpi, inset,
                com.example.carbridge.HuWindowCorners.radiusPx(density),
                onReady = { id, input ->
                    if (adbSource === source && AppDisplayState.displayId == shellId) {
                        applicationDisplayId = id
                        source.attachApplicationDisplay(id, input)
                        showNavigation()
                        launchSelectedApp(selectedPackage)
                    } else input.close()
                }, onFailure = { error ->
                    if (adbSource === source) {
                        SessionDiagnostics.event("HU content area failed: ${error.take(180)}")
                        publishStatus("Не удалось создать окно приложения: ${error.take(160)}. Переподключите CarBridge.")
                        shutdown()
                    }
                })
            contentPresentation = presentation
            presentation.show()
        } catch (e: Exception) {
            publishStatus("Не удалось создать окно: ${e.message}")
            shutdown()
        }
    }

    private fun toggleMediaCard() {
        val visible = mediaOverlay.toggleVisibility()
        navigation.setMediaVisible(visible)
        SessionDiagnostics.event("HU media card visible=$visible")
    }

    private fun setMediaCardVisible(visible: Boolean) {
        mediaOverlay.setVisible(visible)
        navigation.setMediaVisible(visible)
        SessionDiagnostics.event("HU media drawer visible=$visible; resize=${connectionSettings.resizeForMusic}")
    }

    /** Show CarBridge's own launcher on the scrcpy display instead of asking Android
     * to start a system HOME activity on an app-owned virtual display. */
    private fun openNotificationOnHu(item: android.service.notification.StatusBarNotification) {
        val target=applicationDisplayId
        val intent=item.notification.contentIntent
        if(target<=0 || intent==null || !intent.isActivity) { launchSelectedApp(item.packageName); return }
        try {
            closeApplications()
            homePresentation?.dismiss(); homePresentation=null
            intent.send(this,0,null,null,null,null,ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
            adbSource?.watchRequestedLaunch(item.packageName)
            navigation.setForegroundPackage(item.packageName)
            SessionDiagnostics.event("HU notification activity sent; package=${item.packageName}; display=$target")
        } catch(error: Exception) {
            SessionDiagnostics.event("HU notification activity failed: ${error.javaClass.simpleName}")
            launchSelectedApp(item.packageName)
        }
    }

    private fun showHomeScreen() {
        adbSource?.cancelLaunchPlacement()
        closeApplications()
        setMediaCardVisible(false)
        val source = adbSource ?: run {
            SessionDiagnostics.event("HU home request ignored; ADB display is not active")
            return
        }
        val token = generation
        source.cancelMapZoom {
            main.post {
                if (generation != token || adbSource !== source || closedService()) return@post
                showHomePresentation(source, AppDisplayState.displayId)
            }
        }
    }

    private fun showHomePresentation(source: ScrcpySource, targetDisplayId: Int) {
        SessionDiagnostics.event("HU home requested; display=$targetDisplayId; app-owned launcher")
        val display = if (targetDisplayId > 0) {
            getSystemService(DisplayManager::class.java).getDisplay(targetDisplayId)
        } else null
        if (display == null || !display.isValid) {
            SessionDiagnostics.event("HU home failed; virtual display unavailable id=$targetDisplayId")
            publishStatus("Главный экран ГУ недоступен: виртуальный дисплей не готов")
            return
        }
        homePresentation?.takeIf { it.display.displayId == targetDisplayId && it.isShowing }?.let {
            it.showHome()
            SessionDiagnostics.event("HU launcher returned to app list; display=$targetDisplayId")
            return
        }
        dismissHomePresentation()
        try {
            val presentation = HuHomePresentation(applicationContext, display, connectionSettings.displayDpi, huWidth,
                reserveDock = !connectionSettings.dockAutoHide) { component, selectedDisplayId ->
                main.post {
                    if (adbSource !== source || AppDisplayState.displayId != selectedDisplayId || closedService()) {
                        SessionDiagnostics.event("HU launcher ignored stale app selection; display=$selectedDisplayId")
                        return@post
                    }
                    SessionDiagnostics.event("HU launcher app selected=${component.flattenToShortString()}; display=$selectedDisplayId")
                    dismissHomePresentation()
                    selectedPackage = component.packageName
                    BridgeSettings.choosePackage(applicationContext, selectedPackage)
                    launchSelectedApp(selectedPackage, component)
                }
            }
            homePresentation = presentation
            presentation.setMusicLayout(mediaOverlay.isVisible() && connectionSettings.resizeForMusic, mediaOverlay.isOnRight())
            presentation.show()
            navigation.setForegroundPackage(applicationContext.packageName)
            source.diagnoseDisplay("CarBridge home shown", force = true)
            SessionDiagnostics.event("HU app-owned launcher shown; display=$targetDisplayId")
        } catch (error: Exception) {
            dismissHomePresentation()
            SessionDiagnostics.event("HU app-owned launcher failed: ${error.javaClass.simpleName}: ${error.message}")
            publishStatus("Не удалось открыть главный экран ГУ: ${error.message?.take(120) ?: error.javaClass.simpleName}")
        }
    }

    private fun dismissHomePresentation() {
        val old = homePresentation
        homePresentation = null
        if (old != null) runCatching { old.dismiss() }
    }

    /** Launch the selected app full-screen on the HU's scrcpy display. */
    private fun launchSelectedApp(packageName: String, launcherComponent: ComponentName? = null) {
        val source = adbSource ?: return
        dismissHomePresentation()
        selectedPackage = packageName
        BridgeSettings.choosePackage(applicationContext, packageName)
        source.launchApp(packageName, launcherComponent) { success, details ->
            main.post {
                if (adbSource !== source || closedService()) return@post
                SessionDiagnostics.event("HU app launch fullscreen success=$success; $details")
                if (success) {
                    com.example.carbridge.HuLauncherStore.record(applicationContext, packageName)
                    navigation.setForegroundPackage(packageName)
                }
                publishStatus(if (success) "Приложение открыто на весь виртуальный экран" else
                    "Не удалось открыть приложение на ГУ: ${details.take(180)}")
                if (!success) showHomePresentation(source, AppDisplayState.displayId)
            }
        }
    }

    private fun stopHuApplication(packageName: String) {
        val source = adbSource ?: return
        val wasActive = navigation.isForegroundPackage(packageName)
        source.stopApp(packageName) { success ->
            main.post {
                if (adbSource !== source || closedService()) return@post
                if (success) {
                    navigation.forgetApplication(packageName)
                    if (wasActive) showHomeScreen()
                }
                publishStatus(if (success) "Приложение остановлено на ГУ и телефоне" else "Не удалось остановить приложение")
            }
        }
    }

    private fun closedService(): Boolean = !sessionActive

    /** Reopen the last media source on the phone without replacing navigation on the HU display. */
    private fun openMediaSourceOnPhone(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent == null) {
            publishStatus("Не удалось открыть музыкальное приложение $packageName")
            return
        }
        try {
            startActivity(intent, ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
            publishStatus("Открываю ${packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))} на телефоне для восстановления медиасессии")
        } catch (error: Exception) {
            publishStatus("Не удалось повторно открыть музыку: ${error.message?.take(120) ?: error.javaClass.simpleName}")
        }
    }

    private fun showApplications() {
        showHomeScreen()
    }

    private fun closeApplications() {
        openingApplicationPicker = false
        pickerRevision++
        navigation.dismissApplications()
    }

    /** Runs on scrcpy's worker: USB/AOA negotiation may have restarted adbd. */
    private fun ensureAdbConnection(settings: BridgeSettings.Config, token: Int, session: HuLink) {
        val offline = settings.connectionMode == BridgeSettings.MODE_LOCAL_TCP
        val endpointHost = if (offline) "127.0.0.1" else settings.host
        val endpointPort = if (offline) settings.offlinePort else settings.connectPort
        val endpointMode = if (offline) AdbConnectionMode.LOCAL_TCP else AdbConnectionMode.TLS
        if (endpointPort !in 1..65535) throw IOException("Не задан порт ADB. Выполните активацию")
        if (offline) {
            val health = OfflineAdbHealth.checkLocal(applicationContext, endpointPort,
                cancelled = { generation != token || link !== session },
                report = { if (generation == token && link === session) SessionDiagnostics.event(it) })
            if (!health.connected) throw IOException(health.summary)
            return
        }
        val deadline = SystemClock.elapsedRealtime() + 12_000L
        var lastError: Exception? = null
        do {
            if (generation != token || link !== session) throw IOException("Сессия ГУ отменена")
            val remainingBeforeProbe = deadline - SystemClock.elapsedRealtime()
            if (remainingBeforeProbe <= 0L) break
            if (AdbSession.probeConnected(endpointHost, endpointPort, endpointMode,
                    minOf(2000L, remainingBeforeProbe))) return
            try {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, 10_000L)
                val connected = if (offline) AdbSession.connectLocal(applicationContext, endpointPort, remaining)
                    else AdbSession.connect(applicationContext, endpointHost, endpointPort, endpointMode, remaining)
                if (connected && generation == token && link === session) return
            } catch (error: Exception) {
                lastError = error
            }
            if (!offline) break
            Thread.sleep(250L)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IOException(if (offline)
            "Локальный ADB недоступен. Проверьте «Отладку по USB»; после перезагрузки нужна активация"
            else "Беспроводной ADB недоступен. Проверьте Wi-Fi и порт подключения", lastError)
    }

    private fun publishStatus(message: String) {
        Log.i(TAG, message)
        SessionDiagnostics.event(message)
        sendBroadcast(Intent(ACTION_DISPLAY_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
        val notification = getSystemService(NotificationManager::class.java)
        notification.notify(1, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CarBridge Offline")
            .setContentText(message)
            .setOngoing(true).build())
    }

    private fun shutdown(checkLocalAdbAfterUsb: Boolean = false) {
        val wasActive = sessionActive
        sessionActive = false
        main.removeCallbacks(audioPoll)
        carLifeNavigation.update(null, false)
        carLifeMetadata.update(null)
        audioHuReadyAt = Long.MAX_VALUE
        stopUsbAudio("session shutdown")
        cancelUsbRecovery()
        if (wasActive) generation++
        val tail = if (wasActive && checkLocalAdbAfterUsb) SessionDiagnostics.captureTailRecorder() else null
        val checkPort = connectionSettings.offlinePort.takeIf {
            wasActive && checkLocalAdbAfterUsb && usingUsb && mode == MODE_ADB &&
                connectionSettings.connectionMode == BridgeSettings.MODE_LOCAL_TCP && it in 1..65535
        }
        closeApplications()
        dismissHomePresentation()
        contentPresentation?.close(); contentPresentation = null; applicationDisplayId = -1
        navigation.close()
        mediaOverlay.close()
        recoveryWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        recoveryWakeLock = null
        phonePanel.stop()
        val oldLink = link; link = null
        val oldMirror = mirror; mirror = null
        val oldAdb = adbSource; adbSource = null
        oldLink?.stop()
        usingWifiCarLife = false
        usingUsb = false
        oldAdb?.stop()
        oldMirror?.stop()
        mapper = null
        resultData = null
        receivedFrame = false
        AppDisplayState.displayId = -1
        latestScrcpyConfig = null
        SessionDiagnostics.stop()
        if (checkPort != null && tail != null) OfflineDisconnectMonitor.start(applicationContext, checkPort, tail)
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(1)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        unregisterReceiver(screenReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalStatusListener)
        shutdown()
        carLifeMetadata.close()
        super.onDestroy()
    }

    private fun startAsForeground() {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CarBridge Offline")
            .setContentText("Подключение к ГУ").setOngoing(true).build()
        val type = if (mode == MODE_MIRROR) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (type != 0) startForeground(1, notification, type) else startForeground(1, notification)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarBridge Offline", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        const val ACTION_STOP = "carbridge.STOP"
        const val ACTION_LAUNCH_DISPLAY_APP = "carbridge.LAUNCH_DISPLAY_APP"
        const val ACTION_HARD_KEY = "carbridge.HARD_KEY"
        const val ACTION_SCREEN_POWER = "carbridge.SCREEN_POWER"
        const val ACTION_PHONE_WAKE = "carbridge.PHONE_WAKE"
        const val ACTION_PHONE_DIM = "carbridge.PHONE_DIM"
        const val ACTION_APPLY_DPI = "carbridge.APPLY_DPI"
        const val ACTION_SHOW_HOME = "carbridge.SHOW_HOME"
        const val ACTION_METADATA_MODE = "carbridge.METADATA_MODE"
        const val ACTION_AUDIO_MODE = "carbridge.AUDIO_MODE"
        const val ACTION_REFRESH_NAV = "carbridge.REFRESH_NAV"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_PACKAGE = "launch_package"
        const val EXTRA_SCREEN_OFF = "screen_off"
        const val EXTRA_DPI = "display_dpi"
        const val EXTRA_KEY_CODE = "key_code"
        const val EXTRA_MODE = "mode"
        const val MODE_ADB = "adb"
        const val MODE_MIRROR = "mirror"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_STUB = "stub"
        const val EXTRA_TRANSPORT = "carlife_transport"
        const val TRANSPORT_USB = "usb"
        const val TRANSPORT_WIFI = "wifi"
        const val ACTION_DISPLAY_READY = "com.example.carbridge.DISPLAY_READY"
        const val ACTION_DISPLAY_STATUS = "com.example.carbridge.DISPLAY_STATUS"
        const val ACTION_PHONE_STATE = "com.example.carbridge.PHONE_STATE"
        const val EXTRA_PHONE_VISIBLE = "phone_visible"
        const val EXTRA_DISPLAY_ID = "display_id"
        const val EXTRA_STATUS = "status"
        private const val TAG = "CarBridge/Service"
        private const val CHANNEL = "carbridge_adb"
        private const val MAX_USB_RECOVERY_ATTEMPTS = 3
        private const val USB_RECOVERY_STABLE_MS = 60_000L
        private val USB_RECOVERY_DELAYS_MS = longArrayOf(1_000L, 2_500L, 5_000L)
    }
}
