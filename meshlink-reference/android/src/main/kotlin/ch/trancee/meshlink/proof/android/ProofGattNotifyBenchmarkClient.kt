@file:Suppress("ReturnCount", "MagicNumber", "MaxLineLength")

package ch.trancee.meshlink.proof.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream

@SuppressLint("MissingPermission")
internal class ProofGattNotifyBenchmarkClient(
    private val context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit,
    private val stateDidChange: (String) -> Unit,
    private val appId: String,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var activeTransfer: ActiveTransfer? = null
    private var pendingAckWrite: PendingAckWrite? = null
    private var attemptStartedAtNanos: Long = 0L
    private var finished: Boolean = false
    private var servicesDiscoveryStarted: Boolean = false

    private val scanTimeoutRunnable = Runnable {
        finishIfNeeded("SCAN_TIMEOUT")
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.firstOrNull()?.let(::handleScanResult)
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val stateLabel =
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                        else -> newState.toString()
                    }
                logger("GATT notify benchmark connection status=$status state=$stateLabel")
                if (finished) {
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stateDidChange("Configuring(GATT notify benchmark)")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    servicesDiscoveryStarted = false
                    val requestedMtu = gatt.requestMtu(517)
                    if (!requestedMtu) {
                        servicesDiscoveryStarted = true
                        if (!gatt.safeDiscoverServices(logger)) {
                            finishIfNeeded("SERVICE_DISCOVERY_PERMISSION_DENIED")
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finishIfNeeded("GATT_DISCONNECTED")
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                logger("GATT notify benchmark mtu=$mtu status=$status")
                if (finished || servicesDiscoveryStarted) {
                    return
                }
                servicesDiscoveryStarted = true
                if (!gatt.safeDiscoverServices(logger)) {
                    finishIfNeeded("SERVICE_DISCOVERY_PERMISSION_DENIED")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (finished) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("SERVICE_DISCOVERY_FAILED")
                    return
                }
                val service = gatt.getService(ProofGattNotifyBenchmarkProtocol.SERVICE_UUID)
                if (service == null) {
                    finishIfNeeded("SERVICE_MISSING")
                    return
                }
                val appHashCharacteristic =
                    service.getCharacteristic(ProofGattNotifyBenchmarkProtocol.APP_HASH_CHARACTERISTIC_UUID)
                notifyCharacteristic =
                    service.getCharacteristic(ProofGattNotifyBenchmarkProtocol.NOTIFY_CHARACTERISTIC_UUID)
                ackCharacteristic =
                    service.getCharacteristic(ProofGattNotifyBenchmarkProtocol.ACK_CHARACTERISTIC_UUID)
                if (
                    appHashCharacteristic == null ||
                        notifyCharacteristic == null ||
                        ackCharacteristic == null
                ) {
                    finishIfNeeded("CHARACTERISTIC_MISSING")
                    return
                }
                logger("GATT notify benchmark discovered service; reading app hash")
                @Suppress("DEPRECATION")
                gatt.readCharacteristic(appHashCharacteristic)
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (finished) {
                    return
                }
                if (characteristic.uuid != ProofGattNotifyBenchmarkProtocol.APP_HASH_CHARACTERISTIC_UUID) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("APP_HASH_READ_FAILED")
                    return
                }
                val value = characteristic.value
                if (!ProofGattNotifyBenchmarkProtocol.matchesAppHash(value, appId)) {
                    finishIfNeeded("APP_ID_MISMATCH")
                    return
                }
                enableNotifications(gatt)
            }

            @Suppress("DEPRECATION")
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (finished) {
                    return
                }
                if (descriptor.uuid != ProofGattNotifyBenchmarkProtocol.CCCD_UUID) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("NOTIFY_ENABLE_FAILED")
                    return
                }
                logger("GATT notify benchmark notifications enabled")
                stateDidChange("Running(GATT notify benchmark passive)")
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (finished || characteristic.uuid != ProofGattNotifyBenchmarkProtocol.NOTIFY_CHARACTERISTIC_UUID) {
                    return
                }
                when (val frame = ProofGattNotifyBenchmarkProtocol.decodeNotifyFrame(characteristic.value)) {
                    is ProofGattNotifyBenchmarkProtocol.StartFrame -> {
                        activeTransfer =
                            ActiveTransfer(tokenHex = frame.tokenHex, totalBytes = frame.totalBytes)
                        logger(
                            "GATT notify benchmark start token=${frame.tokenHex} bytes=${frame.totalBytes} elapsedMs=${elapsedMillisSince(attemptStartedAtNanos)}"
                        )
                        stateDidChange("Receiving(GATT notify benchmark)")
                    }
                    is ProofGattNotifyBenchmarkProtocol.DataFrame -> {
                        val transfer = activeTransfer
                        if (transfer == null || transfer.tokenHex != frame.tokenHex) {
                            finishIfNeeded("TOKEN_MISMATCH")
                            return
                        }
                        runCatching { transfer.append(frame.chunk) }
                            .onFailure {
                                finishIfNeeded("TRANSFER_OVERFLOW")
                                return
                            }
                        if (transfer.isComplete()) {
                            logger(
                                "MSG from gattNotify bytes=${transfer.totalBytes} benchmarkToken=${transfer.tokenHex}"
                            )
                            sendAck(gatt, transfer)
                            activeTransfer = null
                        }
                    }
                    null -> finishIfNeeded("UNKNOWN_FRAME")
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                val pendingAckWrite = pendingAckWrite ?: return
                if (characteristic.uuid != ProofGattNotifyBenchmarkProtocol.ACK_CHARACTERISTIC_UUID) {
                    return
                }
                this@ProofGattNotifyBenchmarkClient.pendingAckWrite = null
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logger(
                        "BENCHMARK receipt send(${pendingAckWrite.tokenHex.takeLast(6)}) -> Sent token=${pendingAckWrite.tokenHex} attempt=1"
                    )
                    finished = true
                    stateDidChange("Completed(GATT notify benchmark passive)")
                } else {
                    logger(
                        "BENCHMARK receipt send(${pendingAckWrite.tokenHex.takeLast(6)}) -> NotSent(reason=GATT_WRITE_FAILED) token=${pendingAckWrite.tokenHex} attempt=1"
                    )
                    finishIfNeeded("ACK_WRITE_FAILED")
                }
            }
        }

    fun start() {
        stopInternal(updateStateToStopped = false)
        finished = false
        servicesDiscoveryStarted = false
        attemptStartedAtNanos = SystemClock.elapsedRealtimeNanos()
        stateDidChange("Scanning(GATT notify benchmark)")
        logger("GATT notify benchmark scanning appId=$appId")
        val adapter = bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
        scanner = adapter.bluetoothLeScanner ?: error("BluetoothLeScanner is unavailable")
        val filters =
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(ProofGattNotifyBenchmarkProtocol.SERVICE_UUID))
                    .build()
            )
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (!scanner.safeStartScan(filters, settings, scanCallback, logger)) {
            finishIfNeeded("SCAN_PERMISSION_DENIED")
            return
        }
        mainHandler.postDelayed(scanTimeoutRunnable, 15_000L)
    }

    fun stop() {
        finished = true
        stopInternal(updateStateToStopped = true)
    }

    private fun handleScanResult(result: ScanResult) {
        if (finished) {
            return
        }
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        scanner.safeStopScan(logger, scanCallback)
        logger(
            "GATT notify benchmark discovered device=${result.device.address} rssi=${result.rssi}"
        )
        stateDidChange("Connecting(GATT notify benchmark)")
        gatt = result.device.safeConnectGatt(context, false, gattCallback, logger)
        if (gatt == null) {
            finishIfNeeded("GATT_CONNECT_PERMISSION_DENIED")
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val notifyCharacteristic = notifyCharacteristic ?: run {
            finishIfNeeded("NOTIFY_CHARACTERISTIC_MISSING")
            return
        }
        val cccd = notifyCharacteristic.getDescriptor(ProofGattNotifyBenchmarkProtocol.CCCD_UUID)
        if (cccd == null) {
            finishIfNeeded("CCCD_MISSING")
            return
        }
        if (!gatt.safeSetCharacteristicNotification(notifyCharacteristic, true, logger)) {
            finishIfNeeded("NOTIFY_PERMISSION_DENIED")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.safeWriteDescriptor(cccd, logger)) {
            finishIfNeeded("NOTIFY_DESCRIPTOR_WRITE_PERMISSION_DENIED")
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun sendAck(gatt: BluetoothGatt, transfer: ActiveTransfer) {
        val ackCharacteristic = ackCharacteristic ?: run {
            finishIfNeeded("ACK_CHARACTERISTIC_MISSING")
            return
        }
        val value = ProofGattNotifyBenchmarkProtocol.encodeAck(transfer.tokenHex, transfer.totalBytes)
        pendingAckWrite = PendingAckWrite(tokenHex = transfer.tokenHex, totalBytes = transfer.totalBytes)
        ackCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ackCharacteristic.value = value
        val enqueued = gatt.safeWriteCharacteristic(ackCharacteristic, logger)
        if (!enqueued) {
            pendingAckWrite = null
            logger(
                "BENCHMARK receipt send(${transfer.tokenHex.takeLast(6)}) -> NotSent(reason=WRITE_ENQUEUE_FAILED) token=${transfer.tokenHex} attempt=1"
            )
            finishIfNeeded("ACK_WRITE_ENQUEUE_FAILED")
        }
    }

    private fun stopInternal(updateStateToStopped: Boolean) {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        scanner.safeStopScan(logger, scanCallback)
        scanner = null
        gatt.safeClose(logger)
        gatt = null
        notifyCharacteristic = null
        ackCharacteristic = null
        activeTransfer = null
        pendingAckWrite = null
        if (updateStateToStopped) {
            stateDidChange("Stopped")
        }
    }

    private fun finishIfNeeded(reason: String) {
        if (finished) {
            return
        }
        finished = true
        logger("GATT notify benchmark failed reason=$reason elapsedMs=${elapsedMillisSince(attemptStartedAtNanos)}")
        stateDidChange("Error(GATT notify benchmark)")
        stopInternal(updateStateToStopped = false)
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L
    }

    private class ActiveTransfer(
        val tokenHex: String,
        val totalBytes: Int,
    ) {
        private val bytes = ByteArrayOutputStream(totalBytes)

        fun append(chunk: ByteArray) {
            require(bytes.size() + chunk.size <= totalBytes) {
                "Received ${bytes.size() + chunk.size} bytes for $totalBytes-byte transfer"
            }
            bytes.write(chunk)
        }

        fun isComplete(): Boolean {
            return bytes.size() == totalBytes
        }
    }

    private data class PendingAckWrite(val tokenHex: String, val totalBytes: Int)
}
