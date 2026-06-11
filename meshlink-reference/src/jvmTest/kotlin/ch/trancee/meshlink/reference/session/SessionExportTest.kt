package ch.trancee.meshlink.reference.session

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionExportTest {
    @Test
    fun campaignProvenanceProjectsWhenPresentAndStaysAlignedAcrossRerenders() {
        // Arrange
        val runRoot = createTempDirectory("meshlink-reference-session-export")
        val provenancePath = "campaign-provenance/session-export.json"
        writeText(
            runRoot.resolve("campaign-plan.json"),
            """{"campaignProvenancePath":"$provenancePath"}""",
        )
        writeText(runRoot.resolve("campaign-state.json"), "{}")
        writeText(runRoot.resolve(provenancePath), "{\"sessionId\":\"session-1\"}")

        val firstOutput = runRoot.resolve("report-data-first.json")
        val secondOutput = runRoot.resolve("report-data-second.json")

        // Act
        runCampaignReportData(runRoot, firstOutput)
        runCampaignReportData(runRoot, secondOutput)

        val firstReportData = parseJsonObject(firstOutput)
        val secondReportData = parseJsonObject(secondOutput)

        // Assert
        assertEquals(firstReportData.keys, secondReportData.keys)
        assertEquals(firstReportData["sourceFiles"], secondReportData["sourceFiles"])
        assertEquals(firstReportData["campaignProvenance"], secondReportData["campaignProvenance"])

        val firstSourceFiles = firstReportData["sourceFiles"]!!.jsonObject
        val secondSourceFiles = secondReportData["sourceFiles"]!!.jsonObject
        assertEquals("campaign-plan.json", firstSourceFiles["campaignPlan"]?.jsonPrimitive?.content)
        assertEquals(
            "campaign-state.json",
            firstSourceFiles["campaignState"]?.jsonPrimitive?.content,
        )
        assertEquals(provenancePath, firstSourceFiles["campaignProvenance"]?.jsonPrimitive?.content)
        assertEquals(firstSourceFiles, secondSourceFiles)
        assertEquals(
            provenancePath,
            secondSourceFiles["campaignProvenance"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun campaignProvenanceRemainsAbsentWhenMissingAndStructurallyAlignedAcrossRerenders() {
        // Arrange
        val runRoot = createTempDirectory("meshlink-reference-session-export-missing")
        writeText(runRoot.resolve("campaign-plan.json"), "{}")
        writeText(runRoot.resolve("campaign-state.json"), "{}")

        val firstOutput = runRoot.resolve("report-data-first.json")
        val secondOutput = runRoot.resolve("report-data-second.json")

        // Act
        runCampaignReportData(runRoot, firstOutput)
        runCampaignReportData(runRoot, secondOutput)

        val firstReportData = parseJsonObject(firstOutput)
        val secondReportData = parseJsonObject(secondOutput)

        // Assert
        assertEquals(firstReportData.keys, secondReportData.keys)
        val firstSourceFiles = firstReportData["sourceFiles"]!!.jsonObject
        val secondSourceFiles = secondReportData["sourceFiles"]!!.jsonObject
        assertEquals(JsonNull, firstSourceFiles["campaignProvenance"])
        assertEquals(JsonNull, secondSourceFiles["campaignProvenance"])
        assertEquals(firstSourceFiles, secondSourceFiles)
    }

    private fun runCampaignReportData(runRoot: Path, outputJson: Path) {
        val script = Path.of("meshlink-reference/scripts/campaign_report_data.py")
        val process =
            ProcessBuilder(
                    "python3",
                    script.toString(),
                    "--run-root",
                    runRoot.toString(),
                    "--output-json",
                    outputJson.toString(),
                )
                .directory(File("."))
                .redirectErrorStream(true)
                .start()

        val exitCode = process.waitFor()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, exitCode, "campaign_report_data.py failed:\n$output")
    }

    private fun createTempDirectory(prefix: String): Path = Files.createTempDirectory(prefix)

    private fun writeText(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun parseJsonObject(path: Path): JsonObject =
        Json.parseToJsonElement(Files.readString(path)).jsonObject
}
