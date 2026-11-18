package ch.trancee.meshlink.internal

import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class UnsupportedRadioPortTest {
    @Test
    fun nodeOnAPlatformWithoutBluetoothIsBlockedAsUnsupported() =
        runTest {
            // Arrange
            val config = meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = "No radio" }.build()
            val node = MeshLink.create(config, MeshPlatform(UnsupportedRadioPort()))

            // Act
            node.start()
            val state = node.state.value
            node.close()

            // Assert
            assertEquals(MeshState.Blocked(setOf(Requirement.Unsupported)), state)
        }

    @Test
    fun radioRefusesEveryOperationAsUnsupportedAndReportsNothing() =
        runTest {
            // Arrange
            val radio = UnsupportedRadioPort().radio

            // Act
            val results =
                listOf(
                    radio.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH)),
                    radio.startScanning(),
                    radio.serveCapability(ByteArray(0)),
                    radio.connect(object : PeerHandle {}),
                    radio.listenForChannels(),
                )
            radio.stopAdvertising()
            radio.stopScanning()
            radio.stopListening()

            // Assert
            results.forEach { assertEquals(RadioFailure.UNSUPPORTED, assertIs<RadioResult.Failed>(it).reason) }
            assertEquals(emptyList(), radio.events.toList())
            assertNull(radio.peripheralLinkCap)
            assertFalse(radio.supportsLe2MPhy)
        }

    @Test
    fun clockIsTheSystemClock() {
        assertSame(SystemClock, UnsupportedRadioPort().clock)
    }
}
