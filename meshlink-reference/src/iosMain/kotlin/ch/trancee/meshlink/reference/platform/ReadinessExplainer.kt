package ch.trancee.meshlink.reference.platform

internal fun readinessGuidance(): List<String> {
    return listOf(
        "Confirm the iPhone is running iOS 14 or newer and Bluetooth is enabled.",
        "If you are using a physical iPhone, build with a local development team instead of storing it in the repository.",
        "Keep the device offline and near the Android peer before starting the guided exchange.",
    )
}
