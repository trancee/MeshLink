package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A running node's secured link handshakes (spec "Link handshake", "Handshake limits"): its
 * credentials for this run, the D-20 bounds across all its links, and the [diagnostics] they emit.
 * Not thread-safe; the node calls it on its serial dispatcher.
 *
 * [credentials] are this run's static key and certificate; [meshKey] gates the mesh with `XXpsk3`.
 * A gated mesh also has resolving [keys]: each payload carries this node's current key, and each
 * verified peer's key is learned (spec "Handshake payload"). An open mesh sends none and ignores the
 * peer's. Each certificate a payload proves goes to [certified].
 */
internal class Handshakes(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val credentials: LinkCredentials,
    meshKey: ByteArray?,
    private val diagnostics: MutableSharedFlow<LinkDiagnostic>,
    private val certified: (NodeId, IdentityCertificate) -> Unit = { _, _ -> },
) {
    /** A gated mesh's resolving keys for this run; an open mesh has none. */
    val keys: ResolvingKeys? = meshKey?.let { ResolvingKeys(crypto, clock.wallClockMillis()) }

    private val psk = meshKey?.let { Derivations(crypto).meshPsk(it, credentials.meshIdDigest) }
    private val certificate = credentials.certificate.encode()
    private var halfOpen = 0

    // When each remote address last started a handshake this node answered, monotonic milliseconds.
    private val recent = mutableMapOf<PeerHandle, Long>()

    /** The handshake of a new link with [handle], binding the peripheral's capability record [versions]. */
    fun link(
        handle: PeerHandle,
        versions: List<Int>,
    ): LinkHandshake = LinkHandshake(this, handle, versions)

    /** A Noise handshake in [role] that binds [versions] and [selected] into its prologue. */
    fun noise(
        role: NoiseRole,
        versions: List<Int>,
        selected: Int,
    ): NoiseHandshake {
        val prologue = Derivations.linkPrologue(versions, selected, credentials.profile, credentials.meshIdDigest)
        val pattern =
            when (credentials.profile) {
                WireProfile.POST_QUANTUM -> if (psk == null) NoisePattern.XX_HFS else NoisePattern.XX_HFS_PSK3
                else -> if (psk == null) NoisePattern.XX else NoisePattern.XX_PSK3
            }
        return NoiseHandshake(crypto, pattern, role, prologue, NoiseKeys(credentials.static, psk))
    }

    /** This node's payload for message 2 or 3: its current resolving key in a gated mesh, and its certificate. */
    fun payload(): ByteArray = HandshakePayload.of(certificate, keys?.own(clock.wallClockMillis())).encode()

    /**
     * Counts a new handshake as half-open, or returns the bound it breaks. A responder passes the
     * initiator's [handle]; an initiator passes `null`, since the neighbor bound is for answering.
     */
    fun admit(handle: PeerHandle?): HandshakeLimit? {
        val now = clock.monotonicMillis()
        recent.entries.removeAll { now - it.value >= NEIGHBOR_INTERVAL_MILLIS }
        val limit =
            when {
                halfOpen >= HALF_OPEN_GLOBAL -> HandshakeLimit.GLOBAL
                handle != null && handle in recent -> HandshakeLimit.NEIGHBOR_INTERVAL
                else -> null
            }
        if (limit == null) {
            halfOpen++
            handle?.let { recent[it] = now }
        }
        return limit
    }

    /** Ends a handshake [admit] counted. */
    fun finish() {
        halfOpen--
    }

    /**
     * The NodeId the peer's message [payload] proves, or `null` if it has no valid certificate for
     * this mesh's profile whose `x25519_key` is the Noise [remoteStatic] key (spec "Handshake payload").
     * In a gated mesh, learns the proven peer's resolving key if the payload carries a valid one.
     * Passes the proven certificate to [certified].
     */
    fun verify(
        payload: ByteArray,
        remoteStatic: ByteArray,
    ): NodeId? {
        val decoded = (HandshakePayload.decode(payload) as? Decoded.Valid)?.value
        val certificate =
            decoded
                ?.certificate
                ?.let { IdentityCertificate.decode(it) as? Decoded.Valid }
                ?.value
        val peer =
            certificate?.let {
                val nodeId = Derivations(crypto).nodeId(it.ed25519Key)
                val check = IdentityCertificate.validate(crypto, it.encode(), credentials.profile, nodeId, clock)
                NodeId(nodeId).takeIf { _ ->
                    check is CertificateCheck.Valid && it.x25519Key.contentEquals(remoteStatic)
                }
            }
        peer?.let { id ->
            certified(id, certificate)
            decoded.resolvingKey?.let { keys?.learn(id, it, clock.wallClockMillis()) }
        }
        return peer
    }

    fun report(diagnostic: LinkDiagnostic) {
        diagnostics.tryEmit(diagnostic)
    }

    private companion object {
        /** Spec `handshake-half-open-global`. */
        const val HALF_OPEN_GLOBAL = 8

        /** Spec `handshake-neighbor-interval`. */
        const val NEIGHBOR_INTERVAL_MILLIS = 2_000L
    }
}

/**
 * A node's link credentials for one session: a fresh X25519 [static] key and the [certificate] that
 * binds it to the installation identity, for the mesh of [meshIdDigest] in [profile]. In
 * `PostQuantum` the certificate also binds a fresh X-Wing key pair [xWing], which first messages are
 * sealed to.
 */
internal class LinkCredentials(
    val profile: WireProfile,
    val meshIdDigest: ByteArray,
    val static: KeyPair,
    val certificate: IdentityCertificate,
    val xWing: KeyPair?,
) {
    companion object {
        /** Fresh credentials for a run of the node with [identity]. */
        fun issue(
            crypto: CryptoProvider,
            clock: Clock,
            identity: Ed25519KeyPair,
            profile: WireProfile,
            meshIdDigest: ByteArray,
        ): LinkCredentials {
            val static = crypto.x25519KeyPair()
            val xWing = if (profile == WireProfile.POST_QUANTUM) crypto.xWingKeyPair() else null
            val keys = StaticKeys(static.publicKey, xWing?.publicKey)
            val certificate = IdentityCertificate.issue(crypto, clock, identity, keys)
            return LinkCredentials(profile, meshIdDigest, static, certificate, xWing)
        }
    }
}
