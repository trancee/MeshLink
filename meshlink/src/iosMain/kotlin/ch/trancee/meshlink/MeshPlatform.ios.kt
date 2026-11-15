package ch.trancee.meshlink

import ch.trancee.meshlink.internal.iosPort

private val iosPlatform = MeshPlatform(iosPort())

/**
 * The platform of this iOS installation. Nodes on it share its one Bluetooth radio, so one of them
 * runs at a time.
 *
 * On a device the radio is CoreBluetooth: the host app declares `NSBluetoothAlwaysUsageDescription`
 * in its `Info.plist`, and iOS asks the user for Bluetooth when a node first starts, never on
 * [MeshLink.create] or here. The iOS simulator has no Bluetooth: nodes there stay
 * [MeshState.Blocked] by [Requirement.Unsupported] (ADR 0005).
 */
public fun MeshPlatform.Companion.ios(): MeshPlatform = iosPlatform
