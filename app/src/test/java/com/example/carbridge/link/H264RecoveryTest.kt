package com.example.carbridge.link

import org.junit.Assert.*
import org.junit.Test

class H264RecoveryTest {
    private fun nal(type: Int) = byteArrayOf(0, 0, 0, 1, type.toByte(), 0x55)
    private val config = nal(7) + nal(8)

    @Test fun everyIdrCanRecoverDecoderWithoutDuplicatingExistingSets() {
        val idr = nal(5)
        assertArrayEquals(config + idr, H264.withParameterSets(idr, config))
        val complete = config + idr
        assertSame(complete, H264.withParameterSets(complete, config))
        assertArrayEquals(complete, H264.withParameterSets(nal(7) + idr, config))
        assertArrayEquals(complete, H264.withParameterSets(nal(8) + idr, config))
    }

    @Test fun audStaysFirstAndInBandSetsArePreserved() {
        val aud = nal(9)
        val sei = nal(6)
        val idr = nal(5)
        assertArrayEquals(aud + config + sei + idr, H264.withParameterSets(aud + sei + idr, config))
        val inBandSps = byteArrayOf(0, 0, 0, 1, 7, 0x66)
        val result = H264.withParameterSets(aud + inBandSps + sei + idr, config)
        assertArrayEquals(aud + inBandSps + nal(8) + sei + idr, result)
        assertEquals(listOf(9, 7, 8, 6, 5), H264.nalTypes(result))
    }

    @Test fun unusableConfigIsNotInsertedAndMixedStartCodesRemainSupported() {
        val idr = nal(5)
        assertSame(idr, H264.withParameterSets(idr, null))
        assertSame(idr, H264.withParameterSets(idr, nal(7)))
        val empty = byteArrayOf(0, 0, 1)
        assertSame(empty, H264.withParameterSets(empty, config))
        val shortCodes = H264.normalize(byteArrayOf(0, 0, 1, 9, 0x55, 0, 0, 1, 5, 0x55))
        assertEquals(listOf(9, 7, 8, 5), H264.nalTypes(H264.withParameterSets(shortCodes, config)))
        // Config may contain other NALs; only SPS/PPS are repeated.
        assertArrayEquals(config + idr, H264.withParameterSets(idr, config + nal(5)))
    }
}
