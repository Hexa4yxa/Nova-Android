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
