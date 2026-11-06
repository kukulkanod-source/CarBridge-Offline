package com.example.carbridge

import android.content.Context

/** Connection and display preferences. Pairing codes are never persisted. */
object BridgeSettings {
    const val MODE_TLS = "tls"
    const val MODE_LOCAL_TCP = "local_tcp"
    const val DISPLAY_MODE_HU = "hu"
    const val DISPLAY_MODE_1600_900 = "1600x900"
    const val DISPLAY_MODE_1920_1080 = "1920x1080"
    const val DISPLAY_MODE_1920_960 = "1920x960"
    const val DEFAULT_DISPLAY_DPI = 200
    const val DEFAULT_APP_CONTENT_DPI = 200
    val DISPLAY_DPI_OPTIONS: List<Int> = listOf(80, 100, 120, 140, 160, 180, 200, 240, 280, 320)
    // Experimental virtual display modes are downscaled to the negotiated HU video size.
    // Higher app content densities apply only to the selected app on that display.
    val APP_CONTENT_DPI_OPTIONS: List<Int> = listOf(80, 100, 120, 140, 160, 180, 200, 220, 240, 250, 300)
    internal const val DISPLAY_PREFERENCES_SCHEMA = 2
    private const val APP_CONTENT_PREFERENCES_SCHEMA = 1

    data class Config(
        val host: String = "127.0.0.1",
        val connectPort: Int = 0,
        val packageNameLaunch: String = "ru.yandex.yandexmaps",
        val screenOff: Boolean = false,
        val connectionMode: String = MODE_TLS,
        val offlinePort: Int = 0,
        val displayDpi: Int = DEFAULT_DISPLAY_DPI,
        val appContentDpi: Int = DEFAULT_APP_CONTENT_DPI,
        val videoProfile: String = VideoProfile.DEFAULT.preferenceValue,
        val carWifiSsid: String = "",
        val logicalDisplayMode: String = DISPLAY_MODE_HU,
        val dockAutoHide: Boolean = false,
        val usbAudio: Boolean = false,
        val resizeForMusic: Boolean = true,
        val carLifeNavigation: Boolean = false,
        val carLifeArtwork: Boolean = false,
    )

    private const val PREFS = "carbridge_adb_settings"
    private const val SYSTEM_DPI_EXPERIMENT = "system_dpi_experiment"
    private const val SYSTEM_DPI_BASELINE_SAVED = "system_dpi_baseline_saved"
    private const val SYSTEM_DPI_BASELINE_OVERRIDE = "system_dpi_baseline_override"

