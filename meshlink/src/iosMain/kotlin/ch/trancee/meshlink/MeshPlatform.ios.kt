package ch.trancee.meshlink

import ch.trancee.meshlink.internal.UnsupportedRadioPort

private val iosPlatform = MeshPlatform(UnsupportedRadioPort())

/**
 * The platform of this iOS installation. Nodes on it share its one Bluetooth radio, so one of them
 * runs at a time.
 *
 * The iOS simulator has no Bluetooth: nodes there stay [MeshState.Blocked] by
 * [Requirement.Unsupported] (ADR 0005). Until the Core Bluetooth radio lands, a device reports the
 * same.
 */
public fun MeshPlatform.Companion.ios(): MeshPlatform = iosPlatform
