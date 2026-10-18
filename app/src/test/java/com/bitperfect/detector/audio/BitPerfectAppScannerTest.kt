package com.bitperfect.detector.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class BitPerfectAppScannerTest {

    private val marker = "setPreferredMixerAttributes".toByteArray()

    private fun bytesWithMarkerAt(size: Int, offset: Int): ByteArray {
        val data = ByteArray(size) { 'x'.code.toByte() }
        marker.copyInto(data, offset)
        return data
    }

    private fun contains(data: ByteArray) =
        BitPerfectAppScanner.containsBytes(ByteArrayInputStream(data), marker)

    @Test
    fun findsMarkerInSmallInput() {
        assertTrue(contains(bytesWithMarkerAt(100, 10)))
    }

    @Test
    fun findsMarkerSplitAcrossReadBlocks() {
        // The first block read is 64 KiB: the marker starts 5 bytes before the boundary.
        assertTrue(contains(bytesWithMarkerAt(200_000, 64 * 1024 - 5)))
    }

    @Test
    fun findsMarkerAtEnd() {
        assertTrue(contains(bytesWithMarkerAt(150_000, 150_000 - marker.size)))
    }

    @Test
    fun noMarkerReturnsFalse() {
        assertFalse(contains(ByteArray(300_000) { 'x'.code.toByte() }))
    }

    @Test
    fun partialMarkerReturnsFalse() {
        val partial = "setPreferredMixerAttribute".toByteArray()
        assertFalse(contains(ByteArray(1000).also { partial.copyInto(it, 500) }))
    }
}