    fun read(context: Context): Config {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.all
        val storedDpi = values["display_dpi"] as? Int
        val storedAppContentDpi = values["app_content_dpi"] as? Int
        val appContentSchema = values["app_content_preferences_schema"] as? Int ?: 0
        val appContentUserSelected = values["app_content_dpi_user_selected"] as? Boolean ?: false
        val displaySchema = values["display_preferences_schema"] as? Int ?: 0
        val userSelectedDpi = values["display_dpi_user_selected"] as? Boolean ?: false
        val legacyLogicalDisplay = values["experimental_logical_display"] as? Boolean ?: false
        val logicalDisplayMode = normalizeLogicalDisplayMode(
            values["logical_display_mode"] as? String
                ?: if (legacyLogicalDisplay) DISPLAY_MODE_1600_900 else DISPLAY_MODE_HU
        )
        val dpi = resolveDisplayDpi(storedDpi, displaySchema, userSelectedDpi)
        val appContentDpi = contentDpiForMode(logicalDisplayMode)
        val userSelectedVideoProfile = values["video_profile_user_selected"] as? Boolean ?: false
        val videoProfile = if (userSelectedVideoProfile)
            VideoProfile.fromPreference(values["video_profile"] as? String)
        else VideoProfile.DEFAULT
        if (values.containsKey("experimental_wide_video_1920_720")) {
            prefs.edit().remove("experimental_wide_video_1920_720").apply()
        }
        if (!values.containsKey("logical_display_mode")) {
            prefs.edit().putString("logical_display_mode", logicalDisplayMode).apply()
        }
        if (displaySchema < DISPLAY_PREFERENCES_SCHEMA) {
            // Migrate an old implicit default to the larger CarBridge chrome scale;
            // preserve every density the user explicitly selected.
            prefs.edit().putInt("display_dpi", dpi)
                .putInt("display_preferences_schema", DISPLAY_PREFERENCES_SCHEMA)
                .putBoolean("display_dpi_user_selected", userSelectedDpi ||
                    (storedDpi in DISPLAY_DPI_OPTIONS && storedDpi != 120 && storedDpi != 160))
                .apply()
        }
        if (appContentSchema < APP_CONTENT_PREFERENCES_SCHEMA) {
            // 160 dpi was the old implicit default. The user reported that 200 dpi
            // gives the best map labels and controls, so migrate only that default.
            prefs.edit().putInt("app_content_dpi", appContentDpi)
                .putInt("app_content_preferences_schema", APP_CONTENT_PREFERENCES_SCHEMA)
                .putBoolean("app_content_dpi_user_selected", appContentUserSelected ||
                    (storedAppContentDpi in APP_CONTENT_DPI_OPTIONS && storedAppContentDpi != 160))
                .apply()
        }
        return Config(
            host = prefs.getString("host", "127.0.0.1")?.takeIf { it.isNotBlank() } ?: "127.0.0.1",
            connectPort = prefs.getInt("connect_port", 0).takeIf { it in 1..65535 } ?: 0,
            packageNameLaunch = prefs.getString("launch_package", "ru.yandex.yandexmaps")
                ?.takeIf { it.isNotBlank() } ?: "ru.yandex.yandexmaps",
            screenOff = prefs.getBoolean("screen_off", false),
            connectionMode = if (prefs.getString("connection_mode", MODE_TLS) == MODE_LOCAL_TCP)
                MODE_LOCAL_TCP else MODE_TLS,
            offlinePort = prefs.getInt("offline_port", 0).takeIf { it in 1..65535 } ?: 0,
            // Reject wrong preference types and values instead of passing them to the encoder.
            displayDpi = dpi,
            appContentDpi = appContentDpi,
            videoProfile = videoProfile.preferenceValue,
            carWifiSsid = prefs.getString("car_wifi_ssid", "")?.trim().orEmpty(),
            logicalDisplayMode = logicalDisplayMode,
            dockAutoHide = prefs.getBoolean("dock_auto_hide", false),
            usbAudio = prefs.getBoolean("usb_audio", false),
            resizeForMusic = prefs.getBoolean("resize_for_music", true),
            carLifeNavigation = prefs.getBoolean("carlife_navigation", false),
            carLifeArtwork = prefs.getBoolean("carlife_artwork", false),
        )
    }

