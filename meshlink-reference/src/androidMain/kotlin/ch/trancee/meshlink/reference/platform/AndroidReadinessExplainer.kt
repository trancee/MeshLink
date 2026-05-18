package ch.trancee.meshlink.reference.platform

internal fun androidReadinessGuidance(): List<String> {
    return listOf(
        "Confirm Bluetooth is enabled and the Android device is on API 29 or newer.",
        "Use the debug install path so runtime permissions are granted where the platform allows it.",
        "Keep the device offline and near the iPhone peer before starting the guided exchange.",
    )
}
