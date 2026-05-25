package ch.trancee.meshlink.platform.android

import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily

internal fun buildAndroidDiscoveryPayload(
    appId: String,
    localKeyHash: ByteArray,
    currentPowerProfile: AndroidPowerProfile,
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

internal fun buildAndroidAdvertiseData(payload: BleDiscoveryPayload): AdvertiseData {
    return AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addServiceUuid(
            ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)
        )
        .addServiceUuid(ParcelUuid.fromString(payload.payloadUuidString()))
        .build()
}

internal fun buildAndroidAdvertiseSettings(
    currentPowerProfile: AndroidPowerProfile
): AdvertiseSettings {
    return AdvertiseSettings.Builder()
        .setAdvertiseMode(currentPowerProfile.advertiseMode)
        .setConnectable(true)
        .setTxPowerLevel(currentPowerProfile.txPowerLevel)
        .build()
}

internal fun buildAndroidScanFilters(): List<ScanFilter> {
    return listOf(
        ScanFilter.Builder()
            .setServiceUuid(
                ParcelUuid.fromString(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)
            )
            .build()
    )
}

internal fun buildAndroidScanSettings(currentPowerProfile: AndroidPowerProfile): ScanSettings {
    return ScanSettings.Builder().setScanMode(currentPowerProfile.scanMode).build()
}
