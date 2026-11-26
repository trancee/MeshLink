package ch.trancee.meshlink

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.internal.DiagnosticsExport
import ch.trancee.meshlink.internal.DiagnosticsHub
import ch.trancee.meshlink.internal.ExportHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private typealias Role = DiagnosticEvent.LinkOpened.Role

/**
 * Renders one event of every catalog code and checks each field against an allowlist (S1, ADR 0020
 * decision 3): an event shows names, numbers, a [PeerRef], a [LinkRef] or a message id, and never a
 * key, payload, tag, salt, certificate, NodeId or Bluetooth address. An export (decision 6) adds only the
 * configuration, with the mesh ID and mesh key redacted, and a snapshot of names, numbers and [PeerRef]s.
 */
class DiagnosticRedactionTest {
    private val root = File(checkNotNull(System.getProperty("reference.root")) { "reference.root is not set" })

    @Test
    fun everyEventShowsOnlyNamesNumbersReferencesAndMessageIds() {
        // Arrange
        val node = NodeId(ByteArray(NODE_ID_BYTES) { (it + 1).toByte() })
        val hub = DiagnosticsHub(CoroutineScope(Job()), debug = false, crypto = SodiumCryptoProvider)
        hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)
        val events = events(hub.peer(node), hub.link())
        val catalog =
            Regex("""DiagnosticEvent\("(ML\d{4})"""")
                .findAll(File(root, SOURCE).readText())
                .map { it.groupValues[1] }
                .toSet()

        // Act
        val rendered = events.map { it.toString() }

        // Assert
        assertEquals(catalog, events.map { it.code }.toSet(), "one event of every catalog code")
        rendered.forEach { line ->
            val match = assertNotNull(EVENT.matchEntire(line), line)
            match.groupValues[1].split(FIELD).filter { it.isNotEmpty() }.forEach { field ->
                val (name, value) = field.split("=", limit = 2)
                val allowed = if (name == "messageId") MESSAGE_ID else VALUE
                assertTrue(allowed.matches(value), "$line: $name=$value")
            }
            assertTrue(node.toByteArray().toHexString() !in line, line)
        }
    }

    @Test
    fun anExportShowsTheRedactedConfigurationAndOnlyNamesNumbersReferencesAndMessageIds() {
        // Arrange
        val node = NodeId(ByteArray(NODE_ID_BYTES) { (it + 1).toByte() })
        val key = ByteArray(MESH_KEY_BYTES) { (it + 0x40).toByte() }
        val config =
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = MESH_ID
                meshKey = key.copyOf()
            }.build()
        val hub = DiagnosticsHub(CoroutineScope(Job()), debug = false, crypto = SodiumCryptoProvider)
        hub.begin(SecurityProfile.Classical, Preset.OfflineChat)
        val peer = hub.peer(node)
        val link = LinkSnapshot(peer, LinkSnapshot.Bearer.Gatt, LinkSnapshot.Phy.Le1M, 247, Role.Central)
        val counters = DiagnosticCounters(mapOf("ML1001" to 1L), 1, 0, 1)
        val snapshot =
            DiagnosticsSnapshot(
                config.redacted(),
                SecurityProfile.Classical,
                DensityClass.Low,
                listOf(link),
                QueueCounts(0, 0, 0, 1),
                counters,
            )
        val history = events(peer, hub.link()).map { DiagnosticsHub.Recorded(it, 0) }

        // Act
        val json = DiagnosticsExport.encode(ExportHeader("0.1.0", 1, "Android", "15 (API 35)"), snapshot, history)

