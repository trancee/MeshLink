package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily

internal const val NO_L2CAP_PSM: Int = 0
private const val ADVERTISED_PSM_MIN: Int = 128
private const val ADVERTISED_PSM_MAX: Int = 255
internal const val NO_ADVERTISED_L2CAP_PSM: UByte = 0u
internal val ADVERTISED_PSM_RANGE: IntRange = ADVERTISED_PSM_MIN..ADVERTISED_PSM_MAX
internal const val NO_GATT_CHARACTERISTIC_PERMISSIONS: ULong = 0u

internal fun BleTransportAdapter.discoveryPayload(l2capPsm: UByte): BleDiscoveryPayload {
    return BleDiscoveryPayload(
        protocolVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION,
        powerMode = currentPowerProfile.discoveryPowerMode,
        meshHash = BleDiscoveryContract.computeMeshHash(appId),
        l2capPsm = l2capPsm,
        keyHash = localKeyHash,
        platformFamily = BleDiscoveryPlatformFamily.IOS,
    )
}

internal fun BleTransportAdapter.advertisedPsm(psm: UShort): UByte {
    return if (psm.toInt() in ADVERTISED_PSM_RANGE) psm.toUByte() else NO_ADVERTISED_L2CAP_PSM
}
