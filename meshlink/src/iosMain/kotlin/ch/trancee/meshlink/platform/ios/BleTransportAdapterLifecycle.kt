@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.convert
import kotlinx.coroutines.cancelChildren

internal fun BleTransportAdapter.stopTransport(clearPeers: Boolean): Unit {
    reportLog(
        "stopTransport clearPeers=$clearPeers activeLinks=${activeLinksByHint.size} activeGatt=${gattNotifyRegistry.size} pending=${pendingConnectionsByHint.size}"
    )
    discoverySuspended = false
    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    val psm = currentDiscoveryPayload.l2capPsm.toInt()
    if (psm in ADVERTISED_PSM_RANGE) {
        peripheralManager?.unpublishL2CAPChannel(psm.convert())
    }
    peripheralManager?.removeAllServices()
    gattNotifyServiceInstalled = false
    gattNotifyServiceCharacteristic = null
    pendingConnectionsByHint.clear()
    gattNotifyRegistry.stopAll()
    activeLinksByHint.keys.toList().forEach { hint ->
        closeLink(hintPeer = hint, reason = "transport stopped")
    }
    coroutineScope.coroutineContext.cancelChildren()
    if (clearPeers) {
        peerRegistry.clear()
        peerBindings.clear()
    }
}

internal fun BleTransportAdapter.refreshDiscoveryState(): Unit {
    reportLog(
        "refreshDiscoveryState started=$started suspended=$discoverySuspended centralState=${centralManager?.state} peripheralState=${peripheralManager?.state}"
    )
    centralManager?.stopScan()
    peripheralManager?.stopAdvertising()
    centralManager?.let(::startScanIfReady)
    startAdvertisingIfReady()
}
