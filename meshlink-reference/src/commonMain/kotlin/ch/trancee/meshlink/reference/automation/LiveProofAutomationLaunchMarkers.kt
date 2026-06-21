package ch.trancee.meshlink.reference.automation

public fun ReferenceAutomationConfigView.startupMarker(
    stage: String = "activity.onCreate"
): String {
    return "REFERENCE_AUTOMATION startup stage=$stage mode=$mode role=$role scenario=$scenario appId=$appId storage=$storageSubdirectory"
}
