package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.android.meshLinkBootstrap
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import java.security.MessageDigest
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal object MeshLinkProofRuntime {
    private const val PROOF_LOG_FILE_NAME: String = "proof.log"
    private const val BENCHMARK_WARMUP_PAYLOAD: String = "benchmark warmup"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private val knownPeers: LinkedHashMap<String, KnownPeer> = linkedMapOf()
    private val autoSendJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val routeReadyPeers: LinkedHashSet<String> = linkedSetOf()
    private val pendingAutoSendPeers: LinkedHashSet<String> = linkedSetOf()
    private val passiveReceiptRetryJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val pendingBenchmarkReceipts: LinkedHashMap<String, CompletableDeferred<BenchmarkReceipt>> =
        linkedMapOf()
    private val logLines: ArrayDeque<String> = ArrayDeque()

    private var launchConfig: ProofLaunchConfig = ProofLaunchConfig(appId = "demo.meshlink")
    private var meshLink: MeshLink? = null
    private var currentLaunchConfig: ProofLaunchConfig? = null
    private var localAdvertisementKeyHash: ByteArray? = null
    private var localAdvertisementKeyHashHex: String? = null
    private var collectorsStarted: Boolean = false
    private var running: Boolean = false
    private var appContext: Context? = null
    private var runtimeStateText: String = MeshLinkState.Uninitialized.toString()
    private var benchmarkTokenCounter: Long = 0L

    val updates: Flow<Unit> = updatesFlow.asSharedFlow()

    val isRunning: Boolean
        get() = running

    val snapshot: ProofSnapshot
        get() {
            val peers = synchronized(knownPeers) { knownPeers.values.map { knownPeer -> knownPeer.peerId.value } }
            val logs = synchronized(logLines) { logLines.toList() }
            return ProofSnapshot(
                state = runtimeStateText,
                peers = peers,
                logs = logs,
                running = running,
            )
        }

    fun initialize(context: Context, launchConfig: ProofLaunchConfig): Unit {
        synchronized(this) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
            val resolvedLaunchConfig =
                launchConfig.copy(appId = launchConfig.appId.ifBlank { "demo.meshlink" })
            if (currentLaunchConfig != resolvedLaunchConfig || meshLink == null) {
                this.launchConfig = resolvedLaunchConfig
                meshLink =
                    ch.trancee.meshlink.api.meshLink(
                        config = meshLinkConfig {
                            appId = resolvedLaunchConfig.appId
                            regulatoryRegion = RegulatoryRegion.DEFAULT
                            powerMode = resolvedLaunchConfig.powerMode
                            if (resolvedLaunchConfig.benchmarkColdStart) {
                                deliveryRetryDeadline = PASSIVE_RECEIPT_SEND_DEADLINE
                            }
                        },
                        bootstrap = meshLinkBootstrap(appContext!!),
                    )
                currentLaunchConfig = resolvedLaunchConfig
                collectorsStarted = false
                running = false
                runtimeStateText = MeshLinkState.Uninitialized.toString()
                synchronized(knownPeers) { knownPeers.clear() }
                synchronized(routeReadyPeers) { routeReadyPeers.clear() }
                synchronized(pendingAutoSendPeers) { pendingAutoSendPeers.clear() }
                synchronized(autoSendJobs) {
                    autoSendJobs.values.forEach(Job::cancel)
                    autoSendJobs.clear()
                }
                synchronized(passiveReceiptRetryJobs) {
                    passiveReceiptRetryJobs.values.forEach(Job::cancel)
                    passiveReceiptRetryJobs.clear()
                }
                synchronized(pendingBenchmarkReceipts) { pendingBenchmarkReceipts.clear() }
                synchronized(logLines) { logLines.clear() }
                localAdvertisementKeyHash =
                    computeLocalAdvertisementKeyHash(
                        context = appContext!!,
                        appId = resolvedLaunchConfig.appId,
                    )
                localAdvertisementKeyHashHex = localAdvertisementKeyHash?.toLowerHexString()
                clearPersistedLogs()
                val keyHashSuffix =
                    localAdvertisementKeyHashHex?.let { keyHash -> " keyHash=$keyHash" } ?: ""
                appendLog(
                    "MeshLink proof app ready on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) appId=${resolvedLaunchConfig.appId} powerMode=${resolvedLaunchConfig.powerMode.logLabel()}$keyHashSuffix",
                )
            }
        }
    }

    fun start(): Job {

        val context = appContext ?: error("MeshLinkProofRuntime.initialize must be called first")
        val bluetoothReadiness = ProofBluetoothContract.inspect(context)
        if (!bluetoothReadiness.ready) {
            return scope.launch {
                runtimeStateText = bluetoothReadiness.startupState.renderStateLabel()
                appendLog(
                    "Bluetooth preflight failed; ${bluetoothReadiness.startupState.renderLogLabel()}; ${bluetoothReadiness.reason}"
                )
                updatesFlow.tryEmit(Unit)
            }
        }
        ensureCollectors()
        return scope.launch {
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val result = runCatching { requireMeshLink().start() }
            result.onSuccess { startResult ->
                appendLog("mesh.start() -> $startResult")
                if (launchConfig.benchmarkColdStart) {
                    appendLog(
                        "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=$startResult"
                    )
                }
                applyBenchmarkPowerSnapshot()
                updatesFlow.tryEmit(Unit)
            }.onFailure { error ->
                runtimeStateText = "Error(MeshLink)"
                appendLog("mesh.start() failed: ${error.message ?: error::class.java.simpleName}")
                updatesFlow.tryEmit(Unit)
            }
        }
    }

    fun stop(): Job {
        return scope.launch {
            synchronized(this@MeshLinkProofRuntime) {
                running = false
                runtimeStateText = MeshLinkState.Uninitialized.toString()
            }
            synchronized(routeReadyPeers) { routeReadyPeers.clear() }
            synchronized(pendingAutoSendPeers) { pendingAutoSendPeers.clear() }
            synchronized(autoSendJobs) {
                autoSendJobs.values.forEach(Job::cancel)
                autoSendJobs.clear()
            }
            synchronized(passiveReceiptRetryJobs) {
                passiveReceiptRetryJobs.values.forEach(Job::cancel)
                passiveReceiptRetryJobs.clear()
            }
            runCatching { meshLink?.stop() }
            updatesFlow.tryEmit(Unit)
        }
    }

    fun sendHelloToFirstPeer(): Job {
        val peerId = synchronized(knownPeers) { knownPeers.values.firstOrNull()?.peerId }
        if (peerId == null) {
            appendLog("Hello send skipped: no known peer")
            return scope.launch { updatesFlow.tryEmit(Unit) }
        }
        return scope.launch {
            val routeReady = awaitRouteReady(peerId, source = "manual-send")
            appendLog(
                "Hello send proceeding for ${peerId.value.takeLast(6)} routeReady=$routeReady"
            )
            val result = runCatching { requireMeshLink().send(peerId, buildHelloPayload()) }
            result.onSuccess { sendResult ->
                appendLog("Hello sent to ${peerId.value.takeLast(6)} -> $sendResult")
            }.onFailure { error ->
                appendLog("Hello send failed to ${peerId.value.takeLast(6)}: ${error.message.orEmpty()}")
            }
            updatesFlow.tryEmit(Unit)
        }
    }

    private fun clearPersistedLogs(): Unit {
        val context = appContext ?: return
        context.deleteFile(PROOF_LOG_FILE_NAME)
    }

    fun appendLog(message: String): Unit {
        synchronized(logLines) {
            logLines.addLast(message)
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            persistLogs(logLines.joinToString(separator = "\n"))
        }
        Log.i("MeshLinkReferenceAutomation", message)
        updatesFlow.tryEmit(Unit)
    }

    private fun appendDiagnostic(event: DiagnosticEvent): Unit {
        val metadataSuffix =
            if (event.metadata.isEmpty()) {
                ""
            } else {
                event.metadata.entries
                    .sortedBy { entry -> entry.key }
                    .joinToString(separator = " ", prefix = " ") { (key, value) -> "$key=$value" }
            }
        appendLog("DIAG ${event.code} stage=${event.stage} reason=${event.reason}$metadataSuffix")
        if (event.code == DiagnosticCode.ROUTE_DISCOVERED || event.code == DiagnosticCode.HOP_SESSION_ESTABLISHED) {
            markRouteReady(event.peerSuffix, event.code.name)
        }
    }

    private fun appendBenchmarkCorrelation(
        role: String,
        tokenHex: String,
        peerIdValue: String,
        outcome: String,
    ): Unit {
        appendLog(
            "REFERENCE_RUNTIME correlation role=$role peer=${peerIdValue.takeLast(6)} token=$tokenHex outcome=$outcome"
        )
    }

    private fun persistLogs(contents: String): Unit {
        val context = appContext ?: return
        runCatching {
            context
                .openFileOutput(PROOF_LOG_FILE_NAME, Context.MODE_PRIVATE)
                .bufferedWriter()
                .use { writer ->
                    writer.write(contents)
                }
        }
    }

    private fun ensureCollectors(): Unit {
        synchronized(this) {
            if (collectorsStarted) {
                return
            }
            collectorsStarted = true
        }
        val mesh = requireMeshLink()

        scope.launch {
            mesh.state.collectLatest { state ->
                runtimeStateText = state.toString()
                running = state == MeshLinkState.Running
                updatesFlow.tryEmit(Unit)
            }
        }
        scope.launch {
            mesh.peerEvents.collectLatest(::handlePeerEvent)
        }
        scope.launch {
            mesh.diagnosticEvents.collectLatest { event ->
                appendDiagnostic(event)
            }
        }
    }

    private fun handlePeerEvent(event: PeerEvent): Unit {
        when (event) {
            is PeerEvent.Found -> {
                synchronized(knownPeers) {
                    knownPeers[event.peerId.value] = KnownPeer.from(event.peerId, event.state)
                }
                appendLog("Peer found: ${event.peerId.value} (${event.state})")
                scheduleAutoHello(event.peerId, event.state, "found")
            }
            is PeerEvent.Lost -> {
                synchronized(knownPeers) {
                    knownPeers.remove(event.peerId.value)
                }
                synchronized(routeReadyPeers) {
                    routeReadyPeers.remove(event.peerId.value)
                }
                synchronized(pendingAutoSendPeers) {
                    pendingAutoSendPeers.remove(event.peerId.value)
                }
                synchronized(autoSendJobs) {
                    autoSendJobs.remove(event.peerId.value)?.cancel()
                }
                appendLog("Peer lost: ${event.peerId.value}")
            }
            is PeerEvent.StateChanged -> {
                synchronized(knownPeers) {
                    knownPeers[event.peerId.value] = KnownPeer.from(event.peerId, event.state)
                }
                appendLog("Peer state changed: ${event.peerId.value} -> ${event.state}")
                scheduleAutoHello(event.peerId, event.state, "state-changed")
            }
        }
        updatesFlow.tryEmit(Unit)
    }

    private fun handleDiagnosticEvent(event: DiagnosticEvent): Unit {
        val metadataSuffix =
            if (event.metadata.isEmpty()) {
                ""
            } else {
                event.metadata.entries
                    .sortedBy { (key, _) -> key }
                    .joinToString(prefix = " metadata={", postfix = "}") { (key, value) ->
                        "$key=$value"
                    }
            }
        appendLog("DIAG ${event.code} stage=${event.stage} reason=${event.reason}$metadataSuffix")
        if (event.code == DiagnosticCode.ROUTE_DISCOVERED || event.code == DiagnosticCode.HOP_SESSION_ESTABLISHED) {
            markRouteReady(event.peerSuffix, event.code.name)
        }
    }

    private suspend fun handleInboundMessage(message: InboundMessage): Unit {
        BenchmarkReceipt.decode(message.payload)?.let { receipt ->
            val receiptListener =
                synchronized(pendingBenchmarkReceipts) {
                    pendingBenchmarkReceipts.remove(receipt.tokenHex)
                }
            if (receiptListener != null) {
                appendLog(
                    "BENCHMARK receipt from ${message.originPeerId.value} token=${receipt.tokenHex} bytes=${receipt.totalBytes}",
                )
                appendBenchmarkCorrelation(
                    role = "sender.receipt.arrived",
                    tokenHex = receipt.tokenHex,
                    peerIdValue = message.originPeerId.value,
                    outcome = "received",
                )
                receiptListener.complete(receipt)
                return
            }
        }

        BenchmarkPayloadEnvelope.decode(message.payload)?.let { benchmarkPayload ->
            appendLog(
                "MSG from ${message.originPeerId.value} bytes=${message.payload.size} benchmarkToken=${benchmarkPayload.tokenHex}",
            )
            val receiptPeerId = resolveBenchmarkReceiptPeerId(message.originPeerId)
            if (receiptPeerId.value != message.originPeerId.value) {
                appendLog(
                    "BENCHMARK receipt peer remapped origin=${message.originPeerId.value.takeLast(6)} direct=${receiptPeerId.value.takeLast(6)}",
                )
            }
            rememberKnownPeer(
                peerId = receiptPeerId,
                source = "benchmarkReceiptPayload",
            )
            schedulePassiveBenchmarkReceipt(
                peerId = receiptPeerId,
                benchmarkPayload = benchmarkPayload,
            )
            return
        }

        appendLog(
            "MSG from ${message.originPeerId.value} bytes=${message.payload.size} text=${message.payload.decodeToString()}",
        )
    }

    private fun resolveBenchmarkReceiptPeerId(originPeerId: PeerId): PeerId {
        val knownPeer =
            synchronized(knownPeers) {
                val originPeerBytes = originPeerId.value.toBytes()
                knownPeers[originPeerId.value]?.peerId
                    ?: originPeerBytes?.let { originBytes ->
                        knownPeers.values.firstOrNull { knownPeer ->
                            knownPeer.peerBytes?.let(originBytes::startsWith) == true
                        }?.peerId
                    }
                    ?: knownPeers.values.singleOrNull()?.peerId
            }
        return knownPeer ?: originPeerId
    }

    private fun schedulePassiveBenchmarkReceipt(
        peerId: PeerId,
        benchmarkPayload: BenchmarkPayloadEnvelope,
    ): Unit {
        val receipt =
            BenchmarkReceipt(
                tokenHex = benchmarkPayload.tokenHex,
                totalBytes = benchmarkPayload.totalBytes,
            )
        synchronized(passiveReceiptRetryJobs) {
            passiveReceiptRetryJobs[peerId.value]?.cancel()
            passiveReceiptRetryJobs[peerId.value] =
                scope.launch {
                    val routeReady = awaitRouteReady(peerId, source = "passive-receipt")
                    if (!routeReady) {
                        appendLog(
                            "BENCHMARK receipt deferred peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} routeReady=false"
                        )
                    }
                    delay(PASSIVE_RECEIPT_RETRY_DELAY_MS)
                    val sendResult = runCatching { requireMeshLink().send(peerId, receipt.encode()) }
                    sendResult
                        .onSuccess { result ->
                            appendLog(
                                "BENCHMARK receipt sent peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} result=$result"
                            )
                            appendLog(
                                ProofDirectProofMarkers.passiveProofComplete(
                                    peer = peerId.value,
                                    tokenHex = receipt.tokenHex,
                                    totalBytes = receipt.totalBytes,
                                )
                            )
                        }
                        .onFailure { error ->
                            appendLog(
                                "BENCHMARK receipt failed peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex}: ${error.message.orEmpty()}"
                            )
                        }
                }
        }
    }

    private fun rememberKnownPeer(peerId: PeerId, source: String): Unit {
        val inserted =
            synchronized(knownPeers) {
                knownPeers.putIfAbsent(peerId.value, KnownPeer.from(peerId)) == null
            }
        if (inserted) {
            appendLog(
                "BENCHMARK known peer restored ${peerId.value.takeLast(6)} source=$source",
            )
            updatesFlow.tryEmit(Unit)
        }
    }

    private fun scheduleAutoHello(
        peerId: PeerId,
        state: PeerConnectionState,
        source: String,
    ): Unit {
        if (state != PeerConnectionState.CONNECTED) {
            appendLog(
                "auto-send deferred for ${peerId.value.takeLast(6)} source=$source state=$state"
            )
            return
        }
        synchronized(pendingAutoSendPeers) {
            pendingAutoSendPeers.add(peerId.value)
        }
        maybeStartAutoHello(peerId, source)
    }

    private fun markRouteReady(peerSuffix: String?, source: String): Unit {
        if (peerSuffix == null) {
            return
        }
        val peerId =
            synchronized(knownPeers) {
                knownPeers.values.firstOrNull { knownPeer ->
                    knownPeer.peerId.value.endsWith(peerSuffix)
                }?.peerId
            } ?: return
        synchronized(routeReadyPeers) {
            routeReadyPeers.add(peerId.value)
        }
        appendLog("auto-send route ready for ${peerId.value.takeLast(6)} source=$source")
        maybeStartAutoHello(peerId, source)
    }

    private suspend fun awaitRouteReady(peerId: PeerId, source: String): Boolean {
        if (synchronized(routeReadyPeers) { routeReadyPeers.contains(peerId.value) }) {
            return true
        }
        return withTimeoutOrNull(ROUTE_READY_WAIT_TIMEOUT_MS) {
            while (true) {
                if (synchronized(routeReadyPeers) { routeReadyPeers.contains(peerId.value) }) {
                    return@withTimeoutOrNull true
                }
                delay(ROUTE_READY_POLL_INTERVAL_MS)
            }
        }.also { result ->
            if (result != true) {
                appendLog(
                    "route ready wait timed out for ${peerId.value.takeLast(6)} source=$source"
                )
            }
        } == true
    }

    private fun maybeStartAutoHello(peerId: PeerId, source: String): Unit {
        synchronized(autoSendJobs) {
            if (autoSendJobs.containsKey(peerId.value)) {
                return
            }
            if (launchConfig.benchmarkPayloadBytes == null && !shouldInitiateHello(peerId)) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because this device is not the initiator"
                )
                return
            }
            synchronized(pendingAutoSendPeers) {
                pendingAutoSendPeers.remove(peerId.value)
            }
            autoSendJobs[peerId.value] =
                scope.launch {
                    val routeReady = awaitRouteReady(peerId, source)
                    val connected =
                        synchronized(knownPeers) {
                            knownPeers[peerId.value]?.connectionState == PeerConnectionState.CONNECTED
                        }
                    if (!connected) {
                        appendLog(
                            "auto-send deferred for ${peerId.value.takeLast(6)} source=$source connected=false routeReady=$routeReady"
                        )
                        synchronized(autoSendJobs) {
                            autoSendJobs.remove(peerId.value)
                        }
                        return@launch
                    }
                    appendLog(
                        "auto-send proceeding for ${peerId.value.takeLast(6)} source=$source routeReady=$routeReady connected=$connected"
                    )
                    if (launchConfig.benchmarkPayloadBytes != null) {
                        val warmupResult =
                            runCatching {
                                requireMeshLink().send(
                                    peerId,
                                    BENCHMARK_WARMUP_PAYLOAD.encodeToByteArray(),
                                )
                            }
                        warmupResult
                            .onSuccess { sendResult ->
                                appendLog("BENCHMARK transport warmup=$sendResult")
                            }
                            .onFailure { error ->
                                appendLog(
                                    "BENCHMARK transport warmupFailed=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                                )
                            }
                        delay(BENCHMARK_WARMUP_DELAY_MS)
                    }
                    repeat(AUTO_SEND_ATTEMPTS) { attemptIndex ->
                        delay(AUTO_SEND_RETRY_DELAY_MS)
                        val peerStillKnown =
                            synchronized(knownPeers) { knownPeers.containsKey(peerId.value) }
                        if (!peerStillKnown) {
                            return@launch
                        }
                        val benchmarkPayload = launchConfig.benchmarkPayloadBytes?.let(::buildBenchmarkPayload)
                        benchmarkPayload?.let { envelope ->
                            appendBenchmarkCorrelation(
                                role = "sender.benchmark.send",
                                tokenHex = envelope.tokenHex,
                                peerIdValue = peerId.value,
                                outcome = "attempt${attemptIndex + 1}",
                            )
                        }
                        val payload = benchmarkPayload?.bytes ?: buildHelloPayload()
                        val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                        val receiptDeferred =
                            benchmarkPayload?.let { envelope ->
                                CompletableDeferred<BenchmarkReceipt>().also { deferred ->
                                    synchronized(pendingBenchmarkReceipts) {
                                        pendingBenchmarkReceipts[envelope.tokenHex] = deferred
                                    }
                                }
                            }
                        val result = runCatching { requireMeshLink().send(peerId, payload) }
                        result
                            .onSuccess { sendResult ->
                                appendLog(
                                    "auto-send attempt ${attemptIndex + 1} -> $sendResult for ${peerId.value.takeLast(6)}"
                                )
                                var receiptConfirmed = true
                                benchmarkPayload?.let { envelope ->
                                    val receipt =
                                        if (sendResult is SendResult.Sent) {
                                            withTimeoutOrNull(BENCHMARK_RECEIPT_TIMEOUT_MS) {
                                                receiptDeferred?.await()
                                            }
                                        } else {
                                            synchronized(pendingBenchmarkReceipts) {
                                                pendingBenchmarkReceipts.remove(envelope.tokenHex)
                                            }
                                            null
                                        }
                                    if (receipt == null) {
                                        synchronized(pendingBenchmarkReceipts) {
                                            pendingBenchmarkReceipts.remove(envelope.tokenHex)
                                        }
                                    }
                                    receiptConfirmed = receipt != null
                                    val elapsedMs = elapsedMillisSince(startedAtNanos)
                                    val benchmarkResult =
                                        when {
                                            sendResult !is SendResult.Sent -> sendResult.toString()
                                            receipt != null -> sendResult.toString()
                                            else -> "ReceiptTimeout"
                                        }
                                    appendLog(
                                        "BENCHMARK transport bytes=${payload.size} elapsedMs=$elapsedMs throughputKBps=${formatThroughputKilobytesPerSecond(payload.size, elapsedMs)} result=$benchmarkResult"
                                    )
                                    appendBenchmarkCorrelation(
                                        role = "sender.benchmark.result",
                                        tokenHex = envelope.tokenHex,
                                        peerIdValue = peerId.value,
                                        outcome = benchmarkResult,
                                    )
                                }
                                if (sendResult is SendResult.Sent && receiptConfirmed) {
                                    return@launch
                                }
                            }
                            .onFailure { error ->
                                benchmarkPayload?.let { envelope ->
                                    synchronized(pendingBenchmarkReceipts) {
                                        pendingBenchmarkReceipts.remove(envelope.tokenHex)
                                    }
                                }
                                appendLog(
                                    "auto-send attempt ${attemptIndex + 1} failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                                )
                            }
                    }
                }
        }
    }

    private fun requireMeshLink(): MeshLink {
        return meshLink ?: error("MeshLink transport is not active for the current proof launch config")
    }

    private fun applyBenchmarkPowerSnapshot(): Unit {
        val level = launchConfig.benchmarkBatteryLevel ?: return
        val isCharging = launchConfig.benchmarkIsCharging ?: return
        requireMeshLink().updateBattery(BatterySnapshot(level = level, isCharging = isCharging))
        appendLog(
            "BENCHMARK power batteryLevel=$level isCharging=$isCharging powerMode=${launchConfig.powerMode.logLabel()}"
        )
    }

    private fun buildBenchmarkPayload(totalBytes: Int): BenchmarkPayloadEnvelope {
        val tokenHex = nextBenchmarkTokenHex()
        return BenchmarkPayloadEnvelope.create(totalBytes = totalBytes, tokenHex = tokenHex)
    }

    private fun buildHelloPayload(): ByteArray {
        return "hello mesh from ${Build.MODEL}".encodeToByteArray()
    }

    private fun shouldInitiateHello(peerId: PeerId): Boolean {
        val localKeyHash = localAdvertisementKeyHash ?: return false
        return localKeyHash.lexicographicallyPrecedesHexString(peerId.value)
    }

    private fun computeLocalAdvertisementKeyHash(context: Context, appId: String): ByteArray? {
        val preferences = context.getSharedPreferences("meshlink-$appId", Context.MODE_PRIVATE)
        val ed25519 =
            preferences
                .getString("identity:$appId:ed25519-public", null)
                ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) } ?: return null
        val x25519 =
            preferences
                .getString("identity:$appId:x25519-public", null)
                ?.let { encoded -> Base64.decode(encoded, Base64.NO_WRAP) } ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(ed25519 + x25519)
        return hash.copyOfRange(0, 12)
    }

    private fun nextBenchmarkTokenHex(): String {
        benchmarkTokenCounter += 1L
        val tokenValue = SystemClock.elapsedRealtimeNanos() xor benchmarkTokenCounter
        return tokenValue.toULong().toString(radix = 16).padStart(BENCHMARK_TOKEN_HEX_LENGTH, '0')
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000L
    }

    private fun formatThroughputKilobytesPerSecond(bytes: Int, elapsedMs: Long): String {
        if (elapsedMs <= 0L) {
            return "0.00"
        }
        val kibPerSecond = (bytes.toDouble() / 1024.0) / (elapsedMs.toDouble() / 1000.0)
        return String.format(Locale.US, "%.2f", kibPerSecond)
    }

    private const val AUTO_SEND_ATTEMPTS: Int = 6
    private const val AUTO_SEND_RETRY_DELAY_MS: Long = 2_000
    private const val BENCHMARK_WARMUP_DELAY_MS: Long = 500L
    private const val ROUTE_READY_POLL_INTERVAL_MS: Long = 50L
    private const val ROUTE_READY_WAIT_TIMEOUT_MS: Long = 10_000L
    private const val BENCHMARK_RECEIPT_TIMEOUT_MS: Long = 20_000L
    private const val BENCHMARK_MAGIC: String = "MLBM1000"
    private const val BENCHMARK_RECEIPT_PREFIX: String = "MLBM1_ACK:"
    private const val BENCHMARK_HEADER_BYTES: Int = 16
    private const val BENCHMARK_TOKEN_HEX_LENGTH: Int = 16
    private const val MAX_LOG_LINES: Int = 64
    private const val CORRELATION_SUMMARY_LINES: Int = 4
    private const val CORRELATION_SUMMARY_CHARS: Int = 96
    private val PASSIVE_RECEIPT_SEND_DEADLINE = 3.seconds
    private const val PASSIVE_RECEIPT_WINDOW_MS: Long = 18_000L
    private const val PASSIVE_RECEIPT_RETRY_DELAY_MS: Long = 500L
    private const val TAG = "MeshLinkProof"
}
