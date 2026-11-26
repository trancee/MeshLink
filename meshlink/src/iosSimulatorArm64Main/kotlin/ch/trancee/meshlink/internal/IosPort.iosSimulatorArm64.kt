package ch.trancee.meshlink.internal

/** The simulator has no Bluetooth, so CoreBluetooth is never touched there (ADR 0005). */
internal actual fun iosPort(): PlatformPort = UnsupportedRadioPort(AppleLog(IosOsLog()))
