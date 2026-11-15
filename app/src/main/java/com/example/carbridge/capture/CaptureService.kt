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
import com.example.carbridge.HuNavigationMotion
import com.example.carbridge.HuWeatherOverlay
import com.example.carbridge.WeatherWidgetSetupActivity
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
    private var usingCarbitLink = false
    private var lastHardwareSleepMs = -1L
    private var recoveredCarbitSleepMs = -2L
    private var lastHuForegroundPackage: String? = null
    private var usingAaWireless = false
    private var carbitCalibration:com.example.carbridge.CarbitCalibrationPresentation?=null
    private var carbitCalibrationWasHome=false

    private fun finishCarbitCalibration(restore:Boolean=true) {
        (link as? com.example.carbridge.link.CarbitHuLink)?.endCalibration()
        carbitCalibration?.close();carbitCalibration=null
        if(restore&&sessionActive) {
            showNavigation()
            if(carbitCalibrationWasHome)showHomeScreen()
        }
        carbitCalibrationWasHome=false
    }
    private fun startCarbitCalibration() {
        val active=link as? com.example.carbridge.link.CarbitHuLink
        val display=getSystemService(DisplayManager::class.java).getDisplay(AppDisplayState.displayId)
        if(active==null||display==null||!receivedFrame) {
            publishStatus("Калибровка: сначала подключите CarbitLink и дождитесь изображения")
            if(link==null)stopSelf()
            return
        }
        finishCarbitCalibration(false)
        carbitCalibrationWasHome=homePresentation?.isShowing==true
        dismissHomePresentation();navigation.close();mediaOverlay.close()
        try {
            val presentation=com.example.carbridge.CarbitCalibrationPresentation(applicationContext,display,huWidth,huHeight,
                success={transform->
                    if(link===active)runCatching{active.saveCalibration(transform)}
                        .onSuccess{publishStatus("Калибровка сохранена для этого приёмника и разрешения")}
                        .onFailure{publishStatus("Калибровка не сохранена: ${it.message}")}
                    finishCarbitCalibration()
                },failed={message->publishStatus(message);finishCarbitCalibration()})
            carbitCalibration=presentation;presentation.show()
            active.beginCalibration{event,x,y,slot->main.post{
                if(link===active&&carbitCalibration===presentation)presentation.rawTouch(event,x,y,slot)
            }}
            publishStatus("Калибровка: нажмите и отпустите пять меток на экране приёмника")
        }catch(e:Exception){publishStatus("Калибровка недоступна: ${e.message}");finishCarbitCalibration()}
    }
    @Volatile private var generation = 0
    private var huLinkEpoch=0L
    private val powerPlayback by lazy { com.example.carbridge.CarbitPowerPlaybackGuard(applicationContext) }
    private val powerAudioResumePosted=AtomicBoolean(false)
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
    private var audioIdleSince = 0L
    private var audioFailures = 0
    private var audioLastFailureAt = 0L
    private var audioWarmupRequested = false
    private var audioSuspendedByHu = false
    private var audioHuSuspendedAt = -20000L
    private var audioBeforeHuSuspend = false
    private var audioCallRecoveryPending = false
    private var audioNormalSince = 0L
    private var audioRecoveryUntil = 0L
    private var audioRecoveryAttempts = 0
    private var audioCaptureStartedAt = 0L
    private var playbackDiagnostic = ""
    private var observedAudioMode = AudioManager.MODE_NORMAL
    private var audioModeListener: AudioManager.OnModeChangedListener? = null

    private fun observeAudioMode(value: Int) {
        if(observedAudioMode==value) return
        val old=observedAudioMode
        observedAudioMode=value
        if(!sessionActive || !audioCaptureEnabled()) return
        val now=SystemClock.elapsedRealtime()
        if(value!=AudioManager.MODE_NORMAL) {
            audioNormalSince=0L
            if(usbAudioSource!=null || (audioBeforeHuSuspend && now-audioHuSuspendedAt in 0L..10000L))
                audioCallRecoveryPending=true
        } else if(audioCallRecoveryPending) {
            audioNormalSince=now
            // Recover a call-interrupted route, not an arbitrary HU source change
            // or a source explicitly disabled after repeated capture failures.
            if(audioFailures<3 && (!audioSuspended || audioSuspendedByHu)) {
                audioSuspended=false; audioSuspendedByHu=false
                audioRetryAt=maxOf(audioRetryAt,now+500L); audioWarmupRequested=true
                audioRecoveryUntil=now+20000L
                SessionDiagnostics.event("USB audio call-mode ended; routing recovery requested; previousMode=$old")
            }
        }
        SessionDiagnostics.event("USB audio mode changed; from=$old; to=$value; callRecovery=$audioCallRecoveryPending; huSuspended=$audioSuspendedByHu")
    }
    private var assistantAudioUntil = 0L
    private var assistantAudioStartedAt = 0L
    private var assistantAudioPackage: String? = null
    private var assistantRecordingObserverRegistered = false
    private var lastAssistantRecordingState = "<unset>"
    private val assistantRecordingObserver = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            if (sessionActive && assistantAudioUntil > SystemClock.elapsedRealtime()) logAssistantRecorders(configs.orEmpty())
        }
    }

    private fun beginAssistantAudio(packageName: String? = null) {
        assistantAudioStartedAt = SystemClock.elapsedRealtime()
        assistantAudioUntil = assistantAudioStartedAt + 90000L
        assistantAudioPackage = packageName
        audioIdleSince = 0L
        if (!assistantRecordingObserverRegistered) {
            runCatching {
                getSystemService(AudioManager::class.java).registerAudioRecordingCallback(assistantRecordingObserver, main)
                assistantRecordingObserverRegistered = true
            }.onFailure { SessionDiagnostics.event("HU recording observer unavailable: ${it.javaClass.simpleName}") }
        }
        SessionDiagnostics.event("USB assistant audio hold; timeoutMs=90000; package=${packageName ?: "resolving"}; microphone=phone")
        logAssistantRecorders(runCatching { getSystemService(AudioManager::class.java).activeRecordingConfigurations }.getOrDefault(emptyList()))
        updateUsbAudio()
    }

    private fun endAssistantAudio(reason: String) {
        if (assistantAudioUntil == 0L) return
        assistantAudioUntil = 0L
        assistantAudioPackage = null
        adbSource?.stopAssistantDiagnostics()
        if (assistantRecordingObserverRegistered) runCatching {
            getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(assistantRecordingObserver)
        }
        assistantRecordingObserverRegistered = false
        lastAssistantRecordingState = "<unset>"
        audioIdleSince = 0L
        SessionDiagnostics.event("USB assistant audio hold released; reason=$reason")
    }

    private fun logAssistantRecorders(configs: List<android.media.AudioRecordingConfiguration>) {
        val summary = configs.take(8).joinToString("; ") { config ->
            val device = runCatching { config.audioDevice?.type?.toString() ?: "unknown" }.getOrDefault("unknown")
            "session=${config.clientAudioSessionId} source=${config.clientAudioSource} deviceType=$device silenced=${config.isClientSilenced}"
        }
        if (summary == lastAssistantRecordingState) return
        lastAssistantRecordingState = summary
        SessionDiagnostics.event("HU recording inputs; identity=anonymized; count=${configs.size}; configs=[$summary]")
    }
    private val carLifeMetadata by lazy { com.example.carbridge.HuCarLifeMediaMetadata(applicationContext) }
    private val carLifeNavigation by lazy { com.example.carbridge.HuCarLifeNavigation(applicationContext) }
    private var strelkaDiagnosticAt = -30000L
    private val audioPoll = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            updateUsbAudio()
            diagnosePlayback()
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
            onWeatherToggle = { setSidebarCard(SidebarCard.WEATHER,SidebarCard.WEATHER !in sidebarOrder) },
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
            onNotificationOpen = { openNotificationOnHu(it) },
            onNavigationForegroundCheck = { adbSource?.diagnoseDisplay("navigation cue visibility",force=true) },
            onNavigationCueVisibility = {
                setSidebarCard(SidebarCard.NAVIGATION,it)
            })
    }
    private val mediaOverlay: HuMediaOverlay by lazy {
        HuMediaOverlay(applicationContext,
            onDismissPanel = { setMediaCardVisible(false) },
            onOpenMediaSource = { packageName -> openMediaSourceOnHu(packageName) },
            onNavigationPlacementChanged = { right, height, inset ->
                navigation.setNavigationCardPlacement(right,height,inset)
            },
            onLayoutChanged = { visible, right ->
                navigation.setMediaVisible(visible)
                setSidebarCard(SidebarCard.MUSIC,visible)
            })
    }
    private enum class SidebarCard { MUSIC, NAVIGATION, WEATHER }
    private val sidebarOrder=mutableListOf<SidebarCard>()
    private var changingSidebar=false
    private var closingSidebar=false
    private val weatherOverlay by lazy {
        HuWeatherOverlay(applicationContext,
            onClose={ setSidebarCard(SidebarCard.WEATHER,false) },
            onSwap={ mediaOverlay.moveToOtherSide() },
            onSetup={
                runCatching { startActivity(Intent(this,WeatherWidgetSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { publishStatus("Откройте на телефоне настройки CarBridge → Настроить виджет Яндекс Погоды") }
            },onOpen={ launchSelectedApp(it) })
    }
    private fun setSidebarCard(card: SidebarCard, visible: Boolean) {
        if(changingSidebar || closingSidebar) return
        changingSidebar=true
        try {
            if(visible && card !in sidebarOrder) sidebarOrder.add(0,card)
            if(!visible) sidebarOrder.remove(card)
            val removed=mutableListOf<SidebarCard>()
            while(sidebarOrder.size>2) removed.add(sidebarOrder.removeAt(sidebarOrder.lastIndex))
            if(!visible) removed.add(card)
            removed.distinct().forEach {
                when(it) {
                    SidebarCard.MUSIC -> if(mediaOverlay.isVisible()) mediaOverlay.setVisible(false,animate=false)
                    SidebarCard.NAVIGATION -> if(navigation.isNavigationCueVisible()) navigation.hideNavigationCard()
                    SidebarCard.WEATHER -> weatherOverlay.hide()
                }
            }
        } finally { changingSidebar=false }
        layoutSidebarCards()
    }
    private fun layoutSidebarCards() {
        if(changingSidebar || closingSidebar) return
        val geometry=mediaOverlay.sidebarGeometry() ?: return
        val count=sidebarOrder.size
        val available=(geometry.height-geometry.inset*2).coerceAtLeast(1)
        val topHeight=if(count==2) ((available-geometry.gap)/2).coerceAtLeast(1) else available
        var weatherFailed=false
        sidebarOrder.toList().forEachIndexed { index,card ->
            val y=if(index==0) geometry.inset else geometry.inset+topHeight+geometry.gap
            val h=if(index==0) topHeight else (available-geometry.gap-topHeight).coerceAtLeast(1)
            when(card) {
                SidebarCard.MUSIC -> mediaOverlay.setSidebarSlot(h,y)
                SidebarCard.NAVIGATION -> navigation.setNavigationCardPlacement(geometry.right,h,geometry.inset,y)
                SidebarCard.WEATHER -> if(!weatherOverlay.place(AppDisplayState.displayId,geometry,h,y)) weatherFailed=true
            }
        }
        if(weatherFailed) { sidebarOrder.remove(SidebarCard.WEATHER); weatherOverlay.hide(); layoutSidebarCards(); return }
        navigation.setWeatherVisible(SidebarCard.WEATHER in sidebarOrder)
        updateSidebarLayout()
        val signature=sidebarOrder.joinToString(",")+":${geometry.right}"
        if(signature!=lastSidebarLayout) {
            lastSidebarLayout=signature
            SessionDiagnostics.event("HU sidebar layout; topFirst=${sidebarOrder.joinToString(",")}; right=${geometry.right}; count=$count")
        }
    }
    private var lastSidebarLayout=""

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
        if(Build.VERSION.SDK_INT>=31) {
            val listener=AudioManager.OnModeChangedListener { value ->
                observeAudioMode(value)
                if(sessionActive) updateUsbAudio()
            }
            runCatching { getSystemService(AudioManager::class.java).addOnModeChangedListener(mainExecutor,listener) }
                .onSuccess { audioModeListener=listener }
                .onFailure { SessionDiagnostics.event("USB audio mode observer unavailable: ${it.javaClass.simpleName}") }
        }
        getSystemService(PowerManager::class.java).addThermalStatusListener(
            java.util.concurrent.Executor { command -> main.post(command); Unit }, thermalStatusListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART_AUDIO -> {
                if(link==null||!sessionActive) {
                    publishStatus("Восстановление звука: сначала подключитесь к приёмнику")
                    if(link==null)stopSelf()
                } else if(!audioCaptureEnabled()) publishStatus("Сначала включите передачу звука для этого подключения")
                else {
                    stopUsbAudio("manual routing recovery; video retained")
                    audioSuspended=false;audioSuspendedByHu=false;audioFailures=0
                    audioRetryAt=SystemClock.elapsedRealtime()+500L;audioWarmupRequested=true
                    audioRecoveryUntil=SystemClock.elapsedRealtime()+20000L;audioRecoveryAttempts=0
                    if(getSystemService(AudioManager::class.java).mode!=AudioManager.MODE_NORMAL)audioCallRecoveryPending=true
                    publishStatus("Перезапускаю передачу звука. Видео продолжает работать.")
                }
                return START_NOT_STICKY
            }
            ACTION_CARBIT_CALIBRATE -> { startCarbitCalibration();return START_NOT_STICKY }
            ACTION_CARBIT_CALIBRATE_CANCEL -> {
                finishCarbitCalibration();if(link==null)stopSelf();return START_NOT_STICKY
            }
            ACTION_CARBIT_CALIBRATE_RESET -> {
                finishCarbitCalibration()
                (link as? com.example.carbridge.link.CarbitHuLink)?.clearCalibration()
                publishStatus("Калибровка текущего подключения сброшена")
                if(link==null)stopSelf();return START_NOT_STICKY
            }
            ACTION_NAVIGATION_MOTION -> {
                if (sessionActive) {
                    locationForeground = intent.getBooleanExtra(EXTRA_NAVIGATION_MOTION,false) &&
                        HuNavigationMotion.enabled(this) && HuNavigationMotion.permitted(this)
                    startAsForeground()
                } else stopSelf()
                return START_NOT_STICKY
            }
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
            ACTION_MICROPHONE_PROBE, ACTION_MICROPHONE_PROBE_STOP -> {
                val active = link
                if (intent.action == ACTION_MICROPHONE_PROBE_STOP) {
                    active?.stopMicrophoneProbe()
                } else if (active == null || !usingUsb) {
                    publishStatus("Проверка микрофона: сначала подключитесь к ГУ по USB CarLife")
                } else if (!active.startMicrophoneProbe()) {
                    publishStatus("Проверка микрофона не запущена; подробности в статусе подключения и журнале")
                }
                if (link == null && !sessionActive) stopSelf()
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
                audioSuspendedByHu=false; audioCallRecoveryPending=false
                audioWarmupRequested=audioCaptureEnabled()
                updateUsbAudio()
                publishStatus(if(usingCarbitLink) {
                    if(audioCaptureEnabled()) "Сетевой звук CarbitLink включён" else "Сетевой звук CarbitLink выключен"
                } else if (connectionSettings.usbAudio) "USB-звук включён: запустите музыку при активном USB CarLife"
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
        lastHardwareSleepMs = -1L
        recoveredCarbitSleepMs = -2L
        lastHuForegroundPackage = null
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ADB
        selectedPackage = intent?.getStringExtra(EXTRA_PACKAGE) ?: settings.packageNameLaunch
        screenOffRequested = intent?.getBooleanExtra(EXTRA_SCREEN_OFF, settings.screenOff) ?: settings.screenOff
        phonePanel.preference(screenOffRequested).also(phonePanel::complete)
        locationForeground = intent?.getBooleanExtra(EXTRA_NAVIGATION_MOTION,false) == true &&
            HuNavigationMotion.enabled(this) && HuNavigationMotion.permitted(this)
        usingAaWireless = intent?.getStringExtra(EXTRA_TRANSPORT) == TRANSPORT_AA_WIRELESS &&
            intent.getBooleanExtra(EXTRA_STUB, false) == false
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
        hasActiveSession = true
        closingSidebar=false
        thermalCapAppliedFps = Int.MAX_VALUE
        thermalStatusListener.onThermalStatusChanged(getSystemService(PowerManager::class.java).currentThermalStatus)
        announcedDisplay = false
        audioSuspended = false; audioFailures = 0; audioRetryAt = 0L; audioHuReadyAt = Long.MAX_VALUE
        audioSuspendedByHu=false; audioCallRecoveryPending=false; audioBeforeHuSuspend=false
        audioNormalSince=SystemClock.elapsedRealtime(); audioRecoveryUntil=0L; audioRecoveryAttempts=0
        playbackDiagnostic=""
        com.example.carbridge.HuGuidanceSource.reset()
        audioHuSuspendedAt=-20000L; audioLastFailureAt=0L; audioWarmupRequested=settings.usbAudio
        observedAudioMode=getSystemService(AudioManager::class.java).mode
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
        usingCarbitLink = !stub && transport == TRANSPORT_CARBIT
        audioWarmupRequested=audioCaptureEnabled()
        Log.i(TAG, "mode=${if (stub) "STUB" else transport}; source=$mode; adb=${settings.connectionMode}; app=$selectedPackage")
        link = when {
            stub -> StubHuLink()
            usingAaWireless -> com.example.carbridge.link.AaWirelessHuLink(applicationContext,
                intent?.getStringExtra(EXTRA_AA_ADAPTER) ?: "")
            usingCarbitLink -> com.example.carbridge.link.CarbitHuLink(applicationContext,
                intent?.getStringExtra(EXTRA_CARBIT_HOST) ?: "", intent?.getIntExtra(EXTRA_CARBIT_PORT,10930) ?: 10930)
            usingWifiCarLife -> openWifiLink()
            else -> openUsbLink()
        }
        if (link == null) { shutdown(); return START_NOT_STICKY }
        publishStatus(if(usingAaWireless) "AA: подключаюсь к адаптеру" else if(usingCarbitLink) "CarbitLink: ожидаю параметры видео от ГУ" else "CarLife: ожидаю параметры видео от ГУ")
        startHuLink(link!!)
        return START_NOT_STICKY
    }

    private fun startHuLink(activeLink: HuLink) {
        val activeLinkEpoch = ++huLinkEpoch
        latestScrcpyConfig?.let(activeLink::sendConfig)
        activeLink.start(object : HuLink.Listener {
            private fun active(action: () -> Unit) {
                main.post {
                    if (sessionActive && huLinkEpoch == activeLinkEpoch && link === activeLink) action()
                }
            }
            override fun onHuReady(width: Int, height: Int) = active {
                this@CaptureService.onHuReady(width, height)
            }
            override fun onTouch(event: HuLink.TouchEvent) = active {
                if(event.action==android.view.MotionEvent.ACTION_DOWN && usingCarbitLink)
                    SessionDiagnostics.event("CarbitLink TOUCH_DISPATCH shell=${AppDisplayState.displayId} app=$applicationDisplayId videoReady=$receivedFrame")
                this@CaptureService.onTouch(event)
            }
            override fun onHardKey(code: Int) = active { this@CaptureService.onHardKey(code) }
            override fun onDiagnosticStatus(message: String) = active { publishStatus(message) }
            override fun onAudioSuspend() = active {
                audioBeforeHuSuspend=usbAudioSource!=null
                audioHuSuspendedAt=SystemClock.elapsedRealtime()
                audioSuspended = true
                audioSuspendedByHu=true
                endAssistantAudio("HU selected another audio source")
                stopUsbAudio("HU selected another audio source or paused")
            }
            override fun onAudioResume() = active {
                val wasSuspended=audioSuspendedByHu
                audioSuspended = false
                audioSuspendedByHu=false; audioWarmupRequested=true
                if(wasSuspended) {
                    val now=SystemClock.elapsedRealtime()
                    audioRecoveryUntil=now+20000L
                    audioRetryAt=maxOf(audioRetryAt,now+500L)
                }
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
    private fun audioCaptureEnabled():Boolean = if(usingCarbitLink)
        getSharedPreferences("carbit_connection",0).getBoolean("audio",true) else connectionSettings.usbAudio

    /** State changes only: no track names, messages or recording audio in diagnostics. */
    private fun diagnosePlayback() {
        val manager=getSystemService(AudioManager::class.java)
        val sessions=runCatching {
            getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(this,HuMediaNotificationListener::class.java))
                .map { "${it.packageName}:${it.playbackState?.state ?: -1}" }.sorted().joinToString(",")
        }.getOrDefault("access_unavailable")
        val state="mode=${manager.mode}; musicActive=${manager.isMusicActive}; sessions=$sessions"
        if(state!=playbackDiagnostic) {
            playbackDiagnostic=state
            SessionDiagnostics.event("Playback state changed; $state; capture=${usbAudioSource!=null}")
        }
    }

    private fun updateUsbAudio() {
        val activeLink = link
        val now = SystemClock.elapsedRealtime()
        val audioMode = getSystemService(AudioManager::class.java).mode
        observeAudioMode(audioMode)
        val carbitAudio=usingCarbitLink && getSharedPreferences("carbit_connection",0).getBoolean("audio",true)
        val wanted = audioCaptureEnabled() && (usingUsb || usingAaWireless || carbitAudio) && mode == MODE_ADB && receivedFrame &&
            Build.VERSION.SDK_INT >= 31 && !audioSuspended && now >= audioHuReadyAt && activeLink != null
        if(audioMode==AudioManager.MODE_NORMAL && audioNormalSince==0L) audioNormalSince=now
        if (assistantAudioUntil != 0L && now >= assistantAudioUntil) endAssistantAudio("90s deadline")
        if (audioMode != AudioManager.MODE_NORMAL) endAssistantAudio("audio mode=$audioMode")
        if(usbAudioSource?.captureReady==true && now-audioCaptureStartedAt>=10000L) audioFailures=0
        val assistantActive = assistantAudioUntil > now
        if (!wanted || audioMode != AudioManager.MODE_NORMAL) {
            if (usbAudioSource != null) stopUsbAudio(if (wanted) "audio mode=$audioMode" else "disabled or transport unavailable")
            audioIdleSince = 0L
            return
        }
        if (!assistantActive && !playbackActive()) {
            val current = usbAudioSource
            // Startup has its own 20s deadline. A brief pause between speech phrases
            // must not repeatedly tear down the output route or cancel startup.
            if(current==null) {
                if(!audioWarmupRequested) { audioIdleSince=0L; return }
            } else if(!current.captureReady) { audioIdleSince=0L; return }
            else {
              if (audioIdleSince == 0L) {
                audioIdleSince = now
                SessionDiagnostics.event("USB audio idle grace started; timeoutMs=60000; mode=$audioMode")
              }
              if (now - audioIdleSince >= 60000) stopUsbAudio("playback idle after 60s grace")
              return
            }
        }
        audioIdleSince = 0L
        if (usbAudioSource != null || now < audioRetryAt || activeLink == null || now-audioNormalSince<500L) return
        val currentGeneration = generation
        var source: ScrcpyAudioSource? = null
        source = ScrcpyAudioSource(applicationContext, object : ScrcpyAudioSource.Listener {
            override fun onFormatReady(): Boolean = synchronized(usbAudioLock) {
                val accepted=generation == currentGeneration && usbAudioSource === source && link === activeLink &&
                    getSystemService(AudioManager::class.java).mode==AudioManager.MODE_NORMAL && activeLink.startMediaAudio()
                if(accepted) {
                    audioRecoveryUntil=0L; audioRecoveryAttempts=0; audioCallRecoveryPending=false
                }
                accepted
            }
            override fun onPcm(pcm: ByteArray) {
                synchronized(usbAudioLock) {
                    if (generation == currentGeneration && usbAudioSource === source && link === activeLink) {
                        val sent=activeLink.sendMediaPcm(pcm)
                        if(sent && usingCarbitLink && powerPlayback.pending && powerAudioResumePosted.compareAndSet(false,true)) {
                            main.postDelayed({
                                powerAudioResumePosted.set(false)
                                if(generation==currentGeneration && usbAudioSource===source && link===activeLink && source?.captureReady==true)
                                    powerPlayback.resumeIfRouted()
                            },200L)
                        }
                    }
                }
            }
            override fun onError(message: String) {
                if (usbAudioSource !== source || generation != currentGeneration) return
                stopUsbAudio("capture failed: $message")
                val failedAt=SystemClock.elapsedRealtime()
                val normal=getSystemService(AudioManager::class.java).mode==AudioManager.MODE_NORMAL
                if(!normal) {
                    audioCallRecoveryPending=true
                    audioRetryAt=failedAt+500L
                    audioWarmupRequested=true
                    SessionDiagnostics.event("Audio startup interrupted by call mode; retry after stable normal mode; video retained")
                    return
                }
                if(failedAt-audioLastFailureAt>60000L) audioFailures=0
                audioLastFailureAt=failedAt
                audioFailures++
                val fast=failedAt<audioRecoveryUntil && audioRecoveryAttempts<2
                val retryMs=if(fast) { audioRecoveryAttempts++; audioRecoveryAttempts*1000L } else 10000L
                audioRetryAt = failedAt + retryMs
                if(fast) audioWarmupRequested=true
                if (audioFailures >= 3) { audioSuspended = true; audioSuspendedByHu=false }
                SessionDiagnostics.event("USB audio failed; attempts=$audioFailures; retryMs=$retryMs; callRecovery=$audioCallRecoveryPending; video retained; error=$message")
                publishStatus("USB-звук: $message. Видео продолжает работать. " +
                    if (audioSuspended) "Повторить — выключите и включите USB-звук в настройках." else "Повтор через ${retryMs/1000} с.")
            }
        })
        usbAudioSource = source
        audioCaptureStartedAt=SystemClock.elapsedRealtime()
        usbAudioLink = activeLink
        val warmup=audioWarmupRequested
        audioWarmupRequested=false
        SessionDiagnostics.event("USB audio starting; independent shell output capture; reason=${if (assistantActive) "assistant dialogue" else if(warmup) "connection or call recovery warmup" else "playback active"}")
        source.start()
    }

    private fun stopUsbAudio(reason: String) {
        audioIdleSince = 0L
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
        audioCallRecoveryPending=false; audioWarmupRequested=false
        audioHuReadyAt = Long.MAX_VALUE
        endAssistantAudio("CarLife disconnected")
        stopUsbAudio("CarLife disconnected")
        val transport = if (usingAaWireless) "aa_adapter" else if (usingWifiCarLife) "wifi" else if (usingUsb) "usb" else "unknown"
        SessionDiagnostics.event("CaptureService received HU disconnect; transport=$transport; reason=$reason")
        adbSource?.diagnoseDisplay("CarLife disconnect: $reason", force = true)
        if (usingUsb && mode == MODE_ADB && isRecoverableUsbDisconnect(reason)) {
            beginUsbRecovery(activeLink, reason)
            return
        }
        publishStatus(if(usingAaWireless) "Адаптер AA: ${reason.take(140)}" else if(usingCarbitLink) "CarbitLink разорвано: ${reason.take(140)}" else if (usingWifiCarLife) "Wi-Fi CarLife разорвано: ${reason.take(140)}" else
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
                        BridgeSettings.DISPLAY_MODE_1920_720 ->
                            if(width.toLong()*3L == height.toLong()*8L) 1920 to 720 else width to height
                        BridgeSettings.DISPLAY_MODE_1920_1080 ->
                            if (width == 1280 && height == 720) 1920 to 1080 else width to height
                        BridgeSettings.DISPLAY_MODE_1920_960 ->
                            if (width == 1280 && height == 640) 1920 to 960 else width to height
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
                    if (sourceSettings.logicalDisplayMode != BridgeSettings.DISPLAY_MODE_HU && !canScaleLogicalDisplay && !(sourceSettings.logicalDisplayMode == BridgeSettings.DISPLAY_MODE_1920_720 && width == 1920 && height == 720)) {
                        SessionDiagnostics.event("Logical display mode=${sourceSettings.logicalDisplayMode} not applied: unsupported HU frame=${width}x$height")
                        publishStatus("Размер не применён: ГУ ${width}×${height}. " +
                            if(width==1280 && height==640) "Выберите 1920×960 / 300 DPI и переподключитесь." else "Выберите профиль с такими же пропорциями и переподключитесь.")
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
                                        (session as? com.example.carbridge.link.CarbitHuLink)?.endCaptureRecovery()
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
                                main.post sourceErrorUi@{
                                    if (generation == currentGeneration && link === session && adbSource === sourceForCallbacks) {
                                        if (recoverCarbitAfterPowerLoss(session, sourceForCallbacks)) return@sourceErrorUi
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
                            override fun onAssistantStarted(packageName: String) {
                                main.post { if (generation == currentGeneration && link === session) {
                                    appLaunchRequest++
                                    beginAssistantAudio(packageName)
                                } }
                            }
                            override fun onAssistantFinished(reason: String) {
                                main.post { if (generation == currentGeneration && link === session) endAssistantAudio(reason) }
                            }
                            override fun onForegroundApp(packageName: String?) {
                                main.post {
                                    if (generation == currentGeneration && link === session) {
                                        if (packageName != null && packageName != applicationContext.packageName)
                                            lastHuForegroundPackage = packageName
                                        if (packageName != null && assistantAudioPackage != null && packageName != assistantAudioPackage &&
                                            SystemClock.elapsedRealtime() - assistantAudioStartedAt >= 5000L) endAssistantAudio("foreground app changed")
                                        navigation.setForegroundPackage(if (homePresentation?.isShowing == true)
                                            applicationContext.packageName else packageName)
                                    }
                                }
                            }
                        }, isolatedPowerCommand = usingCarbitLink, beforeStart = {
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
            if(receivedFrame)adbSource?.sendTouch(event)
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
            if(code in setOf(15,16,31,32))powerPlayback.cancel("user wheel control")
            val now = android.os.SystemClock.elapsedRealtime()
            val repeatDelay = if (code == 33) 1200L else 250L
            if (code == lastWheelCode && now - lastWheelAt < repeatDelay) {
                if(code==33) SessionDiagnostics.event("HU assistant key ignored; debounceMs=$repeatDelay; intervalMs=${now-lastWheelAt}")
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
                33 -> {
                    link?.stopMicrophoneProbe()
                    dismissHomePresentation()
                    logAssistantAudioState("before launch")
                    if (adbSource != null) beginAssistantAudio()
                    adbSource?.launchVoiceAssistant()
                    for (delay in listOf(3000L, 10000L)) main.postDelayed({
                        if (sessionActive && generation == token) logAssistantAudioState("after ${delay}ms")
                    }, delay)
                }
                14 -> handleHuBack()
                30 -> showHomeScreen()
            }
        }
    }
    private var lastUnknownWheelAt = -10000L
    private fun logAssistantAudioState(stage: String) {
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }.distinct().sorted()
            SessionDiagnostics.event("HU assistant audio state; stage=$stage; mode=${audio.mode}; microphoneMuted=${audio.isMicrophoneMute}; musicActive=${audio.isMusicActive}; inputTypes=$inputs; usbCapture=${usbAudioSource != null}; captureReady=${usbAudioSource?.captureReady == true}; huMicrophone=false")
        }.onFailure { SessionDiagnostics.event("HU assistant audio diagnostics unavailable: ${it.javaClass.simpleName}") }
    }
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
        lastHardwareSleepMs = SystemClock.elapsedRealtime()
        SessionDiagnostics.event("Hardware sleep: choose physical panel ${if (choice.on) "ON" else "OFF"}; revision=${choice.revision}")
        applyPhoneChoice(choice, wake = true)
    }

    /** One bounded capture restart per hardware sleep; the receiver TCP session stays owned. */
    private fun recoverCarbitAfterPowerLoss(session: HuLink, failedSource: ScrcpySource?): Boolean {
        val carbit = session as? com.example.carbridge.link.CarbitHuLink ?: return false
        val now = SystemClock.elapsedRealtime()
        if (!usingCarbitLink || failedSource == null || adbSource !== failedSource ||
            AdbSession.isConnected() || lastHardwareSleepMs < 0 || now-lastHardwareSleepMs !in 0L..8_000L ||
            recoveredCarbitSleepMs == lastHardwareSleepMs) return false
        recoveredCarbitSleepMs = lastHardwareSleepMs
        if(audioCaptureEnabled())powerPlayback.pauseForTransition()
        generation++ // Invalidate every callback belonging to the dead video/audio sources.
        val token = generation
        // The old source's completion is now stale: release its service-level power latch.
        phonePanel.explicit(phonePanel.visible).also(phonePanel::complete)
        recoveryWakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        carbit.beginCaptureRecovery()
        stopUsbAudio("local ADB lost after phone power; CarbitLink retained")
        audioFailures = 0
        audioRetryAt = now+750L
        audioWarmupRequested = true
        closeApplications()
        dismissHomePresentation()
        finishCarbitCalibration(false)
        weatherOverlay.hide()
        contentPresentation?.close(); contentPresentation=null; applicationDisplayId=-1
        navigation.close(); mediaOverlay.close()
        adbSource=null
        failedSource.stop()
        AppDisplayState.displayId=-1
        announcedDisplay=false; receivedFrame=false; latestScrcpyConfig=null
        lastHuForegroundPackage?.let { selectedPackage=it }
        SessionDiagnostics.event("CarbitLink POWER_RECOVERY begin; preserving receiver session; size=${huWidth}x$huHeight")
        publishStatus("После кнопки питания закрылся локальный ADB. Восстанавливаю видео и звук; связь с ГУ сохранена…")
        main.postDelayed({
            if (generation==token && link===session && sessionActive && adbSource==null) onHuReady(huWidth,huHeight)
        },750L)
        main.postDelayed({
            if (generation==token && link===session && carbit.isCaptureRecovering() && !receivedFrame) {
                publishStatus("Не удалось восстановить ADB после кнопки питания за 30 секунд. Отправьте журнал.")
                shutdown()
            }
        },30_000L)
        return true
    }

    /** All callers run on main. The saved startup preference is unchanged by hardware presses. */
    private fun applyPhoneChoice(choice: PhonePanelState.Choice, wake: Boolean) {
        val source = adbSource ?: run { phonePanel.complete(choice); return }
        if(usingCarbitLink && wake && receivedFrame) {
            lastHardwareSleepMs=SystemClock.elapsedRealtime()
            if(audioCaptureEnabled() && usbAudioSource?.captureReady==true)powerPlayback.pauseForTransition()
        }
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
        val shown = navigation.show(id, connectionSettings.displayDpi, huWidth, connectionSettings.dockAutoHide,
            connectionSettings.resizeForMusic)
        navigation.setPhoneVisible(phonePanel.visible)
        val mediaShown = mediaOverlay.show(id, connectionSettings.displayDpi, huWidth,
            connectionSettings.resizeForMusic, !connectionSettings.dockAutoHide)
        if(SidebarCard.MUSIC in sidebarOrder && !mediaOverlay.isVisible()) mediaOverlay.setVisible(true,animate=false)
        navigation.setMediaVisible(mediaOverlay.isVisible())
        layoutSidebarCards()
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
                }, onFailure = contentFailure@{ error ->
                    if (adbSource === source) {
                        SessionDiagnostics.event("HU content area failed: ${error.take(180)}")
                        val active=link
                        if(active!=null && recoverCarbitAfterPowerLoss(active,source)) return@contentFailure
                        publishStatus("Не удалось создать окно приложения: ${error.take(160)}. Переподключите CarBridge.")
                        shutdown()
                    }
                })
            contentPresentation = presentation
            presentation.show()
        } catch (e: Exception) {
            val active=link
            if(adbSource===source && active!=null && recoverCarbitAfterPowerLoss(active,source))return
            publishStatus("Не удалось создать окно: ${e.message}")
            shutdown()
        }
    }

    private fun updateSidebarLayout() {
        val reserved=sidebarOrder.any { it!=SidebarCard.MUSIC } || (mediaOverlay.isVisible() && connectionSettings.resizeForMusic)
        val right=mediaOverlay.isOnRight()
        val fraction=if(connectionSettings.resizeForMusic) 0.30f else 0.34f
        contentPresentation?.setMusicLayout(reserved,right,fraction)
        homePresentation?.setMusicLayout(reserved,right,fraction)
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
    private var appLaunchRequest=0L

    private fun openNotificationOnHu(item: android.service.notification.StatusBarNotification) {
        val source=adbSource ?: return
        val request=++appLaunchRequest
        val target=applicationDisplayId
        val intent=item.notification.contentIntent
        if(target<=0 || intent==null || !intent.isActivity) { launchSelectedApp(item.packageName); return }
        try {
            closeApplications()
            homePresentation?.dismiss(); homePresentation=null
            navigation.expectForegroundPackage(item.packageName)
            intent.send(this,0,null,null,null,null,ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
            source.watchRequestedLaunch(item.packageName) { opened, launchToken ->
                main.post {
                    if (adbSource !== source || closedService() || applicationDisplayId != target ||
                        appLaunchRequest != request || !source.isCurrentLaunch(launchToken)) return@post
                    // A newer app/home/assistant request invalidates the source callback.
                    if (!opened) {
                        SessionDiagnostics.event("HU notification launch fallback; package=${item.packageName}; display=$target")
                        launchSelectedApp(item.packageName)
                    }
                }
            }
            SessionDiagnostics.event("HU notification activity sent; package=${item.packageName}; display=$target")
        } catch(error: Exception) {
            navigation.cancelExpectedForeground(item.packageName)
            SessionDiagnostics.event("HU notification activity failed: ${error.javaClass.simpleName}")
            launchSelectedApp(item.packageName)
        }
    }

    private fun showHomeScreen() {
        val request=++appLaunchRequest
        endAssistantAudio("CarBridge home opened")
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
                if (generation != token || adbSource !== source || closedService() || appLaunchRequest != request) return@post
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
            presentation.setMusicLayout(sidebarOrder.any { it!=SidebarCard.MUSIC } || (mediaOverlay.isVisible() && connectionSettings.resizeForMusic),
                mediaOverlay.isOnRight(),if(connectionSettings.resizeForMusic) 0.30f else 0.34f)
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
        if (assistantAudioUntil != 0L && packageName != assistantAudioPackage) endAssistantAudio("another app selected")
        val source = adbSource ?: return
        val request=++appLaunchRequest
        dismissHomePresentation()
        selectedPackage = packageName
        BridgeSettings.choosePackage(applicationContext, packageName)
        navigation.expectForegroundPackage(packageName)
        source.launchApp(packageName, launcherComponent) { success, details ->
            main.post {
                if (adbSource !== source || closedService() || appLaunchRequest != request) return@post
                SessionDiagnostics.event("HU app launch fullscreen success=$success; $details")
                if (success) {
                    com.example.carbridge.HuLauncherStore.record(applicationContext, packageName)
                }
                else navigation.cancelExpectedForeground(packageName)
                publishStatus(if (success) "Запрошено открытие приложения на ГУ" else
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

    /** Open the widget's current player on the application display. */
    private fun openMediaSourceOnHu(packageName: String) {
        if(mode!=MODE_ADB || adbSource==null || applicationDisplayId<=0) {
            publishStatus("Виртуальный дисплей не готов для восстановления музыки")
            return
        }
        launchSelectedApp(packageName)
        SessionDiagnostics.event("HU media source recovery launch; package=$packageName; display=$applicationDisplayId")
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
        closingSidebar=true; sidebarOrder.clear(); lastSidebarLayout=""
        weatherOverlay.close()
        HuNavigationMotion.setSessionAllowed(this,false)
        locationForeground=false
        val wasActive = sessionActive
        powerPlayback.cancel("session shutdown")
        sessionActive = false
        hasActiveSession = false
        endAssistantAudio("session shutdown")
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
        finishCarbitCalibration(false)
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
        usingAaWireless = false
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
        if(Build.VERSION.SDK_INT>=31) audioModeListener?.let { listener ->
            runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(listener) }
        }
        audioModeListener=null
        unregisterReceiver(usbReceiver)
        unregisterReceiver(screenReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalStatusListener)
        shutdown()
        carLifeMetadata.close()
        super.onDestroy()
    }

    private var locationForeground=false
    private fun startAsForeground() {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CarBridge Offline")
            .setContentText("Подключение к ГУ").setOngoing(true).build()
        var type = if (mode == MODE_MIRROR) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else if (usingAaWireless) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val baseType=type
        if (locationForeground) type=type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            startForeground(1, notification, type)
        } catch (error: SecurityException) {
            if (!locationForeground) throw error
            locationForeground=false
            startForeground(1,notification,baseType)
            SessionDiagnostics.event("Navigation road speed: location foreground access denied; road remains still")
        }
        HuNavigationMotion.setSessionAllowed(this,locationForeground)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarBridge Offline", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        @Volatile var hasActiveSession: Boolean = false
            private set
        const val ACTION_RESTART_AUDIO="carbridge.RESTART_AUDIO"
        const val ACTION_CARBIT_CALIBRATE="carbridge.CARBIT_CALIBRATE"
        const val ACTION_CARBIT_CALIBRATE_CANCEL="carbridge.CARBIT_CALIBRATE_CANCEL"
        const val ACTION_CARBIT_CALIBRATE_RESET="carbridge.CARBIT_CALIBRATE_RESET"
        const val ACTION_NAVIGATION_MOTION="carbridge.NAVIGATION_MOTION"
        const val EXTRA_NAVIGATION_MOTION="navigation_motion_foreground"
        const val ACTION_STOP = "carbridge.STOP"
        const val ACTION_LAUNCH_DISPLAY_APP = "carbridge.LAUNCH_DISPLAY_APP"
        const val ACTION_HARD_KEY = "carbridge.HARD_KEY"
        const val ACTION_SCREEN_POWER = "carbridge.SCREEN_POWER"
        const val ACTION_PHONE_WAKE = "carbridge.PHONE_WAKE"
        const val ACTION_PHONE_DIM = "carbridge.PHONE_DIM"
        const val ACTION_APPLY_DPI = "carbridge.APPLY_DPI"
        const val ACTION_SHOW_HOME = "carbridge.SHOW_HOME"
        const val ACTION_METADATA_MODE = "carbridge.METADATA_MODE"
        const val ACTION_MICROPHONE_PROBE = "carbridge.MICROPHONE_PROBE"
        const val ACTION_MICROPHONE_PROBE_STOP = "carbridge.MICROPHONE_PROBE_STOP"
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
        const val TRANSPORT_CARBIT = "carbit_wifi"
        const val TRANSPORT_AA_WIRELESS = "aa_adapter_wireless"
        const val EXTRA_AA_ADAPTER = "aa_adapter_address"
        const val EXTRA_CARBIT_HOST = "carbit_host"
        const val EXTRA_CARBIT_PORT = "carbit_port"
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
