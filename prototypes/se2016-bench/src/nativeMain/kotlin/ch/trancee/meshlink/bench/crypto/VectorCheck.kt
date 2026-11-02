package ch.trancee.meshlink.bench.crypto

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { index -> substring(2 * index, 2 * index + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** Runs the X-Wing (draft-11 Appendix C) and hpke-pq-05 A.5 vectors; one line per check, `ok` false on any mismatch. */
internal object VectorCheck {
    class Result(val lines: List<String>, val ok: Boolean)

    fun run(): Result {
        val lines = ArrayList<String>()
        var ok = true
        fun verdict(match: Boolean): String {
            ok = ok && match
            return if (match) "MATCH" else "MISMATCH"
        }

        Vectors.xwing.forEachIndexed { index, vector ->
            val keys = Sodium.xwingSeedKeypair(vector.seed.hexToBytes())
            val encapsulation = Sodium.xwingEncapsulate(keys.public, vector.encapsSeed.hexToBytes())
            val decapsulated = Sodium.xwingDecapsulate(keys.secret, vector.ciphertext.hexToBytes())
            lines += "VECTOR xwing-draft11 C#$index pk=${verdict(keys.public.toHex() == vector.publicKey)}" +
                " ct=${verdict(encapsulation.ciphertext.toHex() == vector.ciphertext)}" +
                " ss_enc=${verdict(encapsulation.sharedSecret.toHex() == vector.sharedSecret)}" +
                " ss_dec=${verdict(decapsulated.toHex() == vector.sharedSecret)}"
        }

        val recipient = Hpke.deriveKeyPair(Vectors.hpkeIkmR.hexToBytes())
        val sender = Hpke.setupBaseSender(recipient.public, infoBytes(), Vectors.hpkeIkmE.hexToBytes())
        val encapsulation = Sodium.xwingEncapsulate(recipient.public, Vectors.hpkeIkmE.hexToBytes())
        val recipientContext = Hpke.setupBaseRecipient(Vectors.hpkeEnc.hexToBytes(), recipient.secret, infoBytes())
        lines += "VECTOR hpke-pq-05 A.5 skRm=${verdict(recipient.secret.toHex() == Vectors.hpkeSkRm)}" +
            " pkRm=${verdict(recipient.public.toHex() == Vectors.hpkePkRm)}" +
            " enc=${verdict(sender.encapsulatedKey.toHex() == Vectors.hpkeEnc)}" +
            " ss_enc=${verdict(encapsulation.sharedSecret.toHex() == Vectors.hpkeSharedSecret)}" +
            " ss_dec=${verdict(Sodium.xwingDecapsulate(recipient.secret, Vectors.hpkeEnc.hexToBytes()).toHex() == Vectors.hpkeSharedSecret)}"
        val context = sender.context
        lines += "VECTOR hpke-pq-05 A.5 key=${verdict(context.key.toHex() == Vectors.hpkeKey)}" +
            " base_nonce=${verdict(context.baseNonce.toHex() == Vectors.hpkeBaseNonce)}" +
            " exporter_secret=${verdict(context.exporterSecret.toHex() == Vectors.hpkeExporterSecret)}" +
            " recipient_key=${verdict(recipientContext.key.toHex() == Vectors.hpkeKey)}"

        var sealMatches = 0
        var openMatches = 0
        var nonceMatches = 0
        Vectors.hpkeEncryptions.forEachIndexed { sequence, vector ->
            val plaintext = vector.plaintext.hexToBytes()
            val aad = vector.aad.hexToBytes()
            if (context.nonceFor(sequence.toLong()).toHex() == vector.nonce) nonceMatches++
            if (context.seal(aad, plaintext).toHex() == vector.ciphertext) sealMatches++
            val opened = runCatching { recipientContext.open(aad, vector.ciphertext.hexToBytes()) }.getOrNull()
            if (opened != null && opened.contentEquals(plaintext)) openMatches++
        }
        val total = Vectors.hpkeEncryptions.size
        lines += "VECTOR hpke-pq-05 A.5 encryptions nonce=$nonceMatches/$total seal=$sealMatches/$total open=$openMatches/$total " +
            verdict(nonceMatches == total && sealMatches == total && openMatches == total)

        var exportMatches = 0
        for (vector in Vectors.hpkeExports) {
            if (context.export(vector.context.hexToBytes(), vector.length).toHex() == vector.value) exportMatches++
        }
        lines += "VECTOR hpke-pq-05 A.5 exports=$exportMatches/${Vectors.hpkeExports.size} " +
            verdict(exportMatches == Vectors.hpkeExports.size)
        lines += "VECTORS ${if (ok) "ALL MATCH" else "FAILED"}"
        return Result(lines, ok)
    }

    private fun infoBytes(): ByteArray = Vectors.hpkeInfo.hexToBytes()
}
