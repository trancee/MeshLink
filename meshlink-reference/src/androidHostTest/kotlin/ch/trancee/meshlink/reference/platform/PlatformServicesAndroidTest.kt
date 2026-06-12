package ch.trancee.meshlink.reference.platform

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlatformServicesAndroidTest {
    @AfterTest fun tearDown(): Unit = fakeContext.stopServiceInvocations.clear()

    @Test
    fun createPlatformServicesUsesTheAppFilesDirectoryAsDocumentStoreStorage() = runTest {
        // Arrange
        fakeContext = FakeContext(permissionGranted = true)
        val services = createPlatformServices(fakeContext)
        val path = "platform-services/document.txt"

        // Act
        services.documentStore.writeText(path, "hello")
        val storedValue = services.documentStore.readText(path)

        // Assert
        assertEquals("Android", services.platformName)
        assertEquals(ReferenceAuthorityMode.LIVE, services.defaultAuthorityMode)
        assertEquals(readinessGuidance(), services.readinessGuidance)
        assertEquals(emptyList(), services.readinessBlockers)
        assertEquals("hello", storedValue)
    }

    @Test
    fun createAutomationPlatformServicesNormalizesStorageAndBlocksByDefaultWhenRequested() =
        runTest {
            // Arrange
            fakeContext = FakeContext(permissionGranted = false)

            // Act
            val services =
                createAutomationPlatformServices(
                    context = fakeContext,
                    storageSubdirectory = "../escape",
                    blocked = true,
                )

            // Assert
            assertEquals("Android", services.platformName)
            assertEquals(ReferenceAuthorityMode.LIVE, services.defaultAuthorityMode)
            assertEquals(ReferenceAutomationMode.SCRIPTED_UI, services.automationConfig?.mode)
            assertEquals(ReferenceAutomationRole.PASSIVE, services.automationConfig?.role)
            assertEquals("demo.meshlink.reference.automation", services.automationConfig?.appId)
            assertEquals("../escape", services.automationConfig?.storageSubdirectory)
            assertEquals(
                listOf(
                    "Enable Bluetooth on Android before starting the guided exchange.",
                    "Grant the required nearby-device permissions for MeshLink.",
                ),
                services.readinessBlockers,
            )
            assertNull(services.powerMitigationStatus)
        }

    @Test
    fun createLiveAutomationPlatformServicesConfiguresLiveProofAutomationAndPowerMitigation() =
        runTest {
            // Arrange
            fakeContext = FakeContext(permissionGranted = false)

            // Act
            val services =
                createLiveAutomationPlatformServices(
                    context = fakeContext,
                    storageSubdirectory = "live-proof",
                    appId = "demo.meshlink.reference.live-test",
                    role = ReferenceAutomationRole.SENDER,
                    requiredPeerCount = 2,
                    targetPeerIndex = 1,
                    targetPeerId = "peer-123",
                    scenario = ReferenceAutomationScenario.DIRECT_GUIDED,
                )

            // Assert
            assertEquals(ReferenceAuthorityMode.LIVE, services.defaultAuthorityMode)
            assertEquals(ReferenceAutomationMode.LIVE_PROOF, services.automationConfig?.mode)
            assertEquals(ReferenceAutomationRole.SENDER, services.automationConfig?.role)
            assertEquals("demo.meshlink.reference.live-test", services.automationConfig?.appId)
            assertEquals("live-proof", services.automationConfig?.storageSubdirectory)
            assertEquals(2, services.automationConfig?.requiredPeerCount)
            assertEquals(1, services.automationConfig?.targetPeerIndex)
            assertEquals("peer-123", services.automationConfig?.targetPeerId)
            assertEquals(
                ReferenceAutomationScenario.DIRECT_GUIDED,
                services.automationConfig?.scenario,
            )
            assertEquals(
                "Foreground wake lock active for live-proof automation sessions.",
                services.powerMitigationStatus,
            )
            assertTrue(services.readinessBlockers.isNotEmpty())
            assertTrue(fakeContext.stopServiceInvocations.isEmpty())
        }

    private class FakeContext(permissionGranted: Boolean) : ContextWrapper(null) {
        private val tempDir = Files.createTempDirectory("meshlink-reference-android-host").toFile()
        private val permissionResult =
            if (permissionGranted) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }

        val stopServiceInvocations: MutableList<Intent> = mutableListOf()

        override fun getApplicationContext(): Context = this

        override fun getPackageName(): String = "ch.trancee.meshlink.reference"

        override fun getFilesDir() = tempDir

        override fun checkSelfPermission(permission: String): Int = permissionResult

        override fun getSystemService(name: String): Any? = null

        override fun stopService(service: Intent): Boolean {
            stopServiceInvocations += service
            return true
        }
    }

    private companion object {
        private var fakeContext: FakeContext = FakeContext(permissionGranted = true)
    }
}
