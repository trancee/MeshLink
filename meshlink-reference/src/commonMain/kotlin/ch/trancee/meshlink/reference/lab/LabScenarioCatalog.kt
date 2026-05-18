package ch.trancee.meshlink.reference.lab

public data class LabScenario(
    public val scenarioId: String,
    public val title: String,
    public val summary: String,
)

public object LabScenarioCatalog {
    public val scenarios: List<LabScenario> =
        listOf(
            LabScenario(
                scenarioId = "proof-gatt-prototype",
                title = "Passive GATT prototype",
                summary =
                    "Proof-only transport behavior retained for investigation, not the supported product path.",
            ),
            LabScenario(
                scenarioId = "proof-gatt-notify",
                title = "GATT notify benchmark path",
                summary =
                    "Benchmark-oriented notify-side experiments remain isolated from the main reference experience.",
            ),
            LabScenario(
                scenarioId = "transport-telemetry",
                title = "Transport telemetry series",
                summary =
                    "Diagnostic and benchmark telemetry is available for internal lab review only.",
            ),
        )
}
