package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private fun buildSnapshot(withPeer: Boolean = true): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "direct-guided",
                authorityMode = "LIVE",
                startedAtEpochMillis = 1L,
                meshStateLabel = MeshLinkState.Uninitialized.toString(),
                selectedPeerId = if (withPeer) "peer-1" else null,
                historyStatus = ReferenceHistoryStatus.LIVE,
            ),
        peers =
            if (withPeer) {
                listOf(
                    PeerSnapshot(
                        peerId = "peer-1",
                        peerSuffix = "peer-1",
                        trustState = PeerTrustState.TRUSTED,
                        connectionState = PeerConnectionSnapshotState.CONNECTED,
                    )
                )
            } else {
                emptyList()
            },
        timeline =
            if (withPeer) {
                listOf(
                    TimelineEntry(
                        entryId = "entry-1",
                        sessionId = "session-1",
                        occurredAtEpochMillis = 2L,
                        family = TimelineFamily.DIAGNOSTIC,
                        severity = TimelineSeverity.INFO,
                        title = "peer.discovered",
                        detail = "peerId=peer-1 peer.discovered routeAvailable=true",
                        peerSuffix = "peer-1",
                    )
                )
            } else {
                emptyList()
            },
        activePowerModeLabel = "automatic",
    )
}

class DeferredReferenceMeshLinkControllerTest {
    @Test
    fun factory_is_not_called_until_the_delegate_is_used() {
        var factoryCalls = 0
        val deferred = DeferredReferenceMeshLinkController {
            factoryCalls += 1
            FakeController()
        }

        assertEquals(0, factoryCalls)
        assertTrue(deferred.snapshot.value.peers.isEmpty())
        assertEquals(1, factoryCalls)
    }

    @Test
    fun delegate_snapshot_and_methods_forward_through_the_lazy_controller() {
        var started = false
        val deferred = DeferredReferenceMeshLinkController {
            object : ReferenceMeshLinkController {
                override val snapshot: StateFlow<ReferenceControllerSnapshot> =
                    MutableStateFlow(buildSnapshot())

                override suspend fun start(): Unit {
                    started = true
                }

                override suspend fun pause(): Unit = Unit

                override suspend fun resume(): Unit = Unit

                override suspend fun stop(): Unit = Unit

                override suspend fun sendPayload(
                    peerId: String,
                    payloadText: String,
                    priority: DeliveryPriority,
                ): Unit {
                    assertEquals("peer-1", peerId)
                    assertEquals("hello", payloadText)
                    assertEquals(DeliveryPriority.NORMAL, priority)
                }

                override suspend fun forgetPeer(peerId: String): Unit {
                    assertEquals("peer-1", peerId)
                }
            }
        }

        assertFalse(started)
        assertEquals("session-1", deferred.snapshot.value.session.sessionId)
        assertTrue(deferred.snapshot.value.peers.any { it.peerId == "peer-1" })
    }

    private class FakeController : ReferenceMeshLinkController {
        override val snapshot: StateFlow<ReferenceControllerSnapshot> =
            MutableStateFlow(buildSnapshot(withPeer = false))

        override suspend fun start(): Unit = Unit

        override suspend fun pause(): Unit = Unit

        override suspend fun resume(): Unit = Unit

        override suspend fun stop(): Unit = Unit

        override suspend fun sendPayload(
            peerId: String,
            payloadText: String,
            priority: DeliveryPriority,
        ): Unit = Unit

        override suspend fun forgetPeer(peerId: String): Unit = Unit
    }
}
