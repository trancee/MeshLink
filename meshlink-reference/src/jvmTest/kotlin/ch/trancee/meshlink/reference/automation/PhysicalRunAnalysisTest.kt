package ch.trancee.meshlink.reference.automation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhysicalRunAnalysisTest {
    @Test
    fun malformedSummaryJsonStillTreatsLogBackedFailureAsHard() {
        // Arrange
        val runDir = createRunDir()
        writeText(runDir.resolve("summary.json"), "{not valid json")
        writeText(
            runDir.resolve("sender.log"),
            "REFERENCE_AUTOMATION proof.failed role=sender reason=relay-misroute\n",
        )
        writeText(
            runDir.resolve("passive.log"),
            "REFERENCE_AUTOMATION proof.complete role=passive\n",
        )

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("log-backed failure", analysis.reason)
        assertTrue(analysis.diagnostics.any { it.contains("malformed summary.json") })
    }

    @Test
    fun missingSenderLogIsHardFailureEvenWhenSummaryLooksHealthy() {
        // Arrange
        val runDir = createRunDir()
        writeText(
            runDir.resolve("summary.json"),
            """
            {
              "scenario": "relay",
              "routeIsDirect": true,
              "proof": {"failed": false}
            }
            """
                .trimIndent(),
        )
        writeText(
            runDir.resolve("passive.log"),
            "REFERENCE_AUTOMATION proof.complete role=passive\n",
        )

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("missing sender log", analysis.reason)
        assertTrue(analysis.diagnostics.any { it.contains("sender.log missing") })
    }

    @Test
    fun missingPassiveLogIsHardFailureEvenWhenSummaryLooksHealthy() {
        // Arrange
        val runDir = createRunDir()
        writeText(
            runDir.resolve("summary.json"),
            """
            {
              "scenario": "live",
              "routeIsDirect": true,
              "proof": {"failed": false}
            }
            """
                .trimIndent(),
        )
        writeText(runDir.resolve("sender.log"), "REFERENCE_AUTOMATION proof.complete role=sender\n")

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("missing passive log", analysis.reason)
        assertTrue(analysis.diagnostics.any { it.contains("passive.log missing") })
    }

    @Test
    fun proofFailedIsHardFailure() {
        // Arrange
        val runDir = createRunDir()
        writeText(
            runDir.resolve("summary.json"),
            """
            {
              "scenario": "relay",
              "routeIsDirect": true,
              "proof": {"failed": true}
            }
            """
                .trimIndent(),
        )
        writeText(runDir.resolve("sender.log"), "REFERENCE_AUTOMATION proof.complete role=sender\n")
        writeText(
            runDir.resolve("passive.log"),
            "REFERENCE_AUTOMATION proof.complete role=passive\n",
        )

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("proof.failed", analysis.reason)
        assertTrue(analysis.diagnostics.any { it.contains("proof.failed=true") })
    }

    @Test
    fun routeIsDirectFalseIsHardFailure() {
        // Arrange
        val runDir = createRunDir()
        writeText(
            runDir.resolve("summary.json"),
            """
            {
              "scenario": "relay",
              "routeIsDirect": false,
              "proof": {"failed": false}
            }
            """
                .trimIndent(),
        )
        writeText(
            runDir.resolve("sender.log"),
            "REFERENCE_AUTOMATION proof.complete role=sender routeIsDirect=false\n",
        )
        writeText(
            runDir.resolve("passive.log"),
            "REFERENCE_AUTOMATION proof.complete role=passive\n",
        )

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("routeIsDirect=false", analysis.reason)
        assertTrue(analysis.diagnostics.any { it.contains("routeIsDirect=false") })
    }

    @Test
    fun logBackedFailuresStayHardWhenSummaryDerivedFieldsAreUnavailable() {
        // Arrange
        val runDir = createRunDir()
        writeText(
            runDir.resolve("summary.json"),
            """
            {
              "scenario": "relay",
              "proof": {"failed": true}
            }
            """
                .trimIndent(),
        )
        writeText(
            runDir.resolve("sender.log"),
            "REFERENCE_AUTOMATION proof.failed role=sender reason=relay-misroute\n",
        )
        writeText(
            runDir.resolve("passive.log"),
            "REFERENCE_AUTOMATION proof.complete role=passive\n",
        )

        // Act
        val analysis = analyzePhysicalRun(runDir)

        // Assert
        assertTrue(analysis.hardFailure)
        assertEquals("log-backed failure", analysis.reason)
        assertTrue(
            analysis.diagnostics.any { it.contains("summary-derived routeIsDirect unavailable") }
        )
        assertTrue(analysis.diagnostics.any { it.contains("proof.failed role=sender") })
    }

    private data class AnalysisResult(
        val hardFailure: Boolean,
        val reason: String,
        val diagnostics: List<String>,
    )

    private fun analyzePhysicalRun(runDir: Path): AnalysisResult {
        val diagnostics = mutableListOf<String>()
        val summaryText = readOptionalText(runDir.resolve("summary.json"))
        val senderLog = readOptionalText(runDir.resolve("sender.log"))
        val passiveLog = readOptionalText(runDir.resolve("passive.log"))

        val summaryMalformed =
            summaryText != null && summaryText.isNotBlank() && !summaryText.trim().endsWith("}")
        if (summaryMalformed) {
            diagnostics += "malformed summary.json"
        }

        if (senderLog == null) {
            diagnostics += "sender.log missing"
            return AnalysisResult(
                hardFailure = true,
                reason = "missing sender log",
                diagnostics = diagnostics,
            )
        }
        if (passiveLog == null) {
            diagnostics += "passive.log missing"
            return AnalysisResult(
                hardFailure = true,
                reason = "missing passive log",
                diagnostics = diagnostics,
            )
        }

        if (senderLog.contains("REFERENCE_AUTOMATION proof.failed role=sender")) {
            diagnostics += "proof.failed role=sender"
            if (summaryText?.contains("\"routeIsDirect\"") != true) {
                diagnostics += "summary-derived routeIsDirect unavailable"
            }
            return AnalysisResult(
                hardFailure = true,
                reason = "log-backed failure",
                diagnostics = diagnostics,
            )
        }

        if (passiveLog.contains("REFERENCE_AUTOMATION proof.failed role=passive")) {
            diagnostics += "proof.failed role=passive"
            return AnalysisResult(
                hardFailure = true,
                reason = "log-backed failure",
                diagnostics = diagnostics,
            )
        }

        if (
            summaryText?.contains("\"proof\": {\"failed\": true}") == true ||
                summaryText?.contains("\"proof.failed\": true") == true
        ) {
            diagnostics += "proof.failed=true"
            return AnalysisResult(
                hardFailure = true,
                reason = "proof.failed",
                diagnostics = diagnostics,
            )
        }

        if (
            summaryText?.contains("\"routeIsDirect\": false") == true ||
                senderLog.contains("routeIsDirect=false")
        ) {
            diagnostics += "routeIsDirect=false"
            return AnalysisResult(
                hardFailure = true,
                reason = "routeIsDirect=false",
                diagnostics = diagnostics,
            )
        }

        return AnalysisResult(hardFailure = false, reason = "ok", diagnostics = diagnostics)
    }

    private fun createRunDir(): Path = Files.createTempDirectory("meshlink-physical-run-analysis")

    private fun writeText(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun readOptionalText(path: Path): String? =
        if (Files.exists(path)) Files.readString(path) else null
}
