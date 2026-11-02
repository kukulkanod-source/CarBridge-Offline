package com.example.carbridge.link

import java.util.ArrayDeque

/** Only complete, not-yet-written packets may be discarded or reordered. */
internal class CarLifePacketQueue {
    data class Packet(val channel: Int, val bytes: ByteArray, val createdNs: Long = System.nanoTime())
    private val commands = ArrayDeque<Packet>()
    private val video = ArrayDeque<Packet>()
    private val audio = ArrayDeque<Packet>()
    private var audioBytes = 0
    @Volatile var droppedAudioBytes = 0L
        private set
    private var byteCount = 0
    private var closed = false

    val bytes: Int @Synchronized get() = byteCount
    val hasPending: Boolean @Synchronized get() = commands.isNotEmpty() || audio.isNotEmpty() || video.isNotEmpty()

    @Synchronized fun offer(packet: Packet): Boolean {
        if (closed) return false
        if (packet.channel == Wire.CH_VIDEO) video.addLast(packet) else commands.addLast(packet)
        byteCount += packet.bytes.size
        return true
    }

    /** Only PCM data enters this bounded queue. INIT/STOP remain reliable commands. */
    @Synchronized fun offerAudio(packet: Packet): Boolean {
        if (closed) return false
        while (audio.isNotEmpty() && audioBytes + packet.bytes.size > 40000) dropAudioHead()
        if (packet.bytes.size > 40000) return false
        audio.addLast(packet); audioBytes += packet.bytes.size; byteCount += packet.bytes.size
        return true
    }
    private fun dropAudioHead() {
        val old = audio.removeFirst()
        audioBytes -= old.bytes.size; byteCount -= old.bytes.size; droppedAudioBytes += old.bytes.size
    }
    @Synchronized fun discardAudio(): Int {
        val count = audioBytes
        while (audio.isNotEmpty()) dropAudioHead()
        return count
    }
    @Synchronized fun poll(): Packet? {
        val cutoff = System.nanoTime() - 200_000_000L
        while (audio.isNotEmpty() && audio.first.createdNs < cutoff) dropAudioHead()
        return when {
            commands.isNotEmpty() -> commands.removeFirst()
            audio.isNotEmpty() -> audio.removeFirst().also { audioBytes -= it.bytes.size }
            video.isNotEmpty() -> video.removeFirst()
            else -> null
        }
    }

    // The active packet stays counted until its entire USB write finishes.
    @Synchronized fun complete(packet: Packet) { byteCount -= packet.bytes.size }

    @Synchronized fun discardVideo(): Int {
        var removed = 0
        while (video.isNotEmpty()) removed += video.removeFirst().bytes.size
        byteCount -= removed
        return removed
    }

    @Synchronized fun close() {
        closed = true
        discardVideo()
        discardAudio()
        while (commands.isNotEmpty()) byteCount -= commands.removeFirst().bytes.size
    }
}
