package ch.trancee.meshlink.reference

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceAppAndroidWorkflowTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true")
    }

    @Test
    fun guidedWorkflowShowsLiveProofAndSoloFallback() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")

            tapText(device, "Start MeshLink")
            waitForTextContains(device, "Selected peer: 654321")

            tapText(device, "Send Hello")
            tapText(device, "Solo mode")

            waitForText(device, "Solo exploration")
            waitForText(device, "Non-authoritative")
        }
    }

    @Test
    fun advancedLifecycleTrustResetTransferAndLabFlows() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")
            tapText(device, "Start MeshLink")
            tapText(device, "Advanced controls")

            waitForText(device, "Advanced controls")
            waitForTextContains(device, "Power mode: Automatic")

            tapText(device, "Pause")
            waitForText(device, "Resume")

            tapText(device, "Resume")
            waitForText(device, "Pause")

            tapTextWithScroll(device, "Send large transfer", searchDirection = Direction.UP)
            tapTextWithScroll(device, "Forget selected peer", searchDirection = Direction.UP)
            waitForTextContains(device, "Peer trust reset")

            tapTextWithScroll(device, "Lab", searchDirection = Direction.DOWN)
            waitForText(device, "Lab")
            waitForText(device, "Non-normative")
        }
    }

    @Test
    fun timelineHistoryAndRedactedExportFlowWritesRetainedArtifacts() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")
            tapText(device, "Start MeshLink")
            waitForTextContains(device, "Selected peer: 654321")
            tapText(device, "Send Hello")
            tapText(device, "Technical timeline")

            waitForText(device, "Technical timeline")
            tapText(device, "Retain session")
            tapText(device, "Export redacted")

            val exportPath = waitForLastExportPath(device)
            tapText(device, "Recent history")

            waitForText(device, "Recent history")
            tapText(device, "Open")
            waitForText(device, "Return to live")

            val exportJson = readAutomationFile(storageSubdirectory, exportPath)
            val historyJson = readAutomationFile(storageSubdirectory, "reference/history.json")

            assertTrue(exportPath.startsWith("reference/exports/"))
            assertTrue(exportPath.endsWith(".json"))
            assertTrue(exportJson.contains("\"defaultMode\": \"redacted-preview\""))
            assertTrue(exportJson.contains("\"fullPayloadIncluded\": false"))
            assertTrue(exportJson.contains("\"operatorOptInRecorded\": false"))
            assertFalse(exportJson.contains("\"fullPayload\":"))
            assertTrue(historyJson.contains("\"historyStatus\": \"RETAINED\""))
        }
    }

    @Test
    fun blockedStartupShowsRecoveryGuidance() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory, blocked = true) {
            device ->
            waitForText(device, "Guided first exchange")
            waitForTextContains(device, "Resolve startup blockers")

            assertNotNull(waitForObject(device, By.text("Start MeshLink"), 10_000L))
        }
    }

    private companion object {
        private const val OPT_IN_ARGUMENT: String = "meshlink.reference.workflow"
    }
}

private inline fun withLaunchedAutomationApp(
    storageSubdirectory: String,
    blocked: Boolean = false,
    block: (UiDevice) -> Unit,
): Unit {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val device = uiDevice()
    try {
        if (!device.isScreenOn) {
            device.wakeUp()
        }
        device.pressHome()
        device.waitForIdle()
        executeShellCommand(
            buildString {
                append("am start -W -n ")
                append(context.packageName)
                append('/')
                append(MainActivity::class.java.name)
                append(" --ez ")
                append(MainActivity.EXTRA_UI_AUTOMATION)
                append(" true --es ")
                append(MainActivity.EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY)
                append(' ')
                append(storageSubdirectory)
                append(" --ez ")
                append(MainActivity.EXTRA_UI_AUTOMATION_BLOCKED)
                append(' ')
                append(if (blocked) "true" else "false")
            }
        )
        assertTrue(
            "Expected ${context.packageName} to enter the foreground",
            device.wait(Until.hasObject(By.pkg(context.packageName)), 15_000L),
        )
        device.waitForIdle()
        block(device)
    } finally {
        device.pressHome()
        device.waitForIdle()
    }
}

private fun uiDevice(): UiDevice {
    return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
}

private fun waitForText(device: UiDevice, text: String, timeoutMillis: Long = 15_000L): UiObject2 {
    return waitForObject(device, By.text(text), timeoutMillis)
        ?: error("Expected text '$text' to appear")
}

private fun waitForTextContains(
    device: UiDevice,
    text: String,
    timeoutMillis: Long = 15_000L,
): UiObject2 {
    return waitForObject(device, By.textContains(text), timeoutMillis)
        ?: error("Expected text containing '$text' to appear")
}

private fun tapText(device: UiDevice, text: String, timeoutMillis: Long = 15_000L): Unit {
    val object2 = waitForText(device, text, timeoutMillis)
    object2.click()
    device.waitForIdle()
}

private fun tapTextWithScroll(
    device: UiDevice,
    text: String,
    searchDirection: Direction,
    timeoutMillis: Long = 15_000L,
): Unit {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val found = waitForObject(device, By.text(text), 1_000L)
        if (found != null) {
            found.click()
            device.waitForIdle()
            return
        }
        swipe(device, searchDirection)
    }
    error("Expected text '$text' to appear after scrolling")
}

private fun waitForLastExportPath(device: UiDevice, timeoutMillis: Long = 15_000L): String {
    val exportLabel =
        waitForObject(device, By.textStartsWith("Last export: "), timeoutMillis)
            ?: error("Expected last export path to appear")
    return exportLabel.text.removePrefix("Last export: ")
}

private fun executeShellCommand(command: String): String {
    val descriptor =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    descriptor.use { parcelFileDescriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).bufferedReader().use {
            reader ->
            return reader.readText()
        }
    }
}

private fun readAutomationFile(storageSubdirectory: String, relativePath: String): String {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val file = File(context.filesDir, "ui-automation/$storageSubdirectory/$relativePath")
    assertTrue("Expected automation file $relativePath to exist", file.exists())
    return file.readText()
}

private fun waitForObject(
    device: UiDevice,
    selector: androidx.test.uiautomator.BySelector,
    timeoutMillis: Long,
): UiObject2? {
    return device.wait(Until.findObject(selector), timeoutMillis)
}

private fun swipe(device: UiDevice, direction: Direction): Unit {
    val startX = device.displayWidth / 2
    val upperY = (device.displayHeight * 0.25f).toInt()
    val lowerY = (device.displayHeight * 0.75f).toInt()
    when (direction) {
        Direction.DOWN -> device.swipe(startX, upperY, startX, lowerY, 24)
        Direction.UP -> device.swipe(startX, lowerY, startX, upperY, 24)
        else -> error("Unsupported swipe direction: $direction")
    }
    device.waitForIdle()
}
