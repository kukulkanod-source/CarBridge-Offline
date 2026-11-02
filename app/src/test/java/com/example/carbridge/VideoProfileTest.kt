package com.example.carbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoProfileTest {
    @Test fun missingOrInvalidProfileUsesEconomyLimits() {
        listOf(null, "", "BALANCED", "smooth ", "arbitrary").forEach { value ->
            val profile = VideoProfile.fromPreference(value)
            assertEquals(VideoProfile.ECONOMY, profile)
            assertEquals(15, profile.maxFps)
            assertEquals(2_000_000, profile.bitRate)
        }
    }

    @Test fun supportedProfilesKeepStableIdentifiersAndTwoSecondKeyframes() {
        assertEquals(VideoProfile.ECONOMY, VideoProfile.fromPreference("economy"))
        assertEquals(VideoProfile.BALANCED, VideoProfile.fromPreference("balanced"))
        assertEquals(15, VideoProfile.ECONOMY.maxFps)
        assertEquals(2_000_000, VideoProfile.ECONOMY.bitRate)
        assertEquals(VideoProfile.SMOOTH, VideoProfile.fromPreference("smooth"))
        assertEquals(30, VideoProfile.SMOOTH.maxFps)
        assertEquals(4_000_000, VideoProfile.SMOOTH.bitRate)
        VideoProfile.values().forEach { assertEquals(2, it.iFrameIntervalSeconds) }
    }

    @Test fun invalidDensityFallsBackToSupportedDefault() {
        listOf(Int.MIN_VALUE, -1, 0, 159, 161, 240, Int.MAX_VALUE).forEach {
            assertEquals(120, BridgeSettings.normalizeDisplayDpi(it))
        }
        BridgeSettings.DISPLAY_DPI_OPTIONS.forEach { assertEquals(it, BridgeSettings.normalizeDisplayDpi(it)) }
    }

    @Test fun changingExistingConnectionFieldsPreservesDisplayPreferences() {
        val original = BridgeSettings.Config(displayDpi = 140, videoProfile = "economy")
        val changed = original.copy(host = "192.168.1.2", connectPort = 32100,
            connectionMode = BridgeSettings.MODE_LOCAL_TCP, offlinePort = 32101,
            packageNameLaunch = "com.example.player", screenOff = true)
        assertEquals(140, changed.displayDpi)
        assertEquals("economy", changed.videoProfile)
        assertEquals(120, BridgeSettings.Config().displayDpi)
        assertEquals("economy", BridgeSettings.Config().videoProfile)
    }

    @Test fun oldDefaultDensityMigratesOnceAndLaterExplicitChoiceSurvives() {
        assertEquals(120, BridgeSettings.resolveDisplayDpi(160, 0, false))
        assertEquals(160, BridgeSettings.resolveDisplayDpi(160, BridgeSettings.DISPLAY_PREFERENCES_SCHEMA, false))
        assertEquals(160, BridgeSettings.resolveDisplayDpi(160, 0, true))
        assertEquals(160, BridgeSettings.resolveDisplayDpi(160, BridgeSettings.DISPLAY_PREFERENCES_SCHEMA, true))
    }

    @Test fun oldNondefaultChoicesAndNewSmallDensitiesRemainSupported() {
        listOf(80, 100, 120, 140, 180, 200).forEach {
            assertEquals(it, BridgeSettings.resolveDisplayDpi(it, 0, false))
        }
        assertEquals(120, BridgeSettings.resolveDisplayDpi(null, 0, false))
        assertEquals(120, BridgeSettings.resolveDisplayDpi(999, 0, true))
        assertEquals(listOf(80, 100, 120, 140, 160, 180, 200), BridgeSettings.DISPLAY_DPI_OPTIONS)
    }
}
