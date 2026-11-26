package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The security diagnostics of a node's own keys (ADR 0020 decision 7, `ML1xxx`). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class SecurityDiagnosticsTest {
    @Test
    fun theStaticKeyRotationReportsTheRenewedCertificateButTheFirstIssueDoesNot() =
        runTest(timeout = 5.minutes) {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node =
                MeshLink.create(
                    meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) { meshId = "diagnostics" }.build(),
                    MeshPlatform.of(platform),
                )
            val seen = diagnostics(node, "ML1004")

            // Act
            node.start()
            advanceTimeBy(11.hours)
            val beforeRenewal = seen.map { it.toString() }
            advanceTimeBy(2.hours)

            // Assert
            assertEquals(emptyList(), beforeRenewal)
            assertEquals(listOf("ML1004 OwnCertificateRenewed() x1"), seen.map { it.toString() })
        }
}
