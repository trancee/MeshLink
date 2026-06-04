package ch.trancee.meshlink.reference.automation

public fun ReferenceAutomationConfig.startupMarker(stage: String = "activity.onCreate"): String {
    return "REFERENCE_AUTOMATION startup stage=$stage mode=$mode role=$role scenario=${scenario.wireValue()} appId=$appId storage=$storageSubdirectory"
}
