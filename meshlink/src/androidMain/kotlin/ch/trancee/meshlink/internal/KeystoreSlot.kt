package ch.trancee.meshlink.internal

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A [SeedSlot] on Android (ADR 0003): the seed sealed with AES-256-GCM under a non-exportable
 * Android Keystore key without user authentication, in StrongBox when the device has it. The
 * sealed blob in [file] is a version byte, the 12-byte nonce, and the ciphertext with its 16-byte
 * tag; the additional data binds it to its purpose and version.
 */
internal class KeystoreSlot(
    private val file: AtomicFile,
) : SeedSlot {
    override fun read(): SlotRead =
        try {
            open(file.readFully())
        } catch (_: FileNotFoundException) {
            SlotRead.Absent
        } catch (_: IOException) {
            SlotRead.Unavailable
        }

    override fun write(seed: ByteArray): Boolean =
        guarded {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, generateKey())
            cipher.updateAAD(AAD)
            save(byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(seed))
            true
        } ?: false

    override fun erase(): Boolean =
        guarded {
            file.delete()
            // AndroidKeyStore treats deleting a missing alias as success on every supported API level.
            keyStore().deleteEntry(ALIAS)
            true
        } ?: false

    // A blob without its key, of another size or version, or failing authentication is unavailable.
    private fun open(blob: ByteArray): SlotRead =
        guarded {
            // Without its key, Cipher.init fails with InvalidKeyException.
            val key = keyStore().getKey(ALIAS, null)
            if (blob.size != BLOB_SIZE || blob[0] != VERSION) {
                SlotRead.Unavailable
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, NONCE_SIZE))
                cipher.updateAAD(AAD)
                SlotRead.Present(cipher.doFinal(blob, HEADER_SIZE, blob.size - HEADER_SIZE))
            }
        } ?: SlotRead.Unavailable

    private fun save(blob: ByteArray) {
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    // StrongBox from Android 9 (API 28) where the device has it; otherwise the TEE or software.
    private fun generateKey(): SecretKey =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKey(strongBox = true)
            } catch (_: ProviderException) {
                generateKey(strongBox = false)
            }
        } else {
            generateKey(strongBox = false)
        }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setUserAuthenticationRequired(false)
        if (strongBox) spec.setIsStrongBoxBacked(true)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec.build())
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    // The Keystore reports failure as checked exceptions, or as ProviderException from its service.
    private inline fun <T : Any> guarded(block: () -> T): T? =
        try {
            block()
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: ProviderException) {
            null
        }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "ch.trancee.meshlink.identity"
        private const val FILE_NAME = "ch.trancee.meshlink.identity"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val VERSION: Byte = 1
        private const val NONCE_SIZE = 12
        private const val HEADER_SIZE = 1 + NONCE_SIZE
        private const val BLOB_SIZE = HEADER_SIZE + 32 + TAG_BITS / Byte.SIZE_BITS
        private val AAD = "ch.trancee.meshlink.identity.v1".encodeToByteArray()

        /** The slot of [context]'s app, in its no-backup storage so no backup ever copies the blob. */
        fun of(context: Context): KeystoreSlot = KeystoreSlot(AtomicFile(File(context.noBackupFilesDir, FILE_NAME)))
    }
}
