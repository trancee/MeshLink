package ch.trancee.meshlink.bench.crypto

/** C1, C2 and C3 workloads plus the Android-smoke microbenchmarks. Every sample is a fresh handshake or operation. */
internal class CryptoBench(private val warmup: Int, private val iterations: Int) {
    private val lines = ArrayList<String>()

    fun run(): List<String> {
        microbenchmarks()
        xxHfs()
        ikHfs()
        return lines
    }

    private fun microbenchmarks() {
        val alice = Sodium.x25519Keypair()
        val bob = Sodium.x25519Keypair()
        val signer = Sodium.ed25519Keypair()
        val message = Sodium.random(64)
        val signature = Sodium.ed25519Sign(signer.secret, message)
        val key = Sodium.random(32)
        val nonce = Sodium.random(12)
        val small = Sodium.random(244)
        val large = Sodium.random(2048)
        val smallSealed = Sodium.aeadEncrypt(key, nonce, ByteArray(0), small)
        val largeSealed = Sodium.aeadEncrypt(key, nonce, ByteArray(0), large)
        val mlkem = Sodium.mlkemKeypair()
        val mlkemCiphertext = Sodium.mlkemEncapsulate(mlkem.public).ciphertext
        val xwing = Sodium.xwingKeypair()
        val xwingCiphertext = Sodium.xwingEncapsulate(xwing.public).ciphertext

        bench("x25519 base") { Sodium.x25519Base(alice.secret) }
        bench("x25519 scalarmult") { Sodium.x25519(alice.secret, bob.public) }
        bench("ed25519 sign 64B") { Sodium.ed25519Sign(signer.secret, message) }
        bench("ed25519 verify 64B") { check(Sodium.ed25519Verify(signer.public, message, signature)) }
        bench("C3 chacha20poly1305 encrypt 244B") { Sodium.aeadEncrypt(key, nonce, ByteArray(0), small) }
        bench("C3 chacha20poly1305 encrypt 2KB") { Sodium.aeadEncrypt(key, nonce, ByteArray(0), large) }
        bench("chacha20poly1305 decrypt 244B") { Sodium.aeadDecrypt(key, nonce, ByteArray(0), smallSealed) }
        bench("chacha20poly1305 decrypt 2KB") { Sodium.aeadDecrypt(key, nonce, ByteArray(0), largeSealed) }
        bench("sha256 2KB") { Sodium.sha256(large) }
        bench("hkdf-sha256 extract+expand 64B") { Sodium.hkdfExpand(Sodium.hkdfExtract(key, message), ByteArray(0), 64) }
        bench("hmac-sha256 2KB") { Sodium.hmacSha256(key, large) }
        bench("mlkem768 keypair") { Sodium.mlkemKeypair() }
        bench("mlkem768 enc") { Sodium.mlkemEncapsulate(mlkem.public) }
        bench("mlkem768 dec") { Sodium.mlkemDecapsulate(mlkem.secret, mlkemCiphertext) }
        bench("xwing keypair") { Sodium.xwingKeypair() }
        bench("xwing enc") { Sodium.xwingEncapsulate(xwing.public) }
        bench("xwing dec") { Sodium.xwingDecapsulate(xwing.secret, xwingCiphertext) }
    }

