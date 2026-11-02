package com.example.carbridge.scrcpy

import org.junit.Assert.*
import org.junit.Test

class HuLandscapeCommandTest {
    @Test fun everyOrientationCommandTargetsOnlyTheHuDisplay() {
        val commands = HuLandscapeCommand.commands(784)
        assertEquals(3, commands.size)
        assertTrue(commands.all { "-d 784 " in it })
        assertTrue(commands.any { it.endsWith("lock 0") })
        assertTrue(commands.any { it.endsWith("true") })
        for (invalid in listOf(0, -1)) {
            try { HuLandscapeCommand.commands(invalid); fail("Physical display accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun anEmptyPhoneScreenMustNotReplaceAnAppOnTheHu() {
        val snapshot = """
            Display: mDisplayId=0 (stacks=0)
              mFocusedApp=null
            Display: mDisplayId=784 (stacks=1)
              mFocusedApp=ActivityRecord{app}
            Display: mDisplayId=785 (stacks=0)
              mFocusedApp=null
        """.trimIndent()
        assertFalse(HuWindowSnapshot.isEmpty(snapshot, 0))
        assertFalse(HuWindowSnapshot.isEmpty(snapshot, 784))
        assertTrue(HuWindowSnapshot.isEmpty(snapshot, 785))
        assertFalse(HuWindowSnapshot.isEmpty(snapshot, 786))
        assertFalse(HuWindowSnapshot.isEmpty("unrecognized dump", 784))
    }
}
