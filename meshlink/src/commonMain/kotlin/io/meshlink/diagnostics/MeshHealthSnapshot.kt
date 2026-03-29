package io.meshlink.diagnostics

data class MeshHealthSnapshot(
    val connectedPeers: Int,
    val reachablePeers: Int,
    val bufferUtilizationPercent: Int,
    val activeTransfers: Int,
    val powerMode: String,
    val avgRouteCost: Double = 0.0,
    val relayQueueSize: Int = 0,
    val effectiveGossipIntervalMillis: Long = 0,
)
