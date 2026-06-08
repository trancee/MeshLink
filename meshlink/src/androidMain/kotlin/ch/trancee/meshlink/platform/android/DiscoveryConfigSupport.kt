package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily

internal fun buildDiscoveryPayload(
    appId: String,
    localKeyHash: ByteArray,
    currentPowerProfile: PowerProfile,
    l2capPsm: UByte,
): BleDiscoveryPayload {
    return BleDiscoveryPayload(
        protocolVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION,
        powerMode = currentPowerProfile.discoveryPowerMode,
        meshHash = BleDiscoveryContract.computeMeshHash(appId),
        l2capPsm = l2capPsm,
        keyHash = localKeyHash,
        platformFamily = BleDiscoveryPlatformFamily.ANDROID,
    )
}

public enum class DiscoveryAdvertisementCarrier {
    UUID_PAIR,
    UUID_PAIR_PLUS_SERVICE_DATA,
}

public object AndroidDiscoveryAdvertisementConfig {
    @Volatile
    public var carrier: DiscoveryAdvertisementCarrier = DiscoveryAdvertisementCarrier.UUID_PAIR
}

public data class DiscoveryAdvertisePlan(
    internal val serviceUuids: List<String>,
    internal val serviceData: Map<String, ByteArray>,
)

internal fun buildAdvertisePlan(
    payload: BleDiscoveryPayload,
    carrier: DiscoveryAdvertisementCarrier = DiscoveryAdvertisementCarrier.UUID_PAIR,
): DiscoveryAdvertisePlan {
    val payloadUuid = payload.payloadUuidString()
    val serviceUuids =
        listOf(
            BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED,
            payloadUuid,
        )
    val serviceData =
        when (carrier) {
            DiscoveryAdvertisementCarrier.UUID_PAIR -> emptyMap()
            DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA ->
                mapOf(payloadUuid to payload.encode())
        }
    return DiscoveryAdvertisePlan(serviceUuids = serviceUuids, serviceData = serviceData)
}

internal fun buildAdvertiseData(
    payload: BleDiscoveryPayload,
    carrier: DiscoveryAdvertisementCarrier = AndroidDiscoveryAdvertisementConfig.carrier,
): AdvertiseData {
    val plan = buildAdvertisePlan(payload = payload, carrier = carrier)
    val builder = AdvertiseData.Builder().setIncludeDeviceName(false)
    plan.serviceUuids.forEach { serviceUuid ->
        builder.addServiceUuid(ParcelUuid.fromString(serviceUuid))
    }
    plan.serviceData.forEach { (serviceUuid, bytes) ->
        builder.addServiceData(ParcelUuid.fromString(serviceUuid), bytes)
    }
    return builder.build()
}

internal fun buildAdvertiseSettings(currentPowerProfile: PowerProfile): AdvertiseSettings {
    return AdvertiseSettings.Builder()
        .setAdvertiseMode(currentPowerProfile.advertiseMode)
        .setConnectable(true)
        .setTxPowerLevel(currentPowerProfile.txPowerLevel)
        .build()
}

internal fun buildScanFilters(): List<ScanFilter> {
    return listOf(
        ScanFilter.Builder()
            .setServiceUuid(
                ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)
            )
            .build()
    )
}

internal fun buildScanSettings(currentPowerProfile: PowerProfile): ScanSettings {
    return ScanSettings.Builder().setScanMode(currentPowerProfile.scanMode).build()
}
