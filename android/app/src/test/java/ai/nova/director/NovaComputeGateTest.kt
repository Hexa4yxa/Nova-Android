package ai.nova.director

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovaComputeGateTest {
    @Test
    fun imageReservationCannotOverlapWithText() {
        assertTrue(NovaComputeGate.enterText())
        try {
            assertFalse(NovaComputeGate.reserveImage())
        } finally {
            NovaComputeGate.leaveText()
        }
        assertTrue(NovaComputeGate.reserveImage())
        try {
            assertFalse(NovaComputeGate.enterText())
            assertTrue(NovaComputeGate.beginImage())
            assertFalse(NovaComputeGate.beginImage())
            assertFalse(NovaComputeGate.releaseUnusedImage())
            assertFalse(NovaComputeGate.enterText())
        } finally {
            NovaComputeGate.finishImage()
        }
        assertTrue(NovaComputeGate.enterText())
        NovaComputeGate.leaveText()
    }

    @Test
    fun earlyImageAbortRestoresTextAccess() {
        assertTrue(NovaComputeGate.reserveImage())
        assertFalse(NovaComputeGate.enterText())
        assertTrue(NovaComputeGate.releaseUnusedImage())
        assertTrue(NovaComputeGate.enterText())
        NovaComputeGate.leaveText()
    }

    @Test
    fun abandonedReservationExpiresButActiveInferenceRemainsProtected() {
        val start = 50_000_000_000L
        val oneMinute = 60_000_000_000L
        val twoMinutes = 120_000_000_000L
        assertFalse(NovaComputeGate.reservationExpired(start, start + oneMinute))
        assertFalse(NovaComputeGate.reservationExpired(start, start + twoMinutes))
        assertTrue(NovaComputeGate.reservationExpired(start, start + twoMinutes + 1L))
        assertFalse(NovaComputeGate.reservationExpired(0, start + twoMinutes + 1L))

        assertTrue(NovaComputeGate.reserveImage())
        try {
            assertTrue(NovaComputeGate.beginImage())
            // Web UI cleanup cannot unlock an active native generation.
            assertFalse(NovaComputeGate.releaseUnusedImage())
            assertFalse(NovaComputeGate.enterText())
        } finally {
            NovaComputeGate.finishImage()
        }
    }

    @Test
    fun duplicateImageReservationNeverStarts() {
        assertTrue(NovaComputeGate.reserveImage())
        try {
            assertFalse(NovaComputeGate.reserveImage())
            assertTrue(NovaComputeGate.beginImage())
            assertFalse(NovaComputeGate.releaseUnusedImage())
        } finally {
            NovaComputeGate.finishImage()
        }
    }
}
