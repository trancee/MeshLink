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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReferenceAppWorkflowTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString(OPT_IN_ARGUMENT) == "true")
    }

    @Test
    fun guidedWorkflowShowsLiveProofAndSoloFallback() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")

            tapTextWithScroll(device, "Start MeshLink", searchDirection = Direction.UP)
            waitForTextContains(device, "Peer: 654321")

            tapTextWithScroll(device, "Send Hello", searchDirection = Direction.UP)
            tapTextWithScroll(device, "Solo exploration", searchDirection = Direction.UP)

            waitForText(device, "Solo exploration")
            waitForText(device, "Non-authoritative")
        }
    }

    @Test
    fun advancedLifecycleTrustResetTransferAndLabFlows() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")
            tapTextWithScroll(device, "Start MeshLink", searchDirection = Direction.UP)
            tapText(device, "Controls")

            waitForText(device, "Advanced controls")
            waitForTextContains(device, "Power mode: Automatic")
            waitForTextContains(device, "Mesh state: Running")

            tapTextWithScroll(device, "Pause", searchDirection = Direction.UP)
            tapTextWithScroll(device, "Resume", searchDirection = Direction.UP)

            tapTextWithScroll(device, "Send large transfer", searchDirection = Direction.UP)
            tapTextWithScroll(
                device,
                "Reset trust for selected peer",
                searchDirection = Direction.UP,
            )
            waitForTextContainsWithScroll(
                device,
                "Trust: Forgotten",
                searchDirection = Direction.DOWN,
            )

            tapText(device, "Lab")
            waitForText(device, "Lab")
            waitForText(device, "Non-normative")
        }
    }

    @Test
    fun timelineHistoryAndRedactedExportFlowWritesRetainedArtifacts() {
        val storageSubdirectory = UUID.randomUUID().toString()

        withLaunchedAutomationApp(storageSubdirectory = storageSubdirectory) { device ->
            waitForText(device, "Guided first exchange")
            tapTextWithScroll(device, "Start MeshLink", searchDirection = Direction.UP)
            waitForTextContains(device, "Peer: 654321")
            tapTextWithScroll(device, "Send Hello", searchDirection = Direction.UP)
            tapText(device, "Evidence")

            waitForText(device, "Technical timeline")
            tapTextWithScroll(device, "End session", searchDirection = Direction.UP)
            tapText(device, "End without full export")
            waitForTextContains(device, "Retained 1")
            tapText(device, "Export session")
            waitForText(device, "Redacted export")
            tapText(device, "Redacted export")

            val exportPath = waitForLatestAutomationExportPath(storageSubdirectory)
            tapText(device, "Recent history")

            waitForText(device, "Recent history")
            waitForText(device, "Clear all")

            val exportJson = readAutomationFile(storageSubdirectory, exportPath)
            val historyJson = readAutomationFile(storageSubdirectory, "reference/history.json")

            assertTrue(exportPath.startsWith("reference/exports/"))
            assertTrue(exportPath.endsWith(".json"))
            assertTrue(exportJson.contains("\"defaultMode\": \"redacted-preview\""))
            assertTrue(exportJson.contains("\"fullPayloadIncluded\": false"))
            assertTrue(exportJson.contains("\"operatorOptInRecorded\": false"))
            assertTrue(
                "Expected createdAt to use UTC ISO 8601 in the written export file",
                UTC_ISO_8601_CREATED_AT_REGEX.containsMatchIn(exportJson),
            )
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
            waitForText(device, "Startup blocked")
            waitForText(device, "Start MeshLink")
        }
    }

    private companion object {
        private const val OPT_IN_ARGUMENT: String = "meshlink.reference.workflow"
        private val UTC_ISO_8601_CREATED_AT_REGEX =
            Regex("\\\"createdAt\\\"\\s*:\\s*\\\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z\\\"")
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
                append(" -f 0x10008000 --ez ")
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
        if (tapVisibleTextFromHierarchyDump(device, text)) {
            device.waitForIdle()
            return
        }
        swipe(device, searchDirection)
    }
    error("Expected text '$text' to appear after scrolling")
}

private fun waitForTextContainsWithScroll(
    device: UiDevice,
    text: String,
    searchDirection: Direction,
    timeoutMillis: Long = 15_000L,
): UiObject2 {
    return waitForObjectWithScroll(
        device,
        By.textContains(text),
        searchDirection = searchDirection,
        timeoutMillis = timeoutMillis,
    ) ?: error("Expected text containing '$text' to appear after scrolling")
}

private fun waitForLatestAutomationExportPath(
    storageSubdirectory: String,
    timeoutMillis: Long = 15_000L,
): String {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val exportDirectory =
        File(context.filesDir, "ui-automation/$storageSubdirectory/reference/exports")
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val latestExport =
            exportDirectory
                .listFiles()
                ?.filter { candidate -> candidate.isFile && candidate.extension == "json" }
                ?.maxByOrNull { candidate -> candidate.lastModified() }
        if (latestExport != null) {
            return "reference/exports/${latestExport.name}"
        }
        Thread.sleep(250)
    }
    error("Expected an exported session artifact to be written")
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

private fun tapVisibleTextFromHierarchyDump(device: UiDevice, text: String): Boolean {
    val hierarchyDump =
        executeShellCommand(
            "uiautomator dump /sdcard/meshlink-reference-ui.xml >/dev/null && cat /sdcard/meshlink-reference-ui.xml"
        )
    val boundsMatch =
        Regex(
                "text=\\\"${Regex.escape(text)}\\\"[^>]*bounds=\\\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\\\""
            )
            .find(hierarchyDump) ?: return false
    val (left, top, right, bottom) = boundsMatch.destructured
    val centerX = (left.toInt() + right.toInt()) / 2
    val centerY = (top.toInt() + bottom.toInt()) / 2
    return device.click(centerX, centerY)
}

private fun waitForObjectWithScroll(
    device: UiDevice,
    selector: androidx.test.uiautomator.BySelector,
    searchDirection: Direction,
    timeoutMillis: Long,
): UiObject2? {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        val found = waitForObject(device, selector, 1_000L)
        if (found != null) {
            return found
        }
        swipe(device, searchDirection)
    }
    return null
}

private fun swipe(device: UiDevice, direction: Direction): Unit {
    val startX = device.displayWidth / 2
    val upperY = (device.displayHeight * 0.50f).toInt()
    val lowerY = (device.displayHeight * 0.77f).toInt()
    when (direction) {
        Direction.DOWN -> device.swipe(startX, upperY, startX, lowerY, 24)
        Direction.UP -> device.swipe(startX, lowerY, startX, upperY, 24)
        else -> error("Unsupported swipe direction: $direction")
    }
    device.waitForIdle()
}
