package io.meshlink.diagnostics

import io.meshlink.power.PowerMode

data class MeshHealthSnapshot(
    val connectedPeers: Int,
    val reachablePeers: Int,
    val bufferUtilizationPercent: Int,
    val activeTransfers: Int,
    val powerMode: PowerMode,
    val avgRouteCost: Double = 0.0,
    val relayBufferUtilization: Float = 0f,
    val ownBufferUtilization: Float = 0f,
    val relayQueueSize: Int = 0,
)