        // Assert
        val strings =
            STRING
                .findAll(json)
                .filter { it.groupValues[2].isEmpty() }
                .map { it.groupValues[1].replace("\\\"", "\"") }
                .toList()
        val lines = config.describe().lines().filterNot { it.startsWith("meshId = ") }
        val patterns = listOf(VALUE, NAME, MESSAGE_ID)
        val allowed = strings.filterNot { string -> patterns.any { it.matches(string) } || string in HEADER + lines }
        assertEquals(listOf("meshId = <redacted> (override)"), allowed)
        assertTrue(MESH_ID !in json, json)
        assertTrue(key.toHexString() !in json && node.toByteArray().toHexString() !in json, json)
    }

    private fun events(
        peer: PeerRef,
        link: LinkRef,
    ): List<DiagnosticEvent> =
        listOf(
            DiagnosticEvent.Started(SecurityProfile.PostQuantum, Preset.Custom("Quiet chat", Preset.OfflineChat) {}),
            DiagnosticEvent.Stopped(DiagnosticEvent.Stopped.Reason.IdentityReset),
            DiagnosticEvent.UnsecuredProfile(),
            DiagnosticEvent.OwnCertificateRenewed(),
            DiagnosticEvent.ScanWindow(DiagnosticEvent.ScanWindow.Phase.Started),
            DiagnosticEvent.ScanFailed(platformCode = 2),
            DiagnosticEvent.AdvertisingFailed(platformCode = null),
            DiagnosticEvent.GattServiceFailed(platformCode = 133),
            DiagnosticEvent.ScannerRegistrationFailed(),
            DiagnosticEvent.StaleAddressMarked(),
            DiagnosticEvent.DensityClassChanged(DensityClass.Low, DensityClass.High),
            DiagnosticEvent.LinkOpened(peer, DiagnosticEvent.LinkOpened.Role.Central, mtu = 517),
            DiagnosticEvent.LinkClosed(peer, DiagnosticEvent.LinkClosed.Reason.ConnectionLost, byPeer = false, 8),
            DiagnosticEvent.ConnectFailed(link, DiagnosticEvent.ConnectFailed.Reason.PeerUnreachable, attempt = 3, 147),
            DiagnosticEvent.LinkCapReached(cap = 7),
            DiagnosticEvent.BearerFallback(peer, DiagnosticEvent.BearerFallback.Reason.RadioError),
            DiagnosticEvent.GattOperationRetried(platformCode = 201),
            DiagnosticEvent.UnknownLinkFrameDropped(type = 0x7f),
            DiagnosticEvent.ProtocolVersionRejected(theirVersion = 2),
            DiagnosticEvent.BusyReply(
                DiagnosticEvent.BusyReply.Direction.Sent,
                DiagnosticEvent.BusyReply.Reason.Budget,
            ),
            DiagnosticEvent.BearerSwitched(peer),
            DiagnosticEvent.HandshakeFailed(link, DiagnosticEvent.HandshakeFailed.Reason.Certificate),
            DiagnosticEvent.HandshakeLimitDropped(DiagnosticEvent.HandshakeLimitDropped.Limit.Global),
            DiagnosticEvent.BroadcastSignatureInvalid(),
            DiagnosticEvent.RouteFound(peer, hops = 4),
            DiagnosticEvent.RouteLost(peer),
            DiagnosticEvent.UnknownRoutedFrameRelayed(type = 0xbf),
            DiagnosticEvent.ExpiryFarFromLocalTime(skewSeconds = -31),
            DiagnosticEvent.HeldMessageEvicted(DiagnosticEvent.HeldMessageEvicted.Reason.LinkShare),
            DiagnosticEvent.CertificateLookup(peer, attempts = 2),
            DiagnosticEvent.CertificateLookupUnanswered(peer, attempts = 5),
            DiagnosticEvent.InvalidCertificateReply(),
            DiagnosticEvent.DiagnosticsDropped(dropped = 43),
            DiagnosticEvent.ReadTrackingEvicted(ByteArray(MESSAGE_ID_BYTES) { it.toByte() }),
            DiagnosticEvent.InboxFull(DiagnosticEvent.InboxFull.Bound.Sender),
            DiagnosticEvent.ReassemblyBudgetExhausted(),
        )

    private companion object {
        const val SOURCE = "meshlink/src/commonMain/kotlin/ch/trancee/meshlink/DiagnosticEvent.kt"
        const val NODE_ID_BYTES = 32
        const val MESSAGE_ID_BYTES = 12
        const val MESH_KEY_BYTES = 32
        const val MESH_ID = "Secret Garden"

        /** A JSON string, its text still escaped in group 1, and `:` in group 2 when it is a key. */
        val STRING = Regex(""""((?:[^"\\]|\\.)*)"(:?)""")

        /** The header values of the export, and a name with digits: a code or a PHY. */
        val HEADER = setOf("0.1.0", "Android", "15 (API 35)")
        val NAME = Regex("[A-Z][A-Za-z0-9]*")
        val FIELD = Regex(", (?=[a-z][A-Za-z]*=)")
        val EVENT = Regex("""ML\d{4} [A-Z][A-Za-z]*\((.*)\) x\d+""")

        /** A name, a flag, a signed number, a reference, or the app's own name for a custom preset. */
        val VALUE =
            Regex(
                listOf(
                    """[A-Z][A-Za-z]*|true|false|-?\d+""",
                    """peer-[0-9a-f]{8}|link-\d+""",
                    """Custom\("[^"]*", base = [A-Z][A-Za-z]*\)""",
                ).joinToString("|"),
            )

        /** A 12-byte message id, which the app already holds from its send. */
        val MESSAGE_ID = Regex("[0-9a-f]{24}")
    }
}
