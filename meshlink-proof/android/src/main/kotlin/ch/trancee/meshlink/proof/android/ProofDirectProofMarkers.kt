package ch.trancee.meshlink.proof.android

internal object ProofDirectProofMarkers {
    internal fun passivePeerDiscovered(peer: String): String {
        return "REFERENCE_AUTOMATION peer.discovered role=PASSIVE peer=$peer"
    }

    internal fun passiveProofComplete(
        peer: String,
        tokenHex: String,
        totalBytes: Int,
    ): String {
        return "REFERENCE_AUTOMATION proof.complete role=passive peer=$peer token=$tokenHex bytes=$totalBytes"
    }
}
