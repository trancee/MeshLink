package io.meshlink.sample

import io.meshlink.MeshLink
import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.createCryptoProvider
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
import kotlin.uuid.ExperimentalUuidApi

/**
 * JVM console sample demonstrating the MeshLink API.
 *
 * Since JVM has no BLE hardware access, this uses a no-op transport
 * to showcase lifecycle, configuration, messaging, and diagnostics.
 */
@OptIn(ExperimentalUuidApi::class)
fun main() {
    println("╔══════════════════════════════════════╗")
    println("║        MeshLink — JVM Sample         ║")
    println("╚══════════════════════════════════════╝")
    println()

    val config = MeshLinkConfig.chatOptimized()
    println("Config preset: chatOptimized")
    println("  maxMessageSize = ${config.maxMessageSize}")
    println("  bufferCapacity = ${config.bufferCapacity}")
    println("  mtu            = ${config.mtu}")
    println("  maxHops        = ${config.maxHops}")
    println()

    val transport = DemoTransport()
    val mesh = MeshLink(transport, config, crypto = createCryptoProvider())

    runBlocking {
        // Collect events in background coroutines
        launch {
            mesh.peers.collect { event ->
                when (event) {
                    is PeerEvent.Found -> println("🔵 Peer found: ${event.peerId.toHex()}")
                    is PeerEvent.Lost  -> println("🔴 Peer lost: ${event.peerId.toHex()}")
                }
            }
        }
        launch {
            mesh.messages.collect { message ->
                println("📨 Message from ${message.senderId.toHex()}: ${message.payload.decodeToString()}")
            }
        }
        launch {
            mesh.deliveryConfirmations.collect { uuid ->
                println("✅ Delivered: $uuid")
            }
        }
        launch {
            mesh.meshHealthFlow.collect { snapshot ->
                println("💓 Health: peers=${snapshot.connectedPeers}, mode=${snapshot.powerMode}, buffer=${snapshot.bufferUtilizationPercent}%")
            }
        }

        // Start the mesh
        mesh.start()
            .onSuccess { println("🟢 Mesh started (local peer: ${transport.localPeerId.toHex()})") }
            .onFailure { println("❌ Start failed: ${it.message}") }

        // Show health snapshot
        val health = mesh.meshHealth()
        println()
        println("Mesh Health Snapshot:")
        println("  connectedPeers  = ${health.connectedPeers}")
        println("  reachablePeers  = ${health.reachablePeers}")
        println("  powerMode       = ${health.powerMode}")
        println("  activeTransfers = ${health.activeTransfers}")
        println()

        // Demonstrate sending (will fail — no real peers)
        val fakePeer = ByteArray(16) { (it + 0x10).toByte() }
        mesh.send(fakePeer, "Hello from JVM!".encodeToByteArray())
            .onSuccess { println("📤 Sent message: $it") }
            .onFailure { println("📤 Send result: ${it.message} (expected — no real peers)") }

        // Demonstrate broadcast
        mesh.broadcast("JVM broadcast ping".encodeToByteArray(), maxHops = 3u)
            .onSuccess { println("📡 Broadcast sent: $it") }
            .onFailure { println("📡 Broadcast result: ${it.message}") }

        println()
        println("Diagnostics: ${mesh.drainDiagnostics().size} buffered events")

        // Demonstrate config validation
        val violations = config.validate()
        println("Config validation: ${if (violations.isEmpty()) "✅ valid" else "⚠️ ${violations.size} violations"}")

        // Demonstrate available presets
        println()
        println("Available presets:")
        println("  chatOptimized         — text chat, small payloads")
        println("  fileTransferOptimized — images, files, large data")
        println("  powerOptimized        — IoT, wearables, low power")
        println("  sensorOptimized       — telemetry, beacons")

        // Run briefly then stop
        delay(500)
        mesh.stop()
        println()
        println("🔴 Mesh stopped")
        println()
        println("This sample demonstrates the MeshLink API on JVM.")
        println("For BLE mesh networking, use the Android or iOS targets")
        println("with a real BleTransport implementation.")
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** No-op BLE transport for JVM demonstration. */
private class DemoTransport : BleTransport {
    override val localPeerId: ByteArray = ByteArray(16) { it.toByte() }
    override suspend fun startAdvertisingAndScanning() {}
    override suspend fun stopAll() {}
    override val advertisementEvents: Flow<AdvertisementEvent> = emptyFlow()
    override val peerLostEvents: Flow<PeerLostEvent> = emptyFlow()
    override suspend fun sendToPeer(peerId: ByteArray, data: ByteArray) {}
    override val incomingData: Flow<IncomingData> = emptyFlow()
}
