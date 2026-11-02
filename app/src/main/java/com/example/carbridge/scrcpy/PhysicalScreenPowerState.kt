package com.example.carbridge.scrcpy

/**
 * Serialises the user's panel preference with asynchronous sleep recovery.
 * A late OFF/recovery must never override a newer ON request or a stopped source.
 */
internal class PhysicalScreenPowerState {
    data class Request(val on: Boolean, val revision: Long)
    data class Recovery(val id: Long, val revision: Long, val on: Boolean)

    private var revision = 0L
    private var nextRecoveryId = 0L
    private var desired: Request? = null
    private var pending: Request? = null
    private var recovery: Recovery? = null
    private var closed = false

    @Synchronized
    fun request(on: Boolean) {
        if (closed) return
        desired = Request(on, ++revision)
        pending = desired
        recovery = null
    }

    @Synchronized
    fun takePending(): Request? = pending.also { pending = null }

    @Synchronized
    fun isCurrent(request: Request): Boolean = !closed && desired == request

    /** One bounded wake sequence; duplicate sleep notifications never enqueue retries. */
    @Synchronized
    fun beginRecovery(hasVideo: Boolean): Recovery? {
        val request = desired ?: return null
        if (closed || !hasVideo || recovery != null) return null
        pending = null
        return Recovery(++nextRecoveryId, revision, request.on).also { recovery = it }
    }

    @Synchronized
    fun isCurrent(recovery: Recovery): Boolean =
        !closed && this.recovery == recovery && desired?.on == recovery.on && revision == recovery.revision

    /** No retry: a future hardware sleep must be a new service-level decision. */
    @Synchronized
    fun finishRecovery(completed: Recovery): Boolean {
        if (recovery != completed) return false
        recovery = null
        return false
    }

    @Synchronized
    fun close() {
        closed = true
        desired = null
        pending = null
        recovery = null
    }
}
