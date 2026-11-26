package ch.trancee.meshlink

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.internal.DiagnosticsHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders one event of every catalog code and checks each field against an allowlist (S1, ADR 0020
 * decision 3): an event shows names, numbers, a [PeerRef], a [LinkRef] or a message id, and never a
 * key, payload, tag, salt, certificate, NodeId or Bluetooth address.
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

    private fun events(
        peer: PeerRef,
        link: LinkRef,
    ): List<DiagnosticEvent> =
        listOf(
            DiagnosticEvent.Started(SecurityProfile.PostQuantum, Preset.Custom("Quiet chat", Preset.OfflineChat) {}),
            DiagnosticEvent.Stopped(DiagnosticEvent.Stopped.Reason.IdentityReset),
            DiagnosticEvent.UnsecuredProfile(),
            DiagnosticEvent.ConnectFailed(link, DiagnosticEvent.ConnectFailed.Reason.PeerUnreachable, attempt = 3),
            DiagnosticEvent.LinkCapReached(cap = 7),
            DiagnosticEvent.BearerFallback(peer, DiagnosticEvent.BearerFallback.Reason.RadioError),
            DiagnosticEvent.UnknownLinkFrameDropped(type = 0x7f),
            DiagnosticEvent.HandshakeFailed(link, DiagnosticEvent.HandshakeFailed.Reason.Certificate),
            DiagnosticEvent.HandshakeLimitDropped(DiagnosticEvent.HandshakeLimitDropped.Limit.Global),
            DiagnosticEvent.DiagnosticsDropped(dropped = 43),
            DiagnosticEvent.ReadTrackingEvicted(ByteArray(MESSAGE_ID_BYTES) { it.toByte() }),
        )

    private companion object {
        const val SOURCE = "meshlink/src/commonMain/kotlin/ch/trancee/meshlink/DiagnosticEvent.kt"
        const val NODE_ID_BYTES = 32
        const val MESSAGE_ID_BYTES = 12
        val FIELD = Regex(", (?=[a-z][A-Za-z]*=)")
        val EVENT = Regex("""ML\d{4} [A-Z][A-Za-z]*\((.*)\) x\d+""")

        /** A name, a number, a reference, or the app's own name for a custom preset. */
        val VALUE =
            Regex("""[A-Z][A-Za-z]*|\d+|peer-[0-9a-f]{8}|link-\d+|Custom\("[^"]*", base = [A-Z][A-Za-z]*\)""")

        /** A 12-byte message id, which the app already holds from its send. */
        val MESSAGE_ID = Regex("[0-9a-f]{24}")
    }
}
