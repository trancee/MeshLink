package ch.trancee.meshlink.internal

import android.app.Application
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 37])
class KeystoreSlotTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val file = AtomicFile(File(app.noBackupFilesDir, "meshlink-identity"))
    private val seed = ByteArray(SEED_SIZE) { (it + 1).toByte() }

    @Before
    fun installKeystore() {
        FakeAndroidKeyStore.install()
    }

    @After
    fun uninstallKeystore() {
        FakeAndroidKeyStore.uninstall()
    }

    @Test
    fun aWrittenSeedReadsBack() {
        // Arrange
        val slot = KeystoreSlot(file)

        // Act
        val written = slot.write(seed)
        val read = slot.read()

        // Assert
        assertTrue(written)
        assertContentEquals(seed, assertIs<SlotRead.Present>(read).seed)
    }

    @Test
    fun theBlobIsTheVersionTheNonceAndTheSealedSeed() {
        // Arrange
        val slot = KeystoreSlot(file)

        // Act
        slot.write(seed)

        // Assert
        val blob = file.readFully()
        assertEquals(BLOB_SIZE, blob.size)
        assertEquals(1, blob[0].toInt())
        val plaintext = blob.asList().windowed(SEED_SIZE).any { it == seed.asList() }
        assertFalse(plaintext, "S1: the seed is never stored in plaintext")
    }

    @Test
    fun theKeyIsAnAes256GcmKeyWithoutUserAuthentication() {
        // Arrange
        val slot = KeystoreSlot(file)

        // Act
        slot.write(seed)

        // Assert
        val spec = FakeAndroidKeyStore.specs.last()
        assertEquals("ch.trancee.meshlink.identity", spec.keystoreAlias)
        assertEquals(KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT, spec.purposes)
        assertEquals(listOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes.toList())
        assertEquals(listOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings.toList())
        assertEquals(KEY_BITS, spec.keySize)
        assertFalse(spec.isUserAuthenticationRequired)
        assertTrue(spec.isRandomizedEncryptionRequired)
    }

    @Test
    @Config(sdk = [37])
    fun theKeyIsInStrongBoxWhenTheDeviceHasIt() {
        // Arrange
        val slot = KeystoreSlot(file)

        // Act
        slot.write(seed)

        // Assert
        assertEquals(listOf(true), FakeAndroidKeyStore.specs.map { it.isStrongBoxBacked })
    }

    @Test
    @Config(sdk = [37])
    fun withoutStrongBoxTheKeyIsGeneratedOutsideIt() {
        // Arrange
        FakeAndroidKeyStore.hasStrongBox = false
        val slot = KeystoreSlot(file)

        // Act
        val written = slot.write(seed)

        // Assert
        assertTrue(written)
        assertEquals(listOf(true, false), FakeAndroidKeyStore.specs.map { it.isStrongBoxBacked })
        assertContentEquals(seed, assertIs<SlotRead.Present>(slot.read()).seed)
    }

    @Test
    fun noBlobIsAbsentEvenWithAKey() {
        // Arrange: a first start that generated the key and died before writing the blob.
        val slot = KeystoreSlot(file)
        slot.write(seed)
        file.delete()

        // Act
        val read = slot.read()

        // Assert
        assertEquals(SlotRead.Absent, read)
    }

    @Test
    fun aTruncatedBlobIsUnavailableAndLeftAsItIs() {
        // Arrange: T10 truncated blob, cut inside its nonce.
        val slot = KeystoreSlot(file)
        slot.write(seed)
        val truncated = file.readFully().copyOf(NONCE_END - 1)
        replaceBlob(truncated)

        // Act
        val read = slot.read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
        assertContentEquals(truncated, file.readFully())
    }

    @Test
    fun aCorruptBlobIsUnavailable() {
        // Arrange: T10 corrupt blob; GCM rejects the flipped ciphertext bit.
        val slot = KeystoreSlot(file)
        slot.write(seed)
        replaceBlob(file.readFully().also { it[BLOB_SIZE - 1] = (it[BLOB_SIZE - 1].toInt() xor 1).toByte() })

        // Act
        val read = slot.read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
    }

    @Test
    fun aBlobOfAnotherVersionIsUnavailable() {
        // Arrange
        val slot = KeystoreSlot(file)
        slot.write(seed)
        replaceBlob(file.readFully().also { it[0] = 2 })

        // Act
        val read = slot.read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
    }

    @Test
    fun aBlobWithoutItsKeyIsUnavailableAndLeftAsItIs() {
        // Arrange: T10 Keystore key missing, as after Android cleared the Keystore.
        val slot = KeystoreSlot(file)
        slot.write(seed)
        val blob = file.readFully()
        FakeAndroidKeyStore.keys.clear()

        // Act
        val read = slot.read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
        assertContentEquals(blob, file.readFully())
    }

    @Test
    fun anUnreachableKeystoreFailsEveryOperation() {
        // Arrange
        val slot = KeystoreSlot(file)
        slot.write(seed)
        FakeAndroidKeyStore.unavailable = true

        // Act
        val results = listOf(slot.read(), slot.erase())

        // Assert
        assertEquals(listOf(SlotRead.Unavailable, false), results)
    }

    @Test
    fun aKeyThatCanNotBeGeneratedWritesNothing() {
        // Arrange
        FakeAndroidKeyStore.generationFails = true
        val slot = KeystoreSlot(file)

        // Act
        val written = slot.write(seed)

        // Assert
        assertFalse(written)
        assertFalse(file.baseFile.exists())
    }

    @Test
    fun aFailedFileWriteLeavesNoBlob() {
        // Arrange: the disk fills up mid-write.
        val failing =
            object : AtomicFile(file.baseFile) {
                override fun startWrite(): FileOutputStream {
                    val started = super.startWrite()
                    return object : FileOutputStream(started.fd) {
                        override fun write(b: ByteArray): Unit = throw IOException("disk full")
                    }
                }
            }
        val slot = KeystoreSlot(failing)

        // Act
        val written = slot.write(seed)

        // Assert
        assertFalse(written)
        assertEquals(SlotRead.Absent, slot.read())
    }

    @Test
    fun aFileThatCanNotBeReadIsUnavailable() {
        // Arrange
        val failing =
            object : AtomicFile(file.baseFile) {
                override fun readFully(): ByteArray = throw IOException("I/O error")
            }

        // Act
        val read = KeystoreSlot(failing).read()

        // Assert
        assertEquals(SlotRead.Unavailable, read)
    }

    @Test
    fun eraseDeletesTheBlobAndTheKey() {
        // Arrange
        val slot = KeystoreSlot(file)
        slot.write(seed)

        // Act
        val erased = slot.erase()

        // Assert
        assertTrue(erased)
        assertFalse(file.baseFile.exists())
        assertTrue(FakeAndroidKeyStore.keys.isEmpty())
    }

    @Test
    fun eraseOfAnEmptySlotSucceeds() {
        // Arrange
        val slot = KeystoreSlot(file)

        // Act
        val erased = slot.erase()

        // Assert
        assertTrue(erased)
    }

    @Test
    fun eraseReportsAKeyThatCanNotBeDeleted() {
        // Arrange
        val slot = KeystoreSlot(file)
        slot.write(seed)
        FakeAndroidKeyStore.deleteFails = true

        // Act
        val erased = slot.erase()

        // Assert
        assertFalse(erased)
    }

    private fun replaceBlob(blob: ByteArray) {
        file.delete()
        file.baseFile.writeBytes(blob)
    }

    private companion object {
        const val SEED_SIZE = 32
        const val KEY_BITS = 256

        // Version byte, 12-byte nonce, 32-byte seed, 16-byte tag.
        const val BLOB_SIZE = 61
        const val NONCE_END = 13
    }
}
