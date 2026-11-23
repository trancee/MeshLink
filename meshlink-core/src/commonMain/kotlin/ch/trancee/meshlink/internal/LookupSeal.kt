package ch.trancee.meshlink.internal

/**
 * The seal of a `LOOKUP_REPLY` (spec "Certificate lookup", ADR 0018): ChaCha20-Poly1305 of one
 * certificate, nonce 12 zero bytes and empty associated data, under the [key] of the two ephemeral
 * keys' X25519 shared secret. A low-order peer key, whose shared secret would be all zero, gives no
 * key: either side refuses it before the AEAD.
 */
public class LookupSeal(
    private val crypto: CryptoProvider,
) {
    private val derivations = Derivations(crypto)

    /**
     * The responder's side: [certificate] sealed with [responder] to [requesterEphemeral], or `null`
     * for a low-order key.
     */
    public fun seal(
        responder: KeyPair,
        requesterEphemeral: ByteArray,
        salt: ByteArray,
        lookupTag: ByteArray,
        certificate: ByteArray,
    ): ByteArray? =
        shared(responder.secretKey, requesterEphemeral)?.let {
            val key = key(it, salt, lookupTag, requesterEphemeral, responder.publicKey)
            crypto.chaCha20Poly1305Seal(key, NONCE, certificate, EMPTY)
        }

    /**
     * The requester's side: the certificate in [sealed] from [responderEphemeral], or `null` for a
     * low-order key or a failed tag.
     */
    public fun open(
        requester: KeyPair,
        responderEphemeral: ByteArray,
        salt: ByteArray,
        lookupTag: ByteArray,
        sealed: ByteArray,
    ): ByteArray? =
        shared(requester.secretKey, responderEphemeral)?.let {
            val key = key(it, salt, lookupTag, requester.publicKey, responderEphemeral)
            (crypto.chaCha20Poly1305Open(key, NONCE, sealed, EMPTY) as? CryptoResult.Ok)?.value
        }

    /**
     * The key that seals a `LOOKUP_REPLY`:
     * `KDF(shared, salt ‖ lookup_tag, "lookup-reply", requester_ephemeral ‖ responder_ephemeral, 32)`.
     */
    public fun key(
        shared: ByteArray,
        salt: ByteArray,
        lookupTag: ByteArray,
        requesterEphemeral: ByteArray,
        responderEphemeral: ByteArray,
    ): ByteArray {
        val ephemerals = requesterEphemeral + responderEphemeral
        return derivations.kdf(shared, salt + lookupTag, Domain.LOOKUP_REPLY, ephemerals)
    }

    private fun shared(
        secretKey: ByteArray,
        peer: ByteArray,
    ): ByteArray? = (crypto.x25519(secretKey, peer) as? CryptoResult.Ok)?.value
}

private val NONCE = ByteArray(12)
private val EMPTY = ByteArray(0)
