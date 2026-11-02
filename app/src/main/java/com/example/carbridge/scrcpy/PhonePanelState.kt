package com.example.carbridge.scrcpy

/** Session-only panel choice. SCREEN_ON from our own WAKEUP never changes it. */
internal class PhonePanelState {
    data class Choice(val on: Boolean, val revision: Long)

    var visible = true
        private set
    private var managed = false
    private var revision = 0L
    private var inFlight: Choice? = null

    fun preference(dim: Boolean): Choice {
        managed = dim
        return choose(!dim)
    }

    fun explicit(on: Boolean): Choice {
        if (!on) managed = true
        return choose(on)
    }

    /** DisplayListener and SCREEN_OFF report one transition: latch until recovery ends. */
    fun sleep(): Choice? {
        if (!managed || inFlight != null) return null
        return choose(!visible)
    }

    fun complete(choice: Choice) {
        if (inFlight == choice) inFlight = null
    }

    fun isCurrent(choice: Choice): Boolean = revision == choice.revision

    fun stop() {
        revision++
        inFlight = null
        managed = false
        visible = true
    }

    private fun choose(on: Boolean): Choice {
        visible = on
        return Choice(on, ++revision).also { inFlight = it }
    }
}
