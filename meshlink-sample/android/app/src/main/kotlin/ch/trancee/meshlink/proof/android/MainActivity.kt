package ch.trancee.meshlink.proof.android

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)

        val report = AndroidCryptoDiagnostics.run(context = this)
        Log.i(TAG, "\n$report")

        val label = TextView(this).apply {
            text = report
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
        }
        setContentView(
            ScrollView(this).apply {
                addView(label)
            },
        )
    }

    private companion object {
        private const val TAG = "MeshLinkProof"
    }
}

private object AndroidCryptoDiagnostics {
    fun run(context: Activity): String {
        return buildString {
            appendLine("MeshLink Android Device Diagnostics")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("model=${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine(probeXdh("XDH"))
            appendLine(probeXdh("X25519"))
            appendLine(probeEd25519())
            appendLine(probeChaCha20Poly1305())
            appendLine(probeMeshLinkFactory(context))
        }
    }

    private fun probeXdh(algorithm: String): String {
        return runCatching {
            val generator = KeyPairGenerator.getInstance(algorithm)
            val alice = generator.generateKeyPair()
            val bob = generator.generateKeyPair()

            val aliceAgreement = KeyAgreement.getInstance(algorithm)
            aliceAgreement.init(alice.private)
            aliceAgreement.doPhase(bob.public, true)
            val aliceSecret = aliceAgreement.generateSecret()

            val bobAgreement = KeyAgreement.getInstance(algorithm)
            bobAgreement.init(bob.private)
            bobAgreement.doPhase(alice.public, true)
            val bobSecret = bobAgreement.generateSecret()

            check(aliceSecret.contentEquals(bobSecret)) { "shared secret mismatch" }
            "$algorithm keygen + agreement: PASS (${aliceSecret.size} bytes)"
        }.getOrElse { error ->
            "$algorithm keygen + agreement: FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun probeEd25519(): String {
        return runCatching {
            val generator = KeyPairGenerator.getInstance("Ed25519")
            val keyPair = generator.generateKeyPair()
            val message = "meshlink-trust".encodeToByteArray()

            val signer = Signature.getInstance("Ed25519")
            signer.initSign(keyPair.private)
            signer.update(message)
            val signature = signer.sign()

            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(keyPair.public)
            verifier.update(message)
            check(verifier.verify(signature)) { "signature verification failed" }
            "Ed25519 keygen + sign + verify: PASS (${signature.size} bytes)"
        }.getOrElse { error ->
            "Ed25519 keygen + sign + verify: FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun probeChaCha20Poly1305(): String {
        return runCatching {
            val key = ByteArray(32) { index -> (index + 1).toByte() }
            val nonce = ByteArray(12) { index -> (index + 3).toByte() }
            val aad = "meshlink-aad".encodeToByteArray()
            val plaintext = "meshlink-secret".encodeToByteArray()

            val encryptCipher = Cipher.getInstance("ChaCha20-Poly1305")
            encryptCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            encryptCipher.updateAAD(aad)
            val ciphertext = encryptCipher.doFinal(plaintext)

            val decryptCipher = Cipher.getInstance("ChaCha20-Poly1305")
            decryptCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            decryptCipher.updateAAD(aad)
            val decrypted = decryptCipher.doFinal(ciphertext)

            check(decrypted.contentEquals(plaintext)) { "decrypted plaintext mismatch" }
            "ChaCha20-Poly1305 encrypt + decrypt: PASS (${ciphertext.size} bytes ciphertext)"
        }.getOrElse { error ->
            "ChaCha20-Poly1305 encrypt + decrypt: FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }

    private fun probeMeshLinkFactory(context: Activity): String {
        return runCatching {
            val meshLink = MeshLink.createAndroid(
                context = context,
                config = meshLinkConfig {
                    appId = "demo.meshlink"
                    regulatoryRegion = RegulatoryRegion.DEFAULT
                    powerMode = PowerMode.Automatic
                },
            )
            "MeshLink.createAndroid: PASS (state=${meshLink.state.value})"
        }.getOrElse { error ->
            "MeshLink.createAndroid: FAIL ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        }
    }
}