    fun save(context: Context, config: Config) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("host", config.host.trim().ifEmpty { "127.0.0.1" })
            .putInt("connect_port", config.connectPort.takeIf { it in 1..65535 } ?: 0)
            .putString("launch_package", config.packageNameLaunch)
            .putBoolean("screen_off", config.screenOff)
            .putString("connection_mode", if (config.connectionMode == MODE_LOCAL_TCP) MODE_LOCAL_TCP else MODE_TLS)
            .putInt("offline_port", config.offlinePort.takeIf { it in 1..65535 } ?: 0)
            .putInt("display_dpi", normalizeDisplayDpi(config.displayDpi))
            .putInt("app_content_dpi", contentDpiForMode(config.logicalDisplayMode))
            .putInt("display_preferences_schema", DISPLAY_PREFERENCES_SCHEMA)
            .putBoolean("display_dpi_user_selected", prefs.all["display_dpi_user_selected"] as? Boolean ?: false)
            .putString("video_profile", VideoProfile.fromPreference(config.videoProfile).preferenceValue)
            .putString("car_wifi_ssid", config.carWifiSsid.trim().take(32))
            .putString("logical_display_mode", normalizeLogicalDisplayMode(config.logicalDisplayMode))
            .putBoolean("dock_auto_hide", config.dockAutoHide)
            .putBoolean("usb_audio", config.usbAudio)
            .putBoolean("resize_for_music", config.resizeForMusic)
            .putBoolean("carlife_navigation", config.carLifeNavigation)
            .putBoolean("carlife_artwork", config.carLifeArtwork)
            .putBoolean("experimental_logical_display", config.logicalDisplayMode == DISPLAY_MODE_1600_900)
            .apply()
    }

    fun normalizeDisplayDpi(value: Int): Int = value.takeIf { it in DISPLAY_DPI_OPTIONS } ?: DEFAULT_DISPLAY_DPI

    fun isValidAppContentDpi(value: Int): Boolean = value in 80..300 && value % 10 == 0

    fun normalizeAppContentDpi(value: Int): Int =
        value.takeIf(::isValidAppContentDpi) ?: DEFAULT_APP_CONTENT_DPI

    fun normalizeLogicalDisplayMode(value: String?): String = when (value) {
        DISPLAY_MODE_1600_900 -> DISPLAY_MODE_1600_900
        DISPLAY_MODE_1920_1080 -> DISPLAY_MODE_1920_1080
        DISPLAY_MODE_1920_960 -> DISPLAY_MODE_1920_960
        else -> DISPLAY_MODE_HU
    }

    internal fun resolveDisplayDpi(storedValue: Int?, preferenceSchema: Int, userSelected: Boolean): Int {
        if (preferenceSchema < DISPLAY_PREFERENCES_SCHEMA && !userSelected) {
            return DEFAULT_DISPLAY_DPI
        }
        return normalizeDisplayDpi(storedValue ?: DEFAULT_DISPLAY_DPI)
    }

    fun setDisplayDpi(context: Context, dpi: Int) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("display_dpi", normalizeDisplayDpi(dpi))
            .putInt("display_preferences_schema", DISPLAY_PREFERENCES_SCHEMA)
            .putBoolean("display_dpi_user_selected", true)
            .apply()
    }

    fun setAppContentDpi(context: Context, dpi: Int) {
        val normalized = normalizeAppContentDpi(dpi)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("app_content_dpi", normalized)
            .putInt("app_content_preferences_schema", APP_CONTENT_PREFERENCES_SCHEMA)
            .putBoolean("app_content_dpi_user_selected", true)
            .apply()
    }

    fun systemDpiExperiment(context: Context): Int? = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(SYSTEM_DPI_EXPERIMENT, -1).takeIf { it in 160..300 && it % 10 == 0 }

    fun hasSystemDpiBaseline(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(SYSTEM_DPI_BASELINE_SAVED, false)

    fun systemDpiBaselineOverride(context: Context): Int? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SYSTEM_DPI_BASELINE_SAVED, false)) return null
        return prefs.getInt(SYSTEM_DPI_BASELINE_OVERRIDE, -1).takeIf { it > 0 }
    }

    /** Legacy v0.26 phone-density experiment state, retained only to restore the phone safely. */
    fun rememberSystemDpiBaseline(context: Context, overrideDpi: Int?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SYSTEM_DPI_BASELINE_SAVED, false)) return
        prefs.edit().putBoolean(SYSTEM_DPI_BASELINE_SAVED, true)
            .putInt(SYSTEM_DPI_BASELINE_OVERRIDE, overrideDpi ?: -1).apply()
    }

    fun clearSystemDpiExperiment(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(SYSTEM_DPI_EXPERIMENT)
            .remove(SYSTEM_DPI_BASELINE_SAVED)
            .remove(SYSTEM_DPI_BASELINE_OVERRIDE)
            .apply()
    }

    fun contentDpiForMode(mode: String): Int = when (normalizeLogicalDisplayMode(mode)) {
        DISPLAY_MODE_1920_1080, DISPLAY_MODE_1920_960 -> 300
        DISPLAY_MODE_1600_900 -> 250
        else -> 200
    }

    fun setLogicalDisplayMode(context: Context, mode: String) {
        val selectedMode = normalizeLogicalDisplayMode(mode)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("logical_display_mode", selectedMode)
            .putBoolean("experimental_logical_display", selectedMode == DISPLAY_MODE_1600_900)
            .putInt("app_content_dpi", contentDpiForMode(selectedMode))
            .putInt("app_content_preferences_schema", APP_CONTENT_PREFERENCES_SCHEMA)
            .putBoolean("app_content_dpi_user_selected", true)
            .remove("app_content_dpi_before_1920_mode")
            .apply()
    }

    fun setVideoProfile(context: Context, profile: VideoProfile, userSelected: Boolean = true) {
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("video_profile", VideoProfile.fromPreference(profile.preferenceValue).preferenceValue)
        if (userSelected) editor.putBoolean("video_profile_user_selected", true)
        editor.apply()
    }

    fun choosePackage(context: Context, packageName: String) {
        save(context, read(context).copy(packageNameLaunch = packageName))
    }

    fun setScreenOff(context: Context, enabled: Boolean) {
        save(context, read(context).copy(screenOff = enabled))
    }
}
