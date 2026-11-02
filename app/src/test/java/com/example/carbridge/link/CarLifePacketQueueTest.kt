package com.example.carbridge.link

import org.junit.Assert.*
import org.junit.Test

class CarLifePacketQueueTest {
    private fun video(size: Int) = CarLifePacketQueue.Packet(Wire.CH_VIDEO, ByteArray(size))
    private fun command(size: Int) = CarLifePacketQueue.Packet(Wire.CH_CMD, ByteArray(size))

    @Test fun commandsPassQueuedVideoButNeverInterruptActivePacket() {
        val queue = CarLifePacketQueue()
        val first = video(100)
        val second = video(200)
        val control = command(8)
        queue.offer(first)
        assertSame(first, queue.poll())
        queue.offer(second)
        queue.offer(control)
        assertEquals(308, queue.bytes)
        queue.complete(first)
        assertSame(control, queue.poll())
        queue.complete(control)
        assertSame(second, queue.poll())
        queue.complete(second)
        assertEquals(0, queue.bytes)
    }

    @Test fun droppingVideoRetainsActivePacketAndProtocolCommands() {
        val queue = CarLifePacketQueue()
        val active = video(90)
        val control = command(12)
        queue.offer(active)
        queue.poll()
        queue.offer(video(600_000))
        queue.offer(control)
        assertEquals(600_000, queue.discardVideo())
        assertEquals(102, queue.bytes)
        assertSame(control, queue.poll())
        queue.complete(active)
        queue.complete(control)
        assertEquals(0, queue.bytes)
        assertFalse(queue.hasPending)
    }

    @Test fun closingDiscardsOnlyPendingPacketsAndRejectsFurtherWrites() {
        val queue = CarLifePacketQueue()
        val active = video(800_000)
        queue.offer(active)
        queue.poll()
        queue.offer(video(20))
        queue.offer(command(8))
        queue.close()
        assertEquals(800_000, queue.bytes)
        assertNull(queue.poll())
        assertFalse(queue.offer(command(8)))
        queue.complete(active)
        assertEquals(0, queue.bytes)
    }
}
