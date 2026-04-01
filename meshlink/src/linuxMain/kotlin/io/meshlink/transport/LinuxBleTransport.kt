@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.meshlink.transport

import bluetooth.*
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.SOCK_RAW
import platform.posix.SOCK_SEQPACKET
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.memset
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.write
import kotlin.random.Random

/**
 * Linux BLE transport using raw HCI and L2CAP sockets.
 *
 * Communicates directly with the Linux kernel Bluetooth subsystem,
 * bypassing BlueZ/D-Bus. This provides:
 * - BLE scanning via HCI LE scan commands
 * - BLE advertising via HCI LE advertising commands
 * - GATT client via L2CAP ATT sockets
 *
 * **Requirements:**
 * - Linux kernel with Bluetooth support
 * - `CAP_NET_RAW` capability or root privileges
 * - BlueZ or equivalent must not hold exclusive HCI access
 *   (use `sudo hciconfig hci0 down && sudo hciconfig hci0 up` if needed)
 *
 * @param hciDeviceId HCI device index (default 0 = `hci0`)
 * @param scope Coroutine scope for background tasks
 */
class LinuxBleTransport(
    private val hciDeviceId: Int = 0,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) : BleTransport {

    companion object {
        private const val TAG = "LinuxBle"
        private const val PEER_TIMEOUT_MS = 10_000L
        private const val PEER_SWEEP_INTERVAL_MS = 3_000L

        // LE scan parameters: 100ms interval, 50ms window (50% duty)
        private const val SCAN_INTERVAL: UShort = 0x00A0u // 100ms in 0.625ms units
        private const val SCAN_WINDOW: UShort = 0x0050u // 50ms

        // LE advertising parameters: 100ms interval
        private const val ADV_INTERVAL_MIN: UShort = 0x00A0u
        private const val ADV_INTERVAL_MAX: UShort = 0x00A0u

        // TODO: Replace with ATT service discovery (Read By Group Type + Read By Type)
        //  to dynamically resolve the MeshLink GATT characteristic handle at connection time.
        //  Currently assumes the MeshLink write characteristic is at handle 0x0004,
        //  which only works when the peer's GATT table has this exact layout.
        private const val GATT_DATA_WRITE_HANDLE: UShort = 0x0004u
    }

    // -- Local peer ID --

    override val localPeerId: ByteArray = generatePeerId()

    override var advertisementServiceData: ByteArray = ByteArray(0)

    // -- HCI socket --

    private var hciSocket: Int = -1

    // -- Event flows --

    private val _advertisementEvents = MutableSharedFlow<AdvertisementEvent>(extraBufferCapacity = 64)
    override val advertisementEvents: Flow<AdvertisementEvent> = _advertisementEvents.asSharedFlow()

    private val _peerLostEvents = MutableSharedFlow<PeerLostEvent>(extraBufferCapacity = 64)
    override val peerLostEvents: Flow<PeerLostEvent> = _peerLostEvents.asSharedFlow()

    private val _incomingData = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    override val incomingData: Flow<IncomingData> = _incomingData.asSharedFlow()

    // -- Background jobs --

    private var scanJob: Job? = null
    private var peerSweepJob: Job? = null

    // -- Peer tracking --

    private data class TrackedPeer(
        val peerId: ByteArray,
        val bdaddr: ByteArray,
        val bdaddrType: UByte,
        var lastSeenMillis: Long,
        var advertisementPayload: ByteArray,
    )

    private val knownPeers = mutableMapOf<String, TrackedPeer>() // bdaddr hex → peer

    // -- L2CAP connections for GATT writes --

    private val gattConnections = mutableMapOf<String, Int>() // peerId hex → L2CAP fd

    // ========================
    // BleTransport interface
    // ========================

    override suspend fun startAdvertisingAndScanning() = withContext(Dispatchers.IO) {
        log("Starting BLE on hci$hciDeviceId")

        hciSocket = openHciSocket()
        if (hciSocket < 0) {
            log("Failed to open HCI socket (need CAP_NET_RAW or root)")
            return@withContext
        }

        configureHciFilter()
        startLeAdvertising()
        startLeScanning()

        scanJob = scope.launch(Dispatchers.IO) { scanLoop() }
        peerSweepJob = scope.launch { peerSweepLoop() }
    }

    override suspend fun stopAll() = withContext(Dispatchers.IO) {
        log("Stopping all BLE activity")

        scanJob?.cancel()
        scanJob = null
        peerSweepJob?.cancel()
        peerSweepJob = null

        if (hciSocket >= 0) {
            stopLeScanning()
            stopLeAdvertising()
            close(hciSocket)
            hciSocket = -1
        }

        for ((_, fd) in gattConnections) {
            close(fd)
        }
        gattConnections.clear()
        knownPeers.clear()
    }

    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) = withContext(Dispatchers.IO) {
        val peerHex = peerId.toHexString()
        val peer = knownPeers.values.firstOrNull { it.peerId.toHexString() == peerHex }
        if (peer == null) {
            log("No known peer $peerHex")
            return@withContext
        }

        val fd = gattConnections.getOrPut(peerHex) {
            connectL2cap(peer.bdaddr, peer.bdaddrType)
        }
        if (fd < 0) {
            log("L2CAP connection failed for $peerHex")
            gattConnections.remove(peerHex)
            return@withContext
        }

        // Send as ATT Write Command (no response needed)
        val attPdu = ByteArray(3 + data.size)
        attPdu[0] = ATT_OP_WRITE_CMD.toByte()
        attPdu[1] = (GATT_DATA_WRITE_HANDLE.toInt() and 0xFF).toByte()
        attPdu[2] = (GATT_DATA_WRITE_HANDLE.toInt() shr 8).toByte()
        data.copyInto(attPdu, 3)

        attPdu.usePinned { pinned ->
            val written = write(fd, pinned.addressOf(0), attPdu.size.toULong())
            if (written < 0) {
                log("ATT write failed for $peerHex")
                close(fd)
                gattConnections.remove(peerHex)
            }
        }
    }

    // ========================
    // HCI socket management
    // ========================

    private fun openHciSocket(): Int = memScoped {
        val fd = socket(AF_BLUETOOTH, SOCK_RAW, BTPROTO_HCI)
        if (fd < 0) return fd

        val addr = alloc<sockaddr_hci>()
        memset(addr.ptr, 0, sizeOf<sockaddr_hci>().toULong())
        addr.hci_family = AF_BLUETOOTH.toUShort()
        addr.hci_dev = hciDeviceId.toUShort()
        addr.hci_channel = 0u

        val result = bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_hci>().toUInt())
        if (result < 0) {
            close(fd)
            return -1
        }

        fd
    }

    private fun configureHciFilter() = memScoped {
        // Set all filter bits via raw bytes instead of struct fields —
        // cinterop maps C arrays as CArrayPointer, not Kotlin arrays.
        val filterBytes = ByteArray(sizeOf<hci_filter>().toInt())
        // type_mask at offset 0: accept HCI_EVENT_PKT (bit 4)
        filterBytes[0] = (1 shl HCI_EVENT_PKT).toByte()
        // event_mask at offset 4: accept all events
        for (i in 4..11) filterBytes[i] = 0xFF.toByte()

        filterBytes.usePinned { pinned ->
            setsockopt(
                hciSocket,
                SOL_HCI,
                HCI_FILTER,
                pinned.addressOf(0),
                filterBytes.size.toUInt(),
            )
        }
    }

    // ========================
    // LE scanning
    // ========================

    private fun startLeScanning() = memScoped {
        // Set scan parameters: active scan, 100ms interval, 50ms window
        val scanParams = alloc<hci_le_set_scan_parameters_cp>()
        scanParams.type = 0x01u // active scan
        scanParams.interval = SCAN_INTERVAL
        scanParams.window = SCAN_WINDOW
        scanParams.own_bdaddr_type = 0x00u
        scanParams.filter = 0x00u

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_SCAN_PARAMETERS.toUShort()),
            sizeOf<hci_le_set_scan_parameters_cp>().toUByte(),
            scanParams.ptr,
        )

        // Enable scanning
        val scanEnable = alloc<hci_le_set_scan_enable_cp>()
        scanEnable.enable = 0x01u
        scanEnable.filter_dup = 0x00u // don't filter duplicates

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_SCAN_ENABLE.toUShort()),
            sizeOf<hci_le_set_scan_enable_cp>().toUByte(),
            scanEnable.ptr,
        )

        log("LE scanning started")
    }

    private fun stopLeScanning() = memScoped {
        val scanEnable = alloc<hci_le_set_scan_enable_cp>()
        scanEnable.enable = 0x00u
        scanEnable.filter_dup = 0x00u

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_SCAN_ENABLE.toUShort()),
            sizeOf<hci_le_set_scan_enable_cp>().toUByte(),
            scanEnable.ptr,
        )
    }

    // ========================
    // LE advertising
    // ========================

    private fun startLeAdvertising() = memScoped {
        // Set advertising parameters
        val advParams = alloc<hci_le_set_adv_parameters_cp>()
        advParams.min_interval = ADV_INTERVAL_MIN
        advParams.max_interval = ADV_INTERVAL_MAX
        advParams.type = 0x00u // ADV_IND (connectable undirected)
        advParams.own_bdaddr_type = 0x00u
        advParams.chan_map = 0x07u // all 3 channels
        advParams.filter = 0x00u

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_ADVERTISING_PARAMETERS.toUShort()),
            sizeOf<hci_le_set_adv_parameters_cp>().toUByte(),
            advParams.ptr,
        )

        // Set advertising data: flags + MeshLink service UUID
        // Build as raw bytes — cinterop maps C arrays as CArrayPointer.
        val serviceUuidBytes = GattConstants.SERVICE_UUID
            .replace("-", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .reversed()
            .toByteArray()

        // AD structure: Flags (3 bytes) + Complete 128-bit Service UUIDs (18 bytes)
        val advBytes = ByteArray(32) // 1 byte length + 31 bytes data
        var pos = 1 // skip length byte at [0]
        advBytes[pos++] = 0x02 // AD length
        advBytes[pos++] = 0x01 // AD type: Flags
        advBytes[pos++] = 0x06 // LE General Discoverable + BR/EDR Not Supported
        advBytes[pos++] = 0x11 // AD length (17 = 1 type + 16 UUID)
        advBytes[pos++] = 0x07 // AD type: Complete List of 128-bit Service UUIDs
        for (b in serviceUuidBytes) {
            advBytes[pos++] = b
        }
        advBytes[0] = (pos - 1).toByte() // total data length

        advBytes.usePinned { pinned ->
            bt_hci_send_cmd(
                hciSocket,
                hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_ADVERTISING_DATA.toUShort()),
                sizeOf<hci_le_set_adv_data_cp>().toUByte(),
                pinned.addressOf(0),
            )
        }

        // Set scan response data with service data (AdvertisementCodec payload)
        if (advertisementServiceData.isNotEmpty()) {
            val scanRspBytes = ByteArray(32)
            var spos = 1
            // AD type 0x21 = Service Data - 128-bit UUID
            val adLen = (1 + 16 + advertisementServiceData.size).toByte()
            scanRspBytes[spos++] = adLen
            scanRspBytes[spos++] = 0x21 // type
            for (b in serviceUuidBytes) {
                scanRspBytes[spos++] = b
            }
            for (b in advertisementServiceData) {
                scanRspBytes[spos++] = b
            }
            scanRspBytes[0] = (spos - 1).toByte()

            scanRspBytes.usePinned { pinned ->
                bt_hci_send_cmd(
                    hciSocket,
                    hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_SCAN_RSP_DATA.toUShort()),
                    sizeOf<hci_le_set_scan_rsp_data_cp>().toUByte(),
                    pinned.addressOf(0),
                )
            }
        }

        // Enable advertising
        val advEnable = alloc<hci_le_set_advertise_enable_cp>()
        advEnable.enable = 0x01u

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_ADVERTISE_ENABLE.toUShort()),
            sizeOf<hci_le_set_advertise_enable_cp>().toUByte(),
            advEnable.ptr,
        )

        log("LE advertising started")
    }

    private fun stopLeAdvertising() = memScoped {
        val advEnable = alloc<hci_le_set_advertise_enable_cp>()
        advEnable.enable = 0x00u

        bt_hci_send_cmd(
            hciSocket,
            hci_opcode(OGF_LE_CTL.toUByte(), OCF_LE_SET_ADVERTISE_ENABLE.toUShort()),
            sizeOf<hci_le_set_advertise_enable_cp>().toUByte(),
            advEnable.ptr,
        )
    }

    // ========================
    // HCI event processing
    // ========================

    private fun scanLoop() {
        val buf = ByteArray(HCI_MAX_EVENT_SIZE + 1) // +1 for packet type byte
        while (scope.isActive && hciSocket >= 0) {
            val bytesRead = buf.usePinned { pinned ->
                read(hciSocket, pinned.addressOf(0), buf.size.toULong())
            }
            if (bytesRead <= 0) continue

            // buf[0] = packet type indicator (should be HCI_EVENT_PKT = 0x04)
            if (buf[0] != HCI_EVENT_PKT.toByte()) continue

            // buf[1] = event code, buf[2] = parameter length
            val eventCode = buf[1].toInt() and 0xFF
            if (eventCode != EVT_LE_META_EVENT) continue

            // buf[3] = LE sub-event code
            val subEvent = buf[3].toInt() and 0xFF
            if (subEvent != EVT_LE_ADVERTISING_REPORT) continue

            processAdvertisingReport(buf, bytesRead.toInt())
        }
    }

    private fun processAdvertisingReport(buf: ByteArray, length: Int) {
        // buf[4] = num reports
        val numReports = buf[4].toInt() and 0xFF
        var offset = 5

        for (i in 0 until numReports) {
            if (offset + 9 >= length) break

            // evtType at buf[offset] — not used, skip past it
            val bdaddrType = buf[offset + 1].toUByte()
            val bdaddr = buf.copyOfRange(offset + 2, offset + 8)
            val dataLen = buf[offset + 8].toInt() and 0xFF

            if (offset + 9 + dataLen >= length) break
            val advPayload = buf.copyOfRange(offset + 9, offset + 9 + dataLen)
            // rssi at buf[offset + 9 + dataLen] — not used

            offset += 10 + dataLen

            // Check if the advertisement contains the MeshLink service UUID
            if (!containsMeshLinkServiceUuid(advPayload)) continue

            val bdaddrHex = bdaddr.reversed().toByteArray().toHexString()
            val now = io.meshlink.util.currentTimeMillis()

            val peerId = derivePeerIdFromBdaddr(bdaddr)

            val existing = knownPeers[bdaddrHex]
            if (existing != null) {
                existing.lastSeenMillis = now
                existing.advertisementPayload = advPayload
            } else {
                knownPeers[bdaddrHex] = TrackedPeer(
                    peerId = peerId,
                    bdaddr = bdaddr,
                    bdaddrType = bdaddrType,
                    lastSeenMillis = now,
                    advertisementPayload = advPayload,
                )
            }

            _advertisementEvents.tryEmit(
                AdvertisementEvent(
                    peerId = peerId,
                    advertisementPayload = advPayload,
                ),
            )
        }
    }

    /** Check if an AD payload contains the MeshLink 128-bit service UUID. */
    private fun containsMeshLinkServiceUuid(payload: ByteArray): Boolean {
        val targetUuid = GattConstants.SERVICE_UUID
            .replace("-", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .reversed()
            .toByteArray()

        var pos = 0
        while (pos < payload.size - 1) {
            val adLen = payload[pos].toInt() and 0xFF
            if (adLen == 0 || pos + adLen >= payload.size) break
            val adType = payload[pos + 1].toInt() and 0xFF

            // 0x07 = Complete List of 128-bit Service UUIDs
            // 0x06 = Incomplete List of 128-bit Service UUIDs
            if (adType == 0x07 || adType == 0x06) {
                val uuidData = payload.copyOfRange(pos + 2, pos + 1 + adLen)
                // Check each 16-byte UUID in the list
                for (j in 0..uuidData.size - 16 step 16) {
                    if (uuidData.copyOfRange(j, j + 16).contentEquals(targetUuid)) {
                        return true
                    }
                }
            }
            pos += 1 + adLen
        }
        return false
    }

    // ========================
    // L2CAP ATT connection
    // ========================

    private fun connectL2cap(bdaddr: ByteArray, bdaddrType: UByte): Int = memScoped {
        val fd = socket(AF_BLUETOOTH, SOCK_SEQPACKET, BTPROTO_L2CAP)
        if (fd < 0) return -1

        // Bind local address
        val localAddr = alloc<sockaddr_l2>()
        memset(localAddr.ptr, 0, sizeOf<sockaddr_l2>().toULong())
        localAddr.l2_family = AF_BLUETOOTH.toUShort()
        localAddr.l2_cid = ATT_CID.toUShort()
        localAddr.l2_bdaddr_type = BDADDR_LE_PUBLIC.toUByte()

        bind(fd, localAddr.ptr.reinterpret(), sizeOf<sockaddr_l2>().toUInt())

        // Connect to remote
        val remoteAddr = alloc<sockaddr_l2>()
        memset(remoteAddr.ptr, 0, sizeOf<sockaddr_l2>().toULong())
        remoteAddr.l2_family = AF_BLUETOOTH.toUShort()
        remoteAddr.l2_cid = ATT_CID.toUShort()
        remoteAddr.l2_bdaddr_type = bdaddrType

        // Copy bdaddr bytes
        bdaddr.usePinned { pinned ->
            platform.posix.memcpy(remoteAddr.l2_bdaddr.ptr, pinned.addressOf(0), 6u)
        }

        val result = connect(fd, remoteAddr.ptr.reinterpret(), sizeOf<sockaddr_l2>().toUInt())
        if (result < 0) {
            log("L2CAP connect failed: errno=${platform.posix.errno}")
            close(fd)
            return -1
        }

        log("L2CAP connected to ${bdaddr.reversed().toByteArray().toHexString()}")
        fd
    }

    // ========================
    // Peer sweep (timeout detection)
    // ========================

    private suspend fun peerSweepLoop() {
        while (scope.isActive) {
            delay(PEER_SWEEP_INTERVAL_MS)
            val now = io.meshlink.util.currentTimeMillis()
            val timedOut = knownPeers.entries.filter { (_, peer) ->
                now - peer.lastSeenMillis > PEER_TIMEOUT_MS
            }
            for ((key, peer) in timedOut) {
                knownPeers.remove(key)
                val peerHex = peer.peerId.toHexString()
                val fd = gattConnections.remove(peerHex)
                if (fd != null) close(fd)
                _peerLostEvents.tryEmit(PeerLostEvent(peer.peerId))
            }
        }
    }

    // ========================
    // Helpers
    // ========================

    private fun generatePeerId(): ByteArray = Random.nextBytes(8)

    private fun derivePeerIdFromBdaddr(bdaddr: ByteArray): ByteArray {
        // Pad the 6-byte BD_ADDR to an 8-byte peer ID (zero-padded, reversed for readability)
        val id = ByteArray(8)
        bdaddr.reversed().toByteArray().copyInto(id, 0, 0, minOf(6, bdaddr.size))
        return id
    }

    private fun log(msg: String) {
        println("[$TAG] $msg")
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
