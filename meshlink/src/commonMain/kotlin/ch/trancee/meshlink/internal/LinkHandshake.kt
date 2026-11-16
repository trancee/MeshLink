package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * One link's secured handshake (spec "HANDSHAKE frame", "Handshake payload", D-15, D-16) with the
 * remote [handle], whose prologue binds the peripheral's capability record [versions]. The central
 * calls [start]; the peripheral responds to the first `HANDSHAKE`. Each call returns what the link
 * does next.
 */
internal class LinkHandshake(
    private val handshakes: Handshakes,
    private val handle: PeerHandle,
    private val versions: List<Int>,
) {
    private var noise: NoiseHandshake? = null
    private var version = 0
    private var expected = FIRST

    // Whether Handshakes counts this handshake as half-open.
    private var counted = false

    /** The central's message 1, or a drop if the global bound is full. */
    fun start(selected: Int): HandshakeStep {
        val limit = handshakes.admit(null)
        if (limit != null) return drop(limit)
        counted = true
        version = selected
        expected = SECOND
        val noise = handshakes.noise(NoiseRole.INITIATOR, versions, selected).also { noise = it }
        // Message 1 is only the ephemeral key, which writing can't reject.
        return HandshakeStep.Send(frame(FIRST, (noise.writeMessage(ByteArray(0)) as NoiseResult.Ok).value))
    }

    /** The next step after the peer's [frame], or after an undecodable frame if `null`. */
    fun receive(frame: LinkFrame?): HandshakeStep {
        val noise = noise
        return when {
            frame !is LinkFrame.Handshake -> fail(HandshakeFailure.PROTOCOL)
            frame.step == FIRST && noise != null -> drop(HandshakeLimit.PER_BEARER)
            frame.step != expected -> fail(HandshakeFailure.PROTOCOL)
            noise == null -> respond(frame)
            frame.version != version -> fail(HandshakeFailure.PROTOCOL)
            else -> advance(noise, frame)
        }
    }

    /** The bearer stayed open `handshake-timeout` without completing this handshake. */
    fun timedOut() {
        handshakes.report(LinkDiagnostic.HandshakeFailed(HandshakeFailure.TIMEOUT))
        end()
    }

    /** Stops counting this handshake as half-open; the link closed. Idempotent. */
    fun end() {
        if (counted) handshakes.finish()
        counted = false
    }

    private fun respond(frame: LinkFrame.Handshake): HandshakeStep {
        val spoken = frame.version in versions
        val limit = if (spoken) handshakes.admit(handle) else null
        return when {
            !spoken -> {
                fail(HandshakeFailure.PROTOCOL)
            }

            limit != null -> {
                drop(limit)
            }

            else -> {
                counted = true
                version = frame.version
                advance(handshakes.noise(NoiseRole.RESPONDER, versions, version).also { noise = it }, frame)
            }
        }
    }

    private fun advance(
        noise: NoiseHandshake,
        frame: LinkFrame.Handshake,
    ): HandshakeStep {
        val read = noise.readMessage(frame.noiseMessage)
        // The responder's message 2 runs `ee` and `es` on the initiator's ephemeral key, so it can fail.
        val replies = read is NoiseResult.Ok && frame.step != THIRD
        val reply = if (replies) noise.writeMessage(handshakes.payload()) else null
        return when {
            read !is NoiseResult.Ok || reply is NoiseResult.Failed -> {
                fail(HandshakeFailure.DECRYPT)
            }

            reply == null -> {
                complete(noise, read.value, null)
            }

            frame.step == FIRST -> {
                expected = THIRD
                HandshakeStep.Send(frame(SECOND, (reply as NoiseResult.Ok).value))
            }

            else -> {
                complete(noise, read.value, frame(THIRD, (reply as NoiseResult.Ok).value))
            }
        }
    }

    private fun complete(
        noise: NoiseHandshake,
        payload: ByteArray,
        reply: LinkFrame.Handshake?,
    ): HandshakeStep {
        end()
        // A complete handshake always splits.
        val transport = (noise.split() as NoiseResult.Ok).value
        val peer = handshakes.verify(payload, transport.remoteStatic)
        if (peer == null) handshakes.report(LinkDiagnostic.HandshakeFailed(HandshakeFailure.CERTIFICATE))
        return HandshakeStep.Complete(reply, transport, peer)
    }

    private fun frame(
        step: Int,
        message: ByteArray,
    ): LinkFrame.Handshake = LinkFrame.Handshake(version, step, message)

    private fun fail(reason: HandshakeFailure): HandshakeStep {
        handshakes.report(LinkDiagnostic.HandshakeFailed(reason))
        end()
        return HandshakeStep.Failed
    }

    private fun drop(limit: HandshakeLimit): HandshakeStep {
        handshakes.report(LinkDiagnostic.HandshakeLimitDropped(limit))
        end()
        return HandshakeStep.Dropped
    }

    private companion object {
        const val FIRST = 1
        const val SECOND = 2
        const val THIRD = 3
    }
}

/** What a link does after a [LinkHandshake] step. */
internal sealed interface HandshakeStep {
    /** Sends [frame] in a cleartext record. */
    class Send(
        val frame: LinkFrame.Handshake,
    ) : HandshakeStep

    /**
     * Sends [reply], if any, in clear, then encrypts every record with [transport]. The link opens
     * to [peer], or closes with `HANDSHAKE_FAILED` if its certificate failed (`null`).
     */
    class Complete(
        val reply: LinkFrame.Handshake?,
        val transport: NoiseTransport,
        val peer: NodeId?,
    ) : HandshakeStep

    /** Closes with `HANDSHAKE_FAILED` in clear. */
    data object Failed : HandshakeStep

    /** Closes the bearer without a reply (D-20). */
    data object Dropped : HandshakeStep
}