    private fun xxHfs() {
        val initiatorIdentity = Identity.generate()
        val responderIdentity = Identity.generate()
        val initiatorSamples = LongArray(iterations)
        val responderSamples = LongArray(iterations)
        var bytes = 0
        for (index in -warmup until iterations) {
            val initiator = SideTimer()
            val responder = SideTimer()
            val alice = HandshakeState(Pattern.XX_HFS, initiator = true, localStatic = initiatorIdentity.noiseStatic)
            val bob = HandshakeState(Pattern.XX_HFS, initiator = false, localStatic = responderIdentity.noiseStatic)

            val message1 = initiator.time { alice.writeMessage(ByteArray(0)) }
            responder.time { bob.readMessage(message1) }
            val message2 = responder.time {
                Certificate.issue(responderIdentity.signing, responderIdentity.noiseStatic.public, responderIdentity.xwing.public)
                bob.writeMessage(responderIdentity.certificate.bytes)
            }
            initiator.time {
                val peer = Certificate(alice.readMessage(message2))
                check(peer.verify() && peer.noiseStaticKey.contentEquals(requireNotNull(alice.remoteStaticKey)))
            }
            val message3 = initiator.time {
                Certificate.issue(initiatorIdentity.signing, initiatorIdentity.noiseStatic.public, initiatorIdentity.xwing.public)
                alice.writeMessage(initiatorIdentity.certificate.bytes)
            }
            responder.time {
                val peer = Certificate(bob.readMessage(message3))
                check(peer.verify() && peer.noiseStaticKey.contentEquals(requireNotNull(bob.remoteStaticKey)))
            }
            val (aliceSend, _) = initiator.time { alice.split() }
            val (bobReceive, _) = responder.time { bob.split() }
            val probe = byteArrayOf(1, 2, 3)
            check(bobReceive.decrypt(ByteArray(0), aliceSend.encrypt(ByteArray(0), probe)).contentEquals(probe)) { "split mismatch" }
            if (index >= 0) {
                initiatorSamples[index] = initiator.nanos
                responderSamples[index] = responder.nanos
            }
            bytes = message1.size + message2.size + message3.size
        }
        lines += "C1 XXhfs messages: ${bytes} B in total"
        lines += Stats(initiatorSamples).line("C1 XXhfs initiator side")
        lines += Stats(responderSamples).line("C1 XXhfs responder side")
    }

    private fun ikHfs() {
        val senderIdentity = Identity.generate()
        val recipientIdentity = Identity.generate()
        val recipientCertificate = recipientIdentity.certificate
        val payload = Sodium.random(1024)
        val info = "MeshLink e2e v1".encodeToByteArray()
        val senderSamples = LongArray(iterations)
        val recipientSamples = LongArray(iterations)
        val senderReplySamples = LongArray(iterations)
        val recipientReplySamples = LongArray(iterations)
        var firstMessageBytes = 0
        for (index in -warmup until iterations) {
            val sender = SideTimer()
            val recipient = SideTimer()
            val senderReply = SideTimer()
            val recipientReply = SideTimer()

            val (alice, message1) = sender.time {
                check(recipientCertificate.verify())
                val sealed = Hpke.seal(recipientCertificate.xwingKey, info, ByteArray(0), payload)
                val state = HandshakeState(
                    Pattern.IK_HFS, initiator = true,
                    localStatic = senderIdentity.noiseStatic, remoteStatic = recipientCertificate.noiseStaticKey,
                )
                state to state.writeMessage(concat(senderIdentity.certificate.bytes, sealed))
            }
            val bob = recipient.time {
                val state = HandshakeState(Pattern.IK_HFS, initiator = false, localStatic = recipientIdentity.noiseStatic)
                val body = state.readMessage(message1)
                val peer = Certificate(body.copyOfRange(0, Certificate.SIZE))
                check(peer.verify() && peer.noiseStaticKey.contentEquals(requireNotNull(state.remoteStaticKey)))
                val opened = Hpke.open(recipientIdentity.xwing.secret, info, ByteArray(0), body.copyOfRange(Certificate.SIZE, body.size))
                check(opened.contentEquals(payload)) { "HPKE payload mismatch" }
                state
            }
            val reply = recipientReply.time { bob.writeMessage(ByteArray(0)).also { bob.split() } }
            senderReply.time { alice.readMessage(reply); alice.split() }
            if (index >= 0) {
                senderSamples[index] = sender.nanos
                recipientSamples[index] = recipient.nanos
                senderReplySamples[index] = senderReply.nanos
                recipientReplySamples[index] = recipientReply.nanos
            }
            firstMessageBytes = message1.size
        }
        lines += "C2 IKhfs message 1: $firstMessageBytes B (certificate + HPKE-sealed 1 KB)"
        lines += Stats(senderSamples).line("C2 IKhfs+HPKE sender side")
        lines += Stats(recipientSamples).line("C2 IKhfs+HPKE recipient side")
        lines += Stats(recipientReplySamples).line("C2 info: IKhfs reply write (recipient)")
        lines += Stats(senderReplySamples).line("C2 info: IKhfs reply read (sender)")
    }

    private inline fun bench(name: String, crossinline operation: () -> Unit) {
        lines += measure(warmup, iterations) { operation() }.line(name)
    }
}
