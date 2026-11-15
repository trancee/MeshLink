package ch.trancee.meshlink.internal

import android.Manifest
import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.PermissionKind
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.android
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 37],
    shadows = [
        ShadowMeshAdapter::class,
        ShadowMeshAdvertiser::class,
        ShadowMeshManager::class,
        ShadowMeshGattServer::class,
        ShadowMeshDevice::class,
        ShadowMeshGatt::class,
    ],
)
class AndroidRadioRequirementsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter = manager.adapter
    private val permissions = neededPermissions(Build.VERSION.SDK_INT)

    @Before
    fun arrangeACapableDevice() {
        resetRadioShadows()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        shadowOf(adapter).setEnabled(true)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun eachApiLevelNeedsItsOwnPermissions() {
        // Arrange
        val levels = listOf(26, 28, 29, 30, 31, 37)

        // Act
        val needed = levels.associateWith { neededPermissions(it) }

        // Assert
        val location = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val fine = listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val nearby =
            mapOf(
                PermissionKind.BluetoothScan to listOf(Manifest.permission.BLUETOOTH_SCAN),
                PermissionKind.BluetoothAdvertise to listOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                PermissionKind.BluetoothConnect to listOf(Manifest.permission.BLUETOOTH_CONNECT),
            )
        assertEquals(mapOf(PermissionKind.Location to location), needed.getValue(26))
        assertEquals(mapOf(PermissionKind.Location to location), needed.getValue(28))
        assertEquals(mapOf(PermissionKind.Location to fine), needed.getValue(29))
        assertEquals(mapOf(PermissionKind.Location to fine), needed.getValue(30))
        assertEquals(nearby, needed.getValue(31))
        assertEquals(nearby, needed.getValue(37))
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aCapableDeviceWithEveryPermissionMissesNothing() {
        // Arrange
        grantAll()

        // Act
        val missing = AndroidRequirements(app, adapter).requirements.value

        // Assert
        assertEquals(emptySet(), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun eachPermissionKindThatIsNotGrantedIsDenied() {
        // Arrange
        val requirements = AndroidRequirements(app, adapter)

        // Act
        val missing = requirements.requirements.value

        // Assert
        assertEquals(permissions.keys.map(Requirement::PermissionDenied).toSet(), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun anyOnePermissionOfAKindGrantsIt() {
        // Arrange
        shadowOf(app).grantPermissions(*permissions.values.map { it.last() }.toTypedArray())

        // Act
        val missing = AndroidRequirements(app, adapter).requirements.value

        // Assert
        assertEquals(emptySet(), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aDeviceWithoutBluetoothLeIsUnsupported() {
        // Arrange
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, false)

        // Act
        val withoutFeature = AndroidRequirements(app, adapter).requirements.value
        val withoutAdapter = AndroidRequirements(app, null).requirements.value

        // Assert
        assertEquals(setOf(Requirement.Unsupported), withoutFeature)
        assertEquals(setOf(Requirement.Unsupported), withoutAdapter)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun bluetoothOffIsMissingAndHidesTheAdvertisingCheck() {
        // Arrange
        grantAll()
        shadowOf(adapter).setEnabled(false)
        ShadowMeshAdapter.withoutAdvertiser = true

        // Act
        val missing = AndroidRequirements(app, adapter).requirements.value

        // Assert
        assertEquals(setOf(Requirement.BluetoothOff), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun anAdapterWithoutAnAdvertiserIsUnsupported() {
        // Arrange
        grantAll()
        ShadowMeshAdapter.withoutAdvertiser = true

        // Act
        val missing = AndroidRequirements(app, adapter).requirements.value

        // Assert
        assertEquals(setOf(Requirement.Unsupported), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun anAdapterWithOneAdvertisingInstanceMissesNothing() {
        // Arrange
        grantAll()
        shadowOf(adapter).setIsMultipleAdvertisementSupported(false)

        // Act
        val missing = AndroidRequirements(app, adapter).requirements.value

        // Assert
        assertEquals(emptySet(), missing)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun bluetoothTurningOffClosesTheServerAndRefreshes() =
        runTest {
            // Arrange
            grantAll()
            val requirements = AndroidRequirements(app, adapter)
            val radio = AndroidRadio(app, manager, adapter, backgroundScope)
            RequirementsWatcher(requirements, radio).register(app)
            radio.serveCapability(ByteArray(1))
            shadowOf(adapter).setEnabled(false)

            // Act
            broadcast(BluetoothAdapter.STATE_OFF)

            // Assert
            assertEquals(setOf(Requirement.BluetoothOff), requirements.requirements.value)
            assertTrue(serverShadow().isClosed)
        }

    @Test(timeout = TIMEOUT_MILLIS)
    fun otherStateChangesOnlyRefresh() =
        runTest {
            // Arrange
            grantAll()
            shadowOf(adapter).setEnabled(false)
            val requirements = AndroidRequirements(app, adapter)
            val radio = AndroidRadio(app, manager, adapter, backgroundScope)
            RequirementsWatcher(requirements, radio).register(app)
            radio.serveCapability(ByteArray(1))
            shadowOf(adapter).setEnabled(true)

            // Act
            broadcast(BluetoothAdapter.STATE_TURNING_ON)
            broadcast(null)

            // Assert
            assertEquals(emptySet(), requirements.requirements.value)
            assertFalse(serverShadow().isClosed)
        }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aWatcherWithoutARadioOnlyRefreshes() {
        // Arrange
        grantAll()
        val requirements = AndroidRequirements(app, adapter)
        RequirementsWatcher(requirements, null).register(app)
        shadowOf(adapter).setEnabled(false)

        // Act
        broadcast(BluetoothAdapter.STATE_OFF)

        // Assert
        assertEquals(setOf(Requirement.BluetoothOff), requirements.requirements.value)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aPermissionGrantedWhileAwayCountsWhenAnActivityResumes() {
        // Arrange
        val requirements = AndroidRequirements(app, adapter)
        RequirementsWatcher(requirements, null).register(app)
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        activity.pause().stop()
        grantAll()
        val whileAway = requirements.requirements.value

        // Act
        activity.restart().start().resume()

        // Assert
        assertEquals(permissions.keys.map(Requirement::PermissionDenied).toSet(), whileAway)
        assertEquals(emptySet(), requirements.requirements.value)
        activity
            .pause()
            .stop()
            .saveInstanceState(Bundle())
            .destroy()
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aContextWithoutAnApplicationStillWatchesBluetooth() {
        // Arrange
        val requirements = AndroidRequirements(app, adapter)
        val bare = SelfContext(app)
        RequirementsWatcher(requirements, null).register(bare)
        grantAll()

        // Act
        Robolectric.buildActivity(Activity::class.java).setup()
        val afterResume = requirements.requirements.value
        broadcast(BluetoothAdapter.STATE_ON, bare)

        // Assert
        assertEquals(permissions.keys.map(Requirement::PermissionDenied).toSet(), afterResume)
        assertEquals(emptySet(), requirements.requirements.value)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun thePlatformDrivesTheBluetoothRadioOfTheApp() {
        // Arrange
        grantAll()

        // Act
        val platform = AndroidPlatformPort(app)

        // Assert
        assertIs<AndroidRadio>(platform.radio)
        assertSame(SystemClock, platform.clock)
        assertEquals(emptySet(), platform.requirements.value)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun thePlatformKeepsItsIdentityInTheNoBackupDirectory() {
        // Arrange
        FakeAndroidKeyStore.install()
        try {
            val platform = AndroidPlatformPort(app)

            // Act
            val identity = platform.identityStore.loadOrCreate(FixedKeyPairs)

            // Assert
            assertNotNull(identity)
            assertTrue(File(app.noBackupFilesDir, "ch.trancee.meshlink.identity").isFile)
        } finally {
            FakeAndroidKeyStore.uninstall()
        }
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun thePlatformClosesItsServerWhenBluetoothTurnsOff() {
        // Arrange
        val platform = AndroidPlatformPort(app)
        platform.radio.serveCapability(ByteArray(1))

        // Act
        broadcast(BluetoothAdapter.STATE_OFF)

        // Assert
        assertTrue(serverShadow().isClosed)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aDeviceWithoutBluetoothGetsARadioThatRefusesEverything() {
        // Arrange
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)

        // Act
        val platform = AndroidPlatformPort(app)

        // Assert
        assertSame(UnsupportedRadio, platform.radio)
        assertEquals(setOf(Requirement.Unsupported), platform.requirements.value)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun aDeviceWithoutABluetoothServiceGetsARadioThatRefusesEverything() {
        // Arrange
        val withoutService = SelfContext(app, bluetooth = false)

        // Act
        val platform = AndroidPlatformPort(withoutService)

        // Assert
        assertSame(UnsupportedRadio, platform.radio)
        assertEquals(setOf(Requirement.Unsupported), platform.requirements.value)
    }

    @Test(timeout = TIMEOUT_MILLIS)
    fun theAppHasOnePlatform() {
        // Arrange
        val first = MeshPlatform.android(app)

        // Act
        val second = MeshPlatform.android(SelfContext(app))

        // Assert
        assertSame(first, second)
    }

    private fun grantAll() = shadowOf(app).grantPermissions(*permissions.values.flatten().toTypedArray())

    private fun serverShadow(): ShadowMeshGattServer = Shadow.extract(ShadowMeshManager.servers.single())

    private fun broadcast(
        state: Int?,
        context: Context = app,
    ) {
        val intent = Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
        state?.let { intent.putExtra(BluetoothAdapter.EXTRA_STATE, it) }
        context.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        // A Timeout rule would run each test off the main looper thread, which broadcasts and
        // activities need; Robolectric enforces @Test's timeout on the test's own thread.
        const val TIMEOUT_MILLIS = 60_000L
    }

    /**
     * A context that is its own application context, as a context outside any application is; without
     * [bluetooth] it has no Bluetooth service, as a build without the Bluetooth stack has none.
     */
    private class SelfContext(
        base: Context,
        private val bluetooth: Boolean = true,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSystemService(name: String): Any? =
            if (!bluetooth && name == BLUETOOTH_SERVICE) null else super.getSystemService(name)
    }
}

// Robolectric loads each SDK in its own class loader, and a JNI library loads into one only.
private object FixedKeyPairs : CryptoProvider {
    override fun ed25519KeyPair(): Ed25519KeyPair = Ed25519KeyPair(ByteArray(SEED_SIZE) { 1 }, ByteArray(SEED_SIZE))

    override fun blake2b(
        outLength: Int,
        input: ByteArray,
        key: ByteArray?,
    ): ByteArray = unused()

    override fun sha256(input: ByteArray): ByteArray = unused()

    override fun hkdfSha256Extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray = unused()

    override fun hkdfSha256Expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = unused()

    override fun ed25519KeyPair(seed: ByteArray): Ed25519KeyPair = unused()

    override fun ed25519Sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray = unused()

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = unused()

    override fun x25519KeyPair(): KeyPair = unused()

    override fun x25519KeyPair(seed: ByteArray): KeyPair = unused()

    override fun x25519(
        secretKey: ByteArray,
        publicKey: ByteArray,
    ): CryptoResult<ByteArray> = unused()

    override fun chaCha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray = unused()

    override fun chaCha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): CryptoResult<ByteArray> = unused()

    override fun mlKem768KeyPair(): KeyPair = unused()

    override fun mlKem768KeyPair(seed: ByteArray): KeyPair = unused()

    override fun mlKem768Encapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> = unused()

    override fun mlKem768Encapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation> = unused()

    override fun mlKem768Decapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = unused()

    override fun xWingKeyPair(): KeyPair = unused()

    override fun xWingKeyPair(seed: ByteArray): KeyPair = unused()

    override fun xWingEncapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> = unused()

    override fun xWingEncapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation> = unused()

    override fun xWingDecapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): CryptoResult<ByteArray> = unused()

    override fun randomBytes(count: Int): ByteArray = unused()

    private fun unused(): Nothing = error("unused")

    private const val SEED_SIZE = 32
}
