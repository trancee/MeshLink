package ch.trancee.meshlink.internal

/** The port of this iOS installation: CoreBluetooth on a device, [UnsupportedRadioPort] on the simulator. */
internal expect fun iosPort(): PlatformPort
