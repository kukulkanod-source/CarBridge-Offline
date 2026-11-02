package com.example.carbridge.scrcpy

import org.junit.Assert.*
import org.junit.Test

class PhonePanelStateTest {
    @Test
    fun powerPressShowsPhoneThenNextPressDimsAndDuplicatesDoNotToggleTwice() {
        val state = PhonePanelState()
        state.preference(true).also(state::complete)
        assertFalse(state.visible)
        val wake = state.sleep()!!
        assertTrue(wake.on)
        repeat(5) { assertNull(state.sleep()) }
        state.complete(wake)
        val dim = state.sleep()!!
        assertFalse(dim.on)
        repeat(5) { assertNull(state.sleep()) }
        state.complete(dim)
        assertTrue(state.sleep()!!.on)
    }

    @Test
    fun explicitNewChoiceCannotBeUnlockedByOldRecoveryCompletion() {
        val state = PhonePanelState()
        state.preference(true).also(state::complete)
        val wake = state.sleep()!!
        val dim = state.explicit(false)
        state.complete(wake)
        assertFalse(state.isCurrent(wake))
        assertTrue(state.isCurrent(dim))
        assertNull(state.sleep())
        state.complete(dim)
        assertTrue(state.sleep()!!.on)
    }

    @Test
    fun disabledPreferenceDoesNotFightNormalPhoneSleepButExplicitDimEnablesSessionControl() {
        val state = PhonePanelState()
        state.preference(false).also(state::complete)
        assertTrue(state.visible)
        assertNull(state.sleep())
        state.explicit(false).also(state::complete)
        assertFalse(state.visible)
        assertTrue(state.sleep()!!.on)
        state.preference(false).also(state::complete)
        assertNull(state.sleep())
    }

    @Test
    fun stopInvalidatesChoicesAndClearsSleepManagement() {
        val state = PhonePanelState()
        state.preference(true).also(state::complete)
        val wake = state.sleep()!!
        state.stop()
        state.complete(wake)
        assertTrue(state.visible)
        assertFalse(state.isCurrent(wake))
        assertNull(state.sleep())
    }
}
