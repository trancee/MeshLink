package ch.trancee.meshlink.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals

class L2capReadPumpTest {
    @Test
    fun selectReadPollIntervalMsUsesActivePollingAfterRecentReadProgress(): Unit {
        // Arrange
        val activePollIntervalMs = 1L
        val idlePollIntervalMs = 5L

        // Act
        val pollIntervalMs =
            selectReadPollIntervalMs(
                readBytes = 8,
                activePollIntervalMs = activePollIntervalMs,
                idlePollIntervalMs = idlePollIntervalMs,
            )

        // Assert
        assertEquals(activePollIntervalMs, pollIntervalMs)
    }

    @Test
    fun selectReadPollIntervalMsUsesIdlePollingWhenNoBytesWereRead(): Unit {
        // Arrange
        val activePollIntervalMs = 1L
        val idlePollIntervalMs = 5L

        // Act
        val pollIntervalMs =
            selectReadPollIntervalMs(
                readBytes = 0,
                activePollIntervalMs = activePollIntervalMs,
                idlePollIntervalMs = idlePollIntervalMs,
            )

        // Assert
        assertEquals(idlePollIntervalMs, pollIntervalMs)
    }
}
