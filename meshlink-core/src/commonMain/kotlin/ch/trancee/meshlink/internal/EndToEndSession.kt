package ch.trancee.meshlink.internal

/**
 * An end-to-end session after `IK` or `IKhfs` message 2 (spec "Sessions"): frames are kind `0x03`
 * containers whose explicit nonce is the sender's counter, with empty associated data. The receiver
 * accepts each nonce once, within `e2e-replay-window` nonces of the highest it accepted; a refused
 * frame changes no state.
 *
 * Not thread-safe: a node calls it from its own dispatcher.
 */
public class EndToEndSession(
    public val handshakeHash: ByteArray,
    public val remoteStatic: ByteArray,
    private val sending: CipherState,
    private val receiving: CipherState,
) {
    private val window = ReplayWindow()

    /** [plaintext] sealed at the send counter, which then advances; `null` once it reaches `2^64 − 1`. */
    public fun seal(plaintext: ByteArray): Container.Transport? {
        val nonce = sending.next
        return (sending.encrypt(plaintext) as? NoiseResult.Ok)?.let { Container.Transport(nonce, it.value) }
    }

    /** The plaintext of [frame], or why it is refused: its nonce is checked against the window first. */
    public fun open(frame: Container.Transport): Opened {
        val refusal = window.refusal(frame.nonce)
        val plaintext = if (refusal == null) receiving.openAt(frame.nonce, ByteArray(0), frame.ciphertext) else null
        return when {
            refusal != null -> Opened.Refused(refusal)
            plaintext == null -> Opened.Refused(SessionRefusal.OPEN_FAILED)
            else -> Opened.Frame(plaintext).also { window.accept(frame.nonce) }
        }
    }

    /** The outcome of [open]: a [Frame] or a [Refused] one. */
    public sealed interface Opened {
        /** The frame opened to [plaintext]; its nonce is now accepted. */
        public class Frame(
            public val plaintext: ByteArray,
        ) : Opened

        /** The frame was refused for [refusal]; the session is unchanged. */
        public class Refused(
            public val refusal: SessionRefusal,
        ) : Opened
    }
}

/**
 * The end-to-end session of a complete `IK` or `IKhfs` handshake (`Split()`), whose frames carry
 * explicit nonces; the handshake closes. Fails as [NoiseHandshake.split] does.
 */
public fun NoiseHandshake.session(): NoiseResult<EndToEndSession> =
    when (val split = split()) {
        is NoiseResult.Ok -> {
            val transport = split.value
            val session = transport.run { EndToEndSession(handshakeHash, remoteStatic, sending, receiving) }
            NoiseResult.Ok(session)
        }

        is NoiseResult.Failed -> {
            split
        }
    }

/** Why an [EndToEndSession] refused a frame, in the order it checks. */
public enum class SessionRefusal {
    /** The nonce plus `e2e-replay-window` is at most the highest accepted nonce (`nonce-below-window`). */
    NONCE_BELOW_WINDOW,

    /** The nonce was accepted before (`replayed-nonce`). */
    REPLAYED_NONCE,

    /** The ciphertext doesn't open at its nonce, or the nonce is the reserved `2^64 − 1` (`open-failed`). */
    OPEN_FAILED,
}

/**
 * The accepted nonces from `highest − (SIZE − 1)` to `highest`, one bit each: the bit of nonce n is
 * `n mod SIZE`. Advancing `highest` clears the bits of the nonces it passes, at most [SIZE] of them.
 */
internal class ReplayWindow {
    private val bits = LongArray(SIZE / Long.SIZE_BITS)
    private var highest: ULong? = null

    /** Why [nonce] can't be accepted, or `null` if it can. */
    fun refusal(nonce: ULong): SessionRefusal? {
        val top = highest
        return when {
            top == null || nonce > top -> null
            top - nonce >= SIZE.toULong() -> SessionRefusal.NONCE_BELOW_WINDOW
            isSet(nonce) -> SessionRefusal.REPLAYED_NONCE
            else -> null
        }
    }

    /** Records [nonce], which [refusal] allowed, as accepted. */
    fun accept(nonce: ULong) {
        val top = highest ?: nonce
        if (nonce >= top) {
            for (step in 1uL until minOf(nonce - top, SIZE.toULong())) clear(nonce - step)
            highest = nonce
        }
        set(nonce)
    }

    private fun isSet(nonce: ULong): Boolean = bits[word(nonce)] and mask(nonce) != 0L

    private fun set(nonce: ULong) {
        bits[word(nonce)] = bits[word(nonce)] or mask(nonce)
    }

    private fun clear(nonce: ULong) {
        bits[word(nonce)] = bits[word(nonce)] and mask(nonce).inv()
    }

    private fun word(nonce: ULong): Int = (nonce % SIZE.toULong()).toInt() / Long.SIZE_BITS

    private fun mask(nonce: ULong): Long = 1L shl (nonce % SIZE.toULong()).toInt() % Long.SIZE_BITS

    private companion object {
        /** `e2e-replay-window` (spec "Sessions"; D-33). */
        const val SIZE = 1_024
    }
}
