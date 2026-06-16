@file:Suppress("TooManyFunctions", "ReturnCount", "MagicNumber", "MaxLineLength", "UnusedPrivateProperty")

package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private const val BENCHMARK_CLIENT_LOG_TAG = "MeshLinkReferenceAutomation"
private const val SCAN_TIMEOUT_MILLIS: Long = 15_000L
private const val SERVICE_DISCOVERY_RETRY_MILLIS: Long = 1_500L
private const val MAX_SERVICE_DISCOVERY_ATTEMPTS: Int = 2
private const val MAX_DATA_CHUNK_BYTES: Int = 11

internal class ProofGattBenchmarkClient(
    context: Context,
    private val bluetoothManager: BluetoothManager,
    private val logger: (String) -> Unit,
    private val stateDidChange: (String) -> Unit,
    private val appId: String,
) {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var started: Boolean = false
    private var finished: Boolean = false
    private var transferTokenHex: String? = null
    private var payload: ByteArray = ByteArray(0)
    private var payloadChunks: List<ByteArray> = emptyList()
    private var nextChunkIndex: Int = 0
    private var pendingWritePhase: PendingWritePhase = PendingWritePhase.NONE
    private var dataWriteRetryCount: Int = 0
    private var serviceDiscoveryAttemptCount: Int = 0

    private val scanTimeoutRunnable = Runnable {
        finishIfNeeded("SCAN_TIMEOUT")
    }

    private val serviceDiscoveryRetryRunnable: Runnable = Runnable {
        if (finished || writeCharacteristic != null || gatt == null) {
            return@Runnable
        }
        if (serviceDiscoveryAttemptCount >= MAX_SERVICE_DISCOVERY_ATTEMPTS) {
            finishIfNeeded("SERVICE_DISCOVERY_TIMEOUT")
            return@Runnable
        }
        serviceDiscoveryAttemptCount += 1
        logger(
            "GATT benchmark service discovery retry attempt=$serviceDiscoveryAttemptCount"
        )
        if (!gatt!!.safeDiscoverServices(logger)) {
            finishIfNeeded("SERVICE_DISCOVERY_PERMISSION_DENIED")
        } else {
            mainHandler.postDelayed(serviceDiscoveryRetryRunnable, SERVICE_DISCOVERY_RETRY_MILLIS)
        }
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
                logger("GATT benchmark connection status=$status state=$stateLabel")
                if (finished) {
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stateDidChange("Configuring(GATT benchmark)")
                    serviceDiscoveryAttemptCount = 1
                    if (!gatt.safeDiscoverServices(logger)) {
                        finishIfNeeded("SERVICE_DISCOVERY_PERMISSION_DENIED")
                    } else {
                        mainHandler.postDelayed(
                            serviceDiscoveryRetryRunnable,
                            SERVICE_DISCOVERY_RETRY_MILLIS,
                        )
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finishIfNeeded("GATT_DISCONNECTED")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (finished) {
                    return
                }
                mainHandler.removeCallbacks(serviceDiscoveryRetryRunnable)
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("SERVICE_DISCOVERY_FAILED")
                    return
                }
                logger("GATT benchmark services discovered status=$status")
                val service = gatt.getService(ProofGattBenchmarkProtocol.SERVICE_UUID)
                if (service == null) {
                    finishIfNeeded("SERVICE_MISSING")
                    return
                }
                writeCharacteristic =
                    service.getCharacteristic(ProofGattBenchmarkProtocol.WRITE_CHARACTERISTIC_UUID)
                ackCharacteristic =
                    service.getCharacteristic(ProofGattBenchmarkProtocol.ACK_CHARACTERISTIC_UUID)
                if (writeCharacteristic == null || ackCharacteristic == null) {
                    finishIfNeeded("CHARACTERISTIC_MISSING")
                    return
                }
                logger("GATT benchmark characteristics ready write=${writeCharacteristic?.uuid} ack=${ackCharacteristic?.uuid}")
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
                if (descriptor.uuid != ProofGattBenchmarkProtocol.CCCD_UUID) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("NOTIFY_ENABLE_FAILED")
                    return
                }
                logger("GATT benchmark notifications enabled")
                stateDidChange("Running(GATT benchmark)")
                sendStartFrame(gatt)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (finished) {
                    return
                }
                if (characteristic.uuid != ProofGattBenchmarkProtocol.WRITE_CHARACTERISTIC_UUID) {
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finishIfNeeded("WRITE_FAILED")
                    return
                }
                when (pendingWritePhase) {
                    PendingWritePhase.START -> {
                        pendingWritePhase = PendingWritePhase.DATA
                        sendDataFrame(gatt)
                    }
                    PendingWritePhase.DATA -> {
                        nextChunkIndex += 1
                        if (nextChunkIndex < payloadChunks.size) {
                            sendDataFrame(gatt)
                        } else {
                            pendingWritePhase = PendingWritePhase.WAITING_ACK
                        }
                    }
                    PendingWritePhase.WAITING_ACK,
                    PendingWritePhase.NONE,
                    -> Unit
                }
            }

            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (finished) {
                    return
                }
                if (characteristic.uuid != ProofGattBenchmarkProtocol.ACK_CHARACTERISTIC_UUID) {
                    return
                }
                val ack = ProofGattBenchmarkProtocol.decodeAckFrame(characteristic.value)
                val tokenHex = transferTokenHex ?: return
                if (!benchmarkAckMatches(ack, tokenHex, payload.size)) {
                    finishIfNeeded("ACK_MISMATCH")
                    return
                }
                logger(
                    "BENCHMARK receipt send(${tokenHex.takeLast(6)}) -> Sent " +
                        "token=$tokenHex bytes=${payload.size} attempt=1"
                )
                logger("GATT benchmark completed token=$tokenHex bytes=${payload.size}")
                finished = true
                stateDidChange("Completed(GATT benchmark)")
                stopInternal(updateStateToStopped = false)
            }
        }

    fun start(): Unit {
        if (started) {
            return
        }
        started = true
        payload = appId.encodeToByteArray()
        payloadChunks = payload.chunkedBytes(MAX_DATA_CHUNK_BYTES)
        nextChunkIndex = 0
        transferTokenHex = UUID.randomUUID().toString().replace("-", "").take(16)
        logger("GATT benchmark scanning appId=$appId")
        stateDidChange("Scanning(GATT benchmark)")
        val adapter = bluetoothManager.adapter ?: error("BluetoothAdapter is unavailable")
        scanner = adapter.bluetoothLeScanner ?: error("BluetoothLeScanner is unavailable")
        val filters =
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(ProofGattBenchmarkProtocol.SERVICE_UUID))
                    .setManufacturerData(
                        ProofGattBenchmarkProtocol.MANUFACTURER_ID,
                        ProofGattBenchmarkProtocol.advertisementTag(appId),
                    )
                    .build(),
            )
        val settings =
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (!scanner.safeStartScan(filters, settings, scanCallback, logger)) {
            finishIfNeeded("SCAN_PERMISSION_DENIED")
            return
        }
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MILLIS)
    }

    fun stop(): Unit {
        stopInternal(updateStateToStopped = true)
        started = false
    }

    private fun handleScanResult(result: ScanResult) {
        if (finished) {
            return
        }
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.removeCallbacks(serviceDiscoveryRetryRunnable)
        scanner.safeStopScan(logger, scanCallback)
        logger(
            "GATT benchmark discovered device=${result.device.address} rssi=${result.rssi}"
        )
        stateDidChange("Connecting(GATT benchmark)")
        gatt = result.device.safeConnectGatt(context, false, gattCallback, logger)
        if (gatt == null) {
            finishIfNeeded("GATT_CONNECT_PERMISSION_DENIED")
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val ackCharacteristic = ackCharacteristic ?: run {
            finishIfNeeded("ACK_CHARACTERISTIC_MISSING")
            return
        }
        val cccd = ackCharacteristic.getDescriptor(ProofGattBenchmarkProtocol.CCCD_UUID)
        if (cccd == null) {
            finishIfNeeded("CCCD_MISSING")
            return
        }
        if (!gatt.safeSetCharacteristicNotification(ackCharacteristic, true, logger)) {
            finishIfNeeded("ACK_NOTIFICATION_PERMISSION_DENIED")
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!gatt.safeWriteDescriptor(cccd, logger)) {
            finishIfNeeded("ACK_DESCRIPTOR_WRITE_PERMISSION_DENIED")
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun sendStartFrame(gatt: BluetoothGatt) {
        val writeCharacteristic = writeCharacteristic ?: run {
            finishIfNeeded("WRITE_CHARACTERISTIC_MISSING")
            return
        }
        val tokenHex = transferTokenHex ?: run {
            finishIfNeeded("TOKEN_MISSING")
            return
        }
        pendingWritePhase = PendingWritePhase.START
        logger("GATT benchmark start token=${tokenHex} bytes=${payload.size} chunks=${payloadChunks.size}")
        writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeCharacteristic.value = encodeStartFrame(tokenHex, payload.size)
        val enqueued = gatt.safeWriteCharacteristic(writeCharacteristic, logger)
        if (!enqueued) {
            pendingWritePhase = PendingWritePhase.NONE
            finishIfNeeded("WRITE_ENQUEUE_FAILED")
        } else {
            dataWriteRetryCount = 0
        }
    }

    @Suppress("DEPRECATION")
    private fun sendDataFrame(gatt: BluetoothGatt) {
        val writeCharacteristic = writeCharacteristic ?: run {
            finishIfNeeded("WRITE_CHARACTERISTIC_MISSING")
            return
        }
        val tokenHex = transferTokenHex ?: run {
            finishIfNeeded("TOKEN_MISSING")
            return
        }
        pendingWritePhase = PendingWritePhase.DATA
        val chunk = payloadChunks.getOrNull(nextChunkIndex) ?: run {
            finishIfNeeded("CHUNK_MISSING")
            return
        }
        logger(
            "GATT benchmark data token=${tokenHex} chunk=${nextChunkIndex + 1}/${payloadChunks.size} bytes=${chunk.size}"
        )
        writeCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        writeCharacteristic.value = encodeDataFrame(tokenHex, chunk)
        val enqueued = gatt.safeWriteCharacteristic(writeCharacteristic, logger)
        if (!enqueued) {
            if (dataWriteRetryCount < 10) {
                dataWriteRetryCount += 1
                logger(
                    "GATT benchmark data enqueue retry token=${tokenHex} " +
                        "chunk=${nextChunkIndex + 1}/${payloadChunks.size} attempt=$dataWriteRetryCount"
                )
                mainHandler.postDelayed({ sendDataFrame(gatt) }, 100)
            } else {
                pendingWritePhase = PendingWritePhase.NONE
                finishIfNeeded("WRITE_ENQUEUE_FAILED")
            }
        } else {
            dataWriteRetryCount = 0
        }
    }

    private fun stopInternal(updateStateToStopped: Boolean) {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        mainHandler.removeCallbacks(serviceDiscoveryRetryRunnable)
        scanner.safeStopScan(logger, scanCallback)
        scanner = null
        gatt.safeClose(logger)
        gatt = null
        writeCharacteristic = null
        ackCharacteristic = null
        transferTokenHex = null
        payload = ByteArray(0)
        payloadChunks = emptyList()
        nextChunkIndex = 0
        pendingWritePhase = PendingWritePhase.NONE
        if (updateStateToStopped) {
            stateDidChange("Stopped")
        }
    }

    private fun finishIfNeeded(reason: String) {
        if (finished) {
            return
        }
        finished = true
        logger("GATT benchmark failed reason=$reason elapsedMs=${elapsedMillisSinceStart()}" )
        stateDidChange("Error(GATT benchmark)")
        stopInternal(updateStateToStopped = false)
    }

    private fun elapsedMillisSinceStart(): Long {
        return (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L
    }

    private val startNanos: Long = SystemClock.elapsedRealtimeNanos()

    private fun encodeStartFrame(tokenHex: String, totalBytes: Int): ByteArray {
        val tokenBytes = tokenHex.decodeHexTokenBytes() ?: error("Invalid benchmark token: $tokenHex")
        return ByteBuffer.allocate(1 + tokenBytes.size + Int.SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x01.toByte())
            .put(tokenBytes)
            .putInt(totalBytes)
            .array()
    }

    private fun encodeDataFrame(tokenHex: String, chunk: ByteArray): ByteArray {
        val tokenBytes = tokenHex.decodeHexTokenBytes() ?: error("Invalid benchmark token: $tokenHex")
        return ByteBuffer.allocate(1 + tokenBytes.size + chunk.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(0x02.toByte())
            .put(tokenBytes)
            .put(chunk)
            .array()
    }

    private enum class PendingWritePhase {
        NONE,
        START,
        DATA,
        WAITING_ACK,
    }
}
