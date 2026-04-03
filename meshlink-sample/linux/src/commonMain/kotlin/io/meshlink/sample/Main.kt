package io.meshlink.sample

import io.meshlink.MeshLink
import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.model.PeerEvent
import io.meshlink.transport.AdvertisementEvent
import io.meshlink.transport.BleTransport
import io.meshlink.transport.IncomingData
import io.meshlink.transport.PeerLostEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Linux console sample demonstrating the MeshLink API.
 *
 * Targets: linuxX64 (x86_64 servers/desktops), linuxArm64 (Raspberry Pi, ARM servers).
 *
 * Since Linux has no built-in BLE stack accessible from Kotlin/Native, this
 * uses a no-op transport. A real implementation could bridge to BlueZ via
 * D-Bus interop.
 */
fun main() {
    println("╔══════════════════════════════════════╗")
    println("║       MeshLink — Linux Sample        ║")
    println("╚══════════════════════════════════════╝")
    println()

    val config = MeshLinkConfig.smallPayloadLowLatency { diagnosticsEnabled = true }
    println("Config preset: smallPayloadLowLatency")
    println("  maxMessageSize = ${config.maxMessageSize}")
    println("  bufferCapacity = ${config.bufferCapacity}")
    println("  mtu            = ${config.mtu}")
    println("  maxHops        = ${config.maxHops}")
    println("  broadcastTtl   = ${config.broadcastTtl}")
    println("  diagnostics    = ${config.diagnosticsEnabled}")
    println()

    val transport = DemoTransport()
    val mesh = MeshLink(transport, config, crypto = CryptoProvider())

    runBlocking {
        launch {
            mesh.peers.collect { event ->
                when (event) {
                    is PeerEvent.Found -> println("[PEER] Found: ${event.peerId.toHex()}")
                    is PeerEvent.Lost -> println("[PEER] Lost: ${event.peerId.toHex()}")
                }
            }
        }
        launch {
            mesh.messages.collect { message ->
                println("[MSG]  From ${message.senderId.toHex()}: ${message.payload.decodeToString()}")
            }
        }
        launch {
            mesh.deliveryConfirmations.collect { uuid ->
                println("[ACK]  Delivered: $uuid")
            }
        }
        launch {
            mesh.meshHealthFlow.collect { snapshot ->
                println("[HEALTH] peers=${snapshot.connectedPeers}, mode=${snapshot.powerMode.name}, buffer=${snapshot.bufferUtilizationPercent}%")
            }
        }

        mesh.start()
            .onSuccess { println("[OK]   Mesh started (local peer: ${transport.localPeerId.toHex()})") }
            .onFailure { println("[ERR]  Start failed: ${it.message}") }

        val health = mesh.meshHealth()
        println()
        println("Mesh Health Snapshot:")
        println("  connectedPeers  = ${health.connectedPeers}")
        println("  reachablePeers  = ${health.reachablePeers}")
        println("  powerMode       = ${health.powerMode.name}")
        println("  activeTransfers = ${health.activeTransfers}")
        println()

        val fakePeer = ByteArray(12) { (it + 0x10).toByte() }
        mesh.send(fakePeer, "Hello from Linux!".encodeToByteArray())
            .onSuccess { println("[SEND] Sent message: $it") }
            .onFailure { println("[SEND] Result: ${it.message} (expected - no real peers)") }

        mesh.broadcast("Linux broadcast ping".encodeToByteArray(), maxHops = config.broadcastTtl)
            .onSuccess { println("[BCAST] Broadcast sent: $it") }
            .onFailure { println("[BCAST] Result: ${it.message}") }

        println()
        println("Diagnostics: ${mesh.drainDiagnostics().size} buffered events")

        val violations = config.validate()
        println("Config validation: ${if (violations.isEmpty()) "OK" else "${violations.size} violations"}")

        println()
        println("Available presets: smallPayloadLowLatency, largePayloadHighThroughput, minimalResourceUsage, minimalOverhead")

        delay(500)
        mesh.stop()
        println()
        println("[OK]   Mesh stopped")
        println()
        println("This sample demonstrates the MeshLink API on Linux.")
        println("For BLE support, implement BleTransport using BlueZ D-Bus bindings.")
    }
}

private fun ByteArray.toHex(): String = joinToString("") {
    val v = it.toInt() and 0xFF
    val hi = "0123456789abcdef"[v shr 4]
    val lo = "0123456789abcdef"[v and 0x0F]
    "$hi$lo"
}

/** No-op BLE transport for demonstration without root privileges. */
private class DemoTransport : BleTransport {
    override val localPeerId: ByteArray = ByteArray(12) { it.toByte() }
    override var advertisementServiceData: ByteArray = ByteArray(0)
    override suspend fun startAdvertisingAndScanning() {}
    override suspend fun stopAll() {}
    override val advertisementEvents: Flow<AdvertisementEvent> = emptyFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = emptyFlow()
    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {}
    override val incomingData: Flow<IncomingData> = emptyFlow()
}

// To use real BLE hardware, replace DemoTransport with LinuxBleTransport:
//
//   val transport = LinuxBleTransport(hciDeviceId = 0)
//
// Requires: Linux with Bluetooth, CAP_NET_RAW or root.
// See LinuxBleTransport documentation for details.
