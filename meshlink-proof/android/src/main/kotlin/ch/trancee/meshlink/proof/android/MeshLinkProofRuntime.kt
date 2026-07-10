package ch.trancee.meshlink.proof.android

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal object MeshLinkProofRuntime {
    private const val PROOF_LOG_FILE_NAME: String = "proof.log"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updatesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 32)

    // knownPeers, routeReadyPeers, hopSessionReadyPeers, pendingAutoSendPeers and autoSendJobs
    // form one cohesive view of per-peer state; they are always guarded together by
    // [peerStateLock] so multi-collection updates (e.g. clearing state for a lost peer) are
    // atomic and readers never observe a torn snapshot across the collections.
    private val peerStateLock = Any()
    private val knownPeers: LinkedHashMap<String, KnownPeer> = linkedMapOf()
    private val autoSendJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val routeReadyPeers: LinkedHashSet<String> = linkedSetOf()
    private val hopSessionReadyPeers: LinkedHashSet<String> = linkedSetOf()
    private val pendingAutoSendPeers: LinkedHashSet<String> = linkedSetOf()
    private val passiveReceiptRetryJobs: LinkedHashMap<String, Job> = linkedMapOf()
    private val collectorJobs: LinkedHashMap<String, Job> = linkedMapOf()
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
    private var meshStartRequestedAtNanos: Long? = null
    private var meshStartCompletedAtNanos: Long? = null
    @Volatile
    private var peerDetailsVisible: Boolean = false
    private var benchmarkTokenCounter: Long = 0L

    val updates: Flow<Unit> = updatesFlow.asSharedFlow()

    val isPeerDetailsVisible: Boolean
        get() = synchronized(this) { peerDetailsVisible }

    val isRunning: Boolean
        get() = running

    val snapshot: ProofSnapshot
        get() {
            val peers = synchronized(peerStateLock) { knownPeers.values.map { knownPeer -> knownPeer.peerId.value } }
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
                            if (resolvedLaunchConfig.benchmarkPayloadBytes != null || resolvedLaunchConfig.benchmarkColdStart) {
                                deliveryRetryDeadline = BENCHMARK_SEND_DEADLINE
                            }
                        },
                        bootstrap = meshLinkBootstrap(appContext!!),
                    )
                currentLaunchConfig = resolvedLaunchConfig
                collectorsStarted = false
                running = false
                runtimeStateText = MeshLinkState.Uninitialized.toString()
                meshStartRequestedAtNanos = null
                meshStartCompletedAtNanos = null
                peerDetailsVisible = false
                synchronized(peerStateLock) {
                    knownPeers.clear()
                    routeReadyPeers.clear()
                    hopSessionReadyPeers.clear()
                    pendingAutoSendPeers.clear()
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
                val initiatorMode = if (resolvedLaunchConfig.forceInitiator) "forced" else "hash"
                appendLog(
                    "MeshLink proof app ready on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) appId=${resolvedLaunchConfig.appId} powerMode=${resolvedLaunchConfig.powerMode.logLabel()} initiatorMode=$initiatorMode benchmarkPayloadBytes=${resolvedLaunchConfig.benchmarkPayloadBytes ?: "none"} forceInitiator=${resolvedLaunchConfig.forceInitiator}$keyHashSuffix",
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
            meshStartRequestedAtNanos = startedAtNanos
            peerDetailsVisible = false
            appendLog("mesh.start() requested elapsedMs=0")
            val result = runCatching { requireMeshLink().start() }
            result.onSuccess { startResult ->
                meshStartCompletedAtNanos = SystemClock.elapsedRealtimeNanos()
                appendLog(
                    "mesh.start() -> $startResult elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                )
                if (launchConfig.benchmarkColdStart) {
                    appendLog(
                        "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=$startResult"
                    )
                }
                if (launchConfig.benchmarkPayloadBytes != null && launchConfig.forceInitiator) {
                    scope.launch {
                        appendLog("BENCHMARK fallback waiting for peer discovery")
                        var waitedMs = 0L
                        var lastDiscoverySnapshot: String? = null
                        while (waitedMs < BENCHMARK_FALLBACK_DISCOVERY_TIMEOUT_MS) {
                            val discoverySnapshot = describeDiscoveryState()
                            if (discoverySnapshot != lastDiscoverySnapshot) {
                                appendLog(
                                    "BENCHMARK fallback discovery snapshot waitedMs=$waitedMs $discoverySnapshot"
                                )
                                lastDiscoverySnapshot = discoverySnapshot
                            }
                            val peerId =
                                synchronized(peerStateLock) {
                                    knownPeers.values.firstOrNull()?.peerId
                                }
                            if (peerId != null) {
                                appendLog("BENCHMARK fallback peer resolved ${peerId.value.takeLast(6)}")
                                maybeStartAutoHello(peerId, "benchmark-fallback")
                                return@launch
                            }
                            delay(1_000L)
                            waitedMs += 1_000L
                        }
                        appendLog("BENCHMARK fallback exhausted waiting for peer discovery")
                    }
                }
                updatesFlow.tryEmit(Unit)
            }.onFailure { error ->
                runtimeStateText = "Error(MeshLink)"
                appendLog("mesh.start() failed: ${error.message ?: error::class.java.simpleName}")
                updatesFlow.tryEmit(Unit)
            }
        }
    }

    fun togglePeerDetails(): Unit {
        synchronized(this) {
            peerDetailsVisible = !peerDetailsVisible
        }
        updatesFlow.tryEmit(Unit)
    }

    fun stop(): Job {
        return scope.launch {
            appendLog(
                "runtime stop requested collectorJobs=${synchronized(collectorJobs) { collectorJobs.keys.joinToString(prefix = "[", postfix = "]") }}"
            )
            synchronized(this@MeshLinkProofRuntime) {
                running = false
                runtimeStateText = MeshLinkState.Uninitialized.toString()
                collectorsStarted = false
                peerDetailsVisible = false
            }
            synchronized(peerStateLock) {
                routeReadyPeers.clear()
                hopSessionReadyPeers.clear()
                pendingAutoSendPeers.clear()
                autoSendJobs.values.forEach(Job::cancel)
                autoSendJobs.clear()
            }
            synchronized(passiveReceiptRetryJobs) {
                passiveReceiptRetryJobs.values.forEach(Job::cancel)
                passiveReceiptRetryJobs.clear()
            }
            synchronized(collectorJobs) {
                collectorJobs.values.forEach(Job::cancel)
                collectorJobs.clear()
            }
            runCatching { meshLink?.stop() }
            updatesFlow.tryEmit(Unit)
        }
    }

    fun sendHelloToFirstPeer(): Job {
        val peerId = synchronized(peerStateLock) { knownPeers.values.firstOrNull()?.peerId }
        if (peerId == null) {
            appendLog("Hello send skipped: no known peer")
            return scope.launch { updatesFlow.tryEmit(Unit) }
        }
        return scope.launch {
            val routeReady = awaitRouteReady(
                peerId = peerId,
                source = "manual-send",
                allowAnyReadyPeer = true,
            )
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
            markRouteReady(resolveDiagnosticPeerId(event), event.code.name)
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
        val collectorsStartedAtNanos = SystemClock.elapsedRealtimeNanos()

        synchronized(collectorJobs) {
            collectorJobs["state"] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                mesh.state.collectLatest { state ->
                    runtimeStateText = state.toString()
                    running = state == MeshLinkState.Running
                    updatesFlow.tryEmit(Unit)
                }
            }
        }
        synchronized(collectorJobs) {
            collectorJobs["peer"] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val peerCollectorSubscribedAtNanos = SystemClock.elapsedRealtimeNanos()
                appendLog(
                    "PEER collector subscribed elapsedMs=${elapsedMillisSince(collectorsStartedAtNanos)}"
                )
                var firstPeerEventLogged = false
                try {
                    mesh.peerEvents
                        .onCompletion { cause: Throwable? ->
                            appendLog(
                                "PEER collector completed cause=${cause?.javaClass?.simpleName ?: "none"} elapsedMs=${elapsedMillisSince(collectorsStartedAtNanos)}"
                            )
                        }
                        .collectLatest { event ->
                            val (eventLabel, eventPeerIdValue) =
                                when (event) {
                                    is PeerEvent.Found -> "found" to event.peerId.value
                                    is PeerEvent.StateChanged -> "state-changed" to event.peerId.value
                                    is PeerEvent.Lost -> "lost" to event.peerId.value
                                }
                            appendLog(
                                "PEER collector event received type=$eventLabel peer=${eventPeerIdValue.takeLast(6)} elapsedMs=${elapsedMillisSince(collectorsStartedAtNanos)}"
                            )
                            if (!firstPeerEventLogged) {
                                firstPeerEventLogged = true
                                val meshStartRequestedAtNanos = this@MeshLinkProofRuntime.meshStartRequestedAtNanos
                                val meshStartCompletedAtNanos = this@MeshLinkProofRuntime.meshStartCompletedAtNanos
                                val meshStartRequestedDeltaMs =
                                    meshStartRequestedAtNanos?.let(::elapsedMillisSince)
                                val meshStartCompletedDeltaMs =
                                    meshStartCompletedAtNanos?.let(::elapsedMillisSince)
                                appendLog(
                                    buildString {
                                        append("PEER collector first event observed elapsedMs=")
                                        append(elapsedMillisSince(collectorsStartedAtNanos))
                                        append(" sinceSubscribeMs=")
                                        append(elapsedMillisSince(peerCollectorSubscribedAtNanos))
                                        append(" sinceMeshStartRequestedMs=")
                                        append(meshStartRequestedDeltaMs ?: "n/a")
                                        append(" sinceMeshStartCompletedMs=")
                                        append(meshStartCompletedDeltaMs ?: "n/a")
                                    }
                                )
                            }
                            handlePeerEvent(event)
                        }
                } finally {
                    appendLog(
                        "PEER collector coroutine exiting elapsedMs=${elapsedMillisSince(collectorsStartedAtNanos)}"
                    )
                }
            }
        }
        synchronized(collectorJobs) {
            collectorJobs["diagnostic"] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                mesh.diagnosticEvents.collectLatest { event ->
                    appendDiagnostic(event)
                }
            }
        }
        synchronized(collectorJobs) {
            collectorJobs["inbound"] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                appendLog("INBOUND collector subscribed")
                var firstInboundMessageLogged = false
                mesh.messages.collectLatest { message ->
                    if (!firstInboundMessageLogged) {
                        firstInboundMessageLogged = true
                        appendLog("INBOUND collector first message observed")
                    }
                    appendLog(
                        "INBOUND message origin=${message.originPeerId.value.takeLast(6)} bytes=${message.payload.size} receivedAt=${message.receivedAtEpochMillis}",
                    )
                    handleInboundMessage(message)
                }
            }
        }
    }

    private fun handlePeerEvent(event: PeerEvent): Unit {
        when (event) {
            is PeerEvent.Found -> {
                synchronized(peerStateLock) {
                    knownPeers[event.peerId.value] = KnownPeer.from(event.peerId, event.state)
                }
                appendLog(
                    "PEER discovery found peer=${event.peerId.value.takeLast(6)} state=${event.state} ${describeRouteState(event.peerId)}"
                )
                scheduleAutoHello(event.peerId, event.state, "found")
            }
            is PeerEvent.Lost -> {
                synchronized(peerStateLock) {
                    knownPeers.remove(event.peerId.value)
                    routeReadyPeers.remove(event.peerId.value)
                    hopSessionReadyPeers.remove(event.peerId.value)
                    pendingAutoSendPeers.remove(event.peerId.value)
                    autoSendJobs.remove(event.peerId.value)?.cancel()
                }
                appendLog(
                    "PEER discovery lost peer=${event.peerId.value.takeLast(6)} ${describeDiscoveryState()}"
                )
            }
            is PeerEvent.StateChanged -> {
                synchronized(peerStateLock) {
                    knownPeers[event.peerId.value] = KnownPeer.from(event.peerId, event.state)
                }
                appendLog(
                    "PEER discovery stateChanged peer=${event.peerId.value.takeLast(6)} state=${event.state} ${describeRouteState(event.peerId)}"
                )
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
        when (event.code) {
            DiagnosticCode.ROUTE_DISCOVERED,
            DiagnosticCode.HOP_SESSION_ESTABLISHED -> {
                val peerIdValue = event.metadata["peerId"]
                appendLog(
                    "route diagnostic seed ${event.code.name} peerId=${peerIdValue?.takeLast(6) ?: "none"} suffix=${event.peerSuffix?.takeLast(6) ?: "none"}",
                )
                if (peerIdValue != null) {
                    val peerId = PeerId(peerIdValue)
                    rememberKnownPeer(peerId, source = event.code.name)
                    scheduleAutoHello(peerId, PeerConnectionState.CONNECTED, event.code.name)
                }
                markRouteReady(resolveDiagnosticPeerId(event), event.code.name)
                if (event.code == DiagnosticCode.HOP_SESSION_ESTABLISHED) {
                    markHopSessionReady(resolveDiagnosticPeerId(event), event.code.name)
                }
            }
            DiagnosticCode.TRUST_ESTABLISHED -> {
                val peerIdValue = event.metadata["peerId"]
                if (peerIdValue != null) {
                    appendLog("trust state snapshot ${describeRouteState(PeerId(peerIdValue))} source=${event.code.name}")
                }
            }
            DiagnosticCode.HOP_SESSION_FAILED,
            DiagnosticCode.NO_ROUTE_AVAILABLE -> {
                val peerIdValue = event.metadata["peerId"]
                if (peerIdValue != null) {
                    appendLog("route failure state ${describeRouteState(PeerId(peerIdValue))} source=${event.code.name}")
                }
            }
            else -> Unit
        }
    }

    private suspend fun handleInboundMessage(message: InboundMessage): Unit {
        if (launchConfig.benchmarkPayloadBytes != null) {
            appendLog(
                "BENCHMARK inbound inspect origin=${message.originPeerId.value.takeLast(6)} bytes=${message.payload.size} preview=${message.payload.decodeToString().take(32)}",
            )
        }
        BenchmarkReceipt.decode(message.payload)?.let { receipt ->
            val receiptListener =
                synchronized(pendingBenchmarkReceipts) {
                    pendingBenchmarkReceipts.remove(receipt.tokenHex)
                }
            val pendingReceiptCount =
                synchronized(pendingBenchmarkReceipts) {
                    pendingBenchmarkReceipts.size
                }
            appendLog(
                "BENCHMARK receipt decode token=${receipt.tokenHex} bytes=${receipt.totalBytes} origin=${message.originPeerId.value.takeLast(6)} pendingListener=${receiptListener != null} pendingCount=$pendingReceiptCount",
            )
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
                appendLog(
                    "BENCHMARK receipt complete token=${receipt.tokenHex} remainingPending=$pendingReceiptCount",
                )
                return
            }
            appendLog(
                "BENCHMARK receipt unmatched token=${receipt.tokenHex} origin=${message.originPeerId.value.takeLast(6)} pendingCount=$pendingReceiptCount",
            )
        }

        BenchmarkPayloadEnvelope.decode(message.payload)?.let { benchmarkPayload ->
            appendLog(
                "MSG from ${message.originPeerId.value} bytes=${message.payload.size} benchmarkToken=${benchmarkPayload.tokenHex}",
            )
            appendLog(
                "BENCHMARK payload decoded origin=${message.originPeerId.value.takeLast(6)} token=${benchmarkPayload.tokenHex} totalBytes=${benchmarkPayload.totalBytes}",
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

        if (launchConfig.benchmarkPayloadBytes != null && launchConfig.forceInitiator) {
            appendLog(
                "BENCHMARK inbound fallback scheduling from ${message.originPeerId.value.takeLast(6)} source=inbound-message",
            )
            rememberKnownPeer(
                peerId = message.originPeerId,
                source = "inbound-benchmark-fallback",
            )
            markRouteReady(message.originPeerId.value, "inbound-benchmark-fallback")
            scheduleAutoHello(
                peerId = message.originPeerId,
                state = PeerConnectionState.CONNECTED,
                source = "inbound-benchmark-fallback",
            )
        }

        appendLog(
            "MSG from ${message.originPeerId.value} bytes=${message.payload.size} text=${message.payload.decodeToString()}",
        )
    }

    private fun resolveBenchmarkReceiptPeerId(originPeerId: PeerId): PeerId {
        val knownPeer =
            synchronized(peerStateLock) {
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

    private fun resolveBenchmarkTransportPeerId(targetPeerId: PeerId): PeerId {
        val sessionPeerValues =
            synchronized(peerStateLock) {
                hopSessionReadyPeers.toList()
            }
        if (sessionPeerValues.isEmpty()) {
            return targetPeerId
        }
        if (sessionPeerValues.contains(targetPeerId.value)) {
            return targetPeerId
        }
        if (sessionPeerValues.size == 1) {
            val sessionPeer = PeerId(sessionPeerValues.single())
            appendLog(
                "BENCHMARK transport peer remapped target=${targetPeerId.value.takeLast(6)} session=${sessionPeer.value.takeLast(6)}",
            )
            return sessionPeer
        }
        appendLog(
            "BENCHMARK transport peer ambiguous target=${targetPeerId.value.takeLast(6)} sessions=${sessionPeerValues.joinToString(prefix = "[", postfix = "]") { it.takeLast(6) }}",
        )
        return targetPeerId
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
        appendLog(
            "BENCHMARK receipt queue peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} bytes=${receipt.totalBytes}",
        )
        synchronized(passiveReceiptRetryJobs) {
            passiveReceiptRetryJobs[peerId.value]?.cancel()
            passiveReceiptRetryJobs[peerId.value] =
                scope.launch {
                    appendLog(
                        "BENCHMARK receipt schedule start peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} bytes=${receipt.totalBytes}"
                    )
                    val routeReady = awaitRouteReady(
                        peerId = peerId,
                        source = "passive-receipt",
                        allowAnyReadyPeer = true,
                    )
                    appendLog(
                        "BENCHMARK receipt route ready peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} routeReady=$routeReady"
                    )
                    if (!routeReady) {
                        appendLog(
                            "BENCHMARK receipt deferred peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex} routeReady=false"
                        )
                    }
                    delay(BENCHMARK_ROUTE_SETTLE_DELAY_MS)
                    delay(PASSIVE_RECEIPT_RETRY_DELAY_MS)
                    appendLog(
                        "BENCHMARK receipt send attempt peer=${peerId.value.takeLast(6)} token=${receipt.tokenHex}"
                    )
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
            synchronized(peerStateLock) {
                knownPeers.putIfAbsent(peerId.value, KnownPeer.from(peerId)) == null
            }
        appendLog(
            buildString {
                append(if (inserted) "BENCHMARK known peer restored" else "BENCHMARK known peer observed")
                append(' ')
                append(peerId.value.takeLast(6))
                append(" source=")
                append(source)
                append(' ')
                append(describeDiscoveryState())
            }
        )
        if (inserted) {
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
                "auto-send deferred for ${peerId.value.takeLast(6)} source=$source state=$state ${describeRouteState(peerId)}"
            )
            return
        }
        synchronized(peerStateLock) {
            pendingAutoSendPeers.add(peerId.value)
        }
        appendLog(
            "auto-send queued for ${peerId.value.takeLast(6)} source=$source ${describeRouteState(peerId)}"
        )
        maybeStartAutoHello(peerId, source)
    }

    private fun markRouteReady(peerIdValue: String?, source: String): Unit {
        if (peerIdValue == null) {
            return
        }
        val peerId = PeerId(peerIdValue)
        val describedPeerId =
            synchronized(peerStateLock) {
                routeReadyPeers.add(peerId.value)
                knownPeers[peerId.value]?.peerId
                    ?: knownPeers.values.firstOrNull { knownPeer ->
                        knownPeer.peerId.value.endsWith(peerId.value)
                    }?.peerId
            } ?: peerId
        appendLog(
            "auto-send route ready for ${describedPeerId.value.takeLast(6)} source=$source ${describeRouteState(describedPeerId)}"
        )
        if (source == DiagnosticCode.HOP_SESSION_ESTABLISHED.name) {
            synchronized(peerStateLock) {
                hopSessionReadyPeers.add(peerId.value)
            }
            appendLog(
                "benchmark session ready for ${describedPeerId.value.takeLast(6)} source=$source ${describeRouteState(describedPeerId)}"
            )
        }
        maybeStartAutoHello(peerId, source)
    }

    private fun markHopSessionReady(peerIdValue: String?, source: String): Unit {
        if (peerIdValue == null) {
            return
        }
        val peerId = PeerId(peerIdValue)
        val describedPeerId =
            synchronized(peerStateLock) {
                hopSessionReadyPeers.add(peerId.value)
                knownPeers[peerId.value]?.peerId
                    ?: knownPeers.values.firstOrNull { knownPeer ->
                        knownPeer.peerId.value.endsWith(peerId.value)
                    }?.peerId
            } ?: peerId
        appendLog(
            "benchmark session ready for ${describedPeerId.value.takeLast(6)} source=$source ${describeRouteState(describedPeerId)}"
        )
    }

    private suspend fun awaitRouteReady(
        peerId: PeerId,
        source: String,
        allowAnyReadyPeer: Boolean,
    ): Boolean {
        if (synchronized(peerStateLock) { routeReadyPeers.contains(peerId.value) }) {
            return true
        }
        if (allowAnyReadyPeer && synchronized(peerStateLock) { routeReadyPeers.isNotEmpty() }) {
            appendLog("route ready fallback via existing route ${describeRouteState(peerId)} source=$source")
            return true
        }
        appendLog("route ready wait start ${describeRouteState(peerId)} source=$source")
        return withTimeoutOrNull(ROUTE_READY_WAIT_TIMEOUT_MS) {
            while (true) {
                if (synchronized(peerStateLock) { routeReadyPeers.contains(peerId.value) }) {
                    return@withTimeoutOrNull true
                }
                delay(ROUTE_READY_POLL_INTERVAL_MS)
            }
        }.also { result ->
            if (result != true) {
                appendLog(
                    "route ready wait timed out ${describeRouteState(peerId)} source=$source"
                )
            }
        } == true
    }

    private suspend fun awaitHopSessionReady(
        peerId: PeerId,
        source: String,
    ): Boolean {
        if (synchronized(peerStateLock) { hopSessionReadyPeers.contains(peerId.value) }) {
            return true
        }
        if (synchronized(peerStateLock) { hopSessionReadyPeers.isNotEmpty() }) {
            appendLog("benchmark session fallback via existing session ${describeRouteState(peerId)} source=$source")
            return true
        }
        appendLog("benchmark session wait start ${describeRouteState(peerId)} source=$source")
        return withTimeoutOrNull(ROUTE_READY_WAIT_TIMEOUT_MS) {
            while (true) {
                if (synchronized(peerStateLock) { hopSessionReadyPeers.contains(peerId.value) }) {
                    return@withTimeoutOrNull true
                }
                delay(ROUTE_READY_POLL_INTERVAL_MS)
            }
        }.also { result ->
            if (result != true) {
                appendLog(
                    "benchmark session wait timed out ${describeRouteState(peerId)} source=$source"
                )
            }
        } == true
    }

    private fun resolveDiagnosticPeerId(event: DiagnosticEvent): String? {
        return event.metadata["peerId"]
            ?: event.metadata["connectedPeerId"]
            ?: event.metadata["destinationPeerId"]
            ?: event.peerSuffix
    }

    private fun describeRouteState(peerId: PeerId): String {
        val (knownPeersSummary, readyPeersSummary, sessionReadySummary, pendingPeersSummary) =
            synchronized(peerStateLock) {
                val known =
                    knownPeers.values.joinToString(prefix = "[", postfix = "]") { knownPeer ->
                        "${knownPeer.peerId.value.takeLast(6)}:${knownPeer.connectionState}"
                    }
                val ready =
                    routeReadyPeers.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                val sessionReady =
                    hopSessionReadyPeers.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                val pending =
                    pendingAutoSendPeers.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                listOf(known, ready, sessionReady, pending)
            }
        return "peer=${peerId.value.takeLast(6)} known=$knownPeersSummary ready=$readyPeersSummary session=$sessionReadySummary pending=$pendingPeersSummary"
    }

    private fun describeDiscoveryState(): String {
        val (knownPeersSummary, readyPeersSummary, pendingPeersSummary, jobsSummary) =
            synchronized(peerStateLock) {
                val known =
                    knownPeers.values.joinToString(prefix = "[", postfix = "]") { knownPeer ->
                        "${knownPeer.peerId.value.takeLast(6)}:${knownPeer.connectionState}"
                    }
                val ready =
                    routeReadyPeers.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                val pending =
                    pendingAutoSendPeers.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                val jobs =
                    autoSendJobs.keys.joinToString(prefix = "[", postfix = "]") { peer ->
                        peer.takeLast(6)
                    }
                listOf(known, ready, pending, jobs)
            }
        return "known=$knownPeersSummary ready=$readyPeersSummary pending=$pendingPeersSummary jobs=$jobsSummary"
    }

    private fun maybeStartAutoHello(peerId: PeerId, source: String): Unit {
        synchronized(peerStateLock) {
            if (autoSendJobs.containsKey(peerId.value)) {
                return
            }
            if (launchConfig.benchmarkPayloadBytes == null && !shouldInitiateHello(peerId)) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because this device is not the initiator"
                )
                return
            }
            pendingAutoSendPeers.remove(peerId.value)
            autoSendJobs[peerId.value] =
                scope.launch {
                    val routeReady =
                        if (launchConfig.benchmarkPayloadBytes != null) {
                            awaitHopSessionReady(peerId = peerId, source = source)
                        } else {
                            awaitRouteReady(
                                peerId = peerId,
                                source = source,
                                allowAnyReadyPeer = true,
                            )
                        }
                    val connected =
                        synchronized(peerStateLock) {
                            knownPeers[peerId.value]?.connectionState == PeerConnectionState.CONNECTED
                        }
                    if (!connected) {
                        appendLog(
                            "auto-send deferred for ${peerId.value.takeLast(6)} source=$source connected=false routeReady=$routeReady"
                        )
                        synchronized(peerStateLock) {
                            autoSendJobs.remove(peerId.value)
                        }
                        return@launch
                    }
                    if (launchConfig.benchmarkPayloadBytes != null && !routeReady) {
                        // Do NOT return@launch here: routeReady only reflects whether this
                        // device has passively observed a HOP_SESSION_ESTABLISHED diagnostic yet
                        // (see awaitHopSessionReady()). The real MeshLink.send() call below
                        // already knows how to establish a session on demand -- MeshEngine's
                        // ensureHopSession() waits for the peer to initiate for a bounded window,
                        // then self-initiates if they don't (see
                        // MeshEngineSessionSupport.ensureHopSession() / prewarmHopSession()'s own
                        // doc comments) -- so gating the attempt on routeReady only prevented that
                        // fallback from ever running and could deadlock a pairing indefinitely when
                        // this device is the mesh engine's designated "deferring" side and BLE
                        // discovery happened to be one-sided this run (a real, reproducible
                        // hardware failure mode, not hypothetical). Proceed to send() regardless;
                        // it will simply take longer while the fallback runs.
                        appendLog(
                            "BENCHMARK auto-send proceeding despite route not yet observed ready ${describeRouteState(peerId)} source=$source -- relying on send()'s own session-establishment fallback"
                        )
                    }
                    appendLog(
                        "auto-send proceeding for ${peerId.value.takeLast(6)} source=$source routeReady=$routeReady connected=$connected mode=${if (launchConfig.benchmarkPayloadBytes != null) "benchmark" else "hello"} payloadBytes=${launchConfig.benchmarkPayloadBytes ?: 0}"
                    )
                    val benchmarkPayload = launchConfig.benchmarkPayloadBytes?.let(::buildBenchmarkPayload)
                    if (benchmarkPayload != null) {
                        appendLog(
                            "BENCHMARK route settle delay peer=${peerId.value.takeLast(6)} token=${benchmarkPayload.tokenHex} settleDelayMs=$BENCHMARK_ROUTE_SETTLE_DELAY_MS"
                        )
                        delay(BENCHMARK_ROUTE_SETTLE_DELAY_MS)
                    }
                    if (launchConfig.benchmarkPayloadBytes != null) {
                        appendLog(
                            "BENCHMARK transport waiting for route diagnostic ${describeRouteState(peerId)} source=$source"
                        )
                    }
                    repeat(AUTO_SEND_ATTEMPTS) { attemptIndex ->
                        delay(AUTO_SEND_RETRY_DELAY_MS)
                        val peerStillKnown =
                            synchronized(peerStateLock) { knownPeers.containsKey(peerId.value) }
                        if (!peerStillKnown) {
                            return@launch
                        }
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
                                    val pendingCount =
                                        synchronized(pendingBenchmarkReceipts) {
                                            pendingBenchmarkReceipts[envelope.tokenHex] = deferred
                                            pendingBenchmarkReceipts.size
                                        }
                                    appendLog(
                                        "BENCHMARK pending receipt registered peer=${peerId.value.takeLast(6)} token=${envelope.tokenHex} pendingCount=$pendingCount"
                                    )
                                }
                            }
                        val result = runCatching { requireMeshLink().send(peerId, payload) }
                        result
                            .onSuccess { sendResult ->
                                appendLog(
                                    "auto-send attempt ${attemptIndex + 1} -> $sendResult for ${peerId.value.takeLast(6)}"
                                )
                                benchmarkPayload?.let { envelope ->
                                    val elapsedMs = elapsedMillisSince(startedAtNanos)
                                    appendLog(
                                        "BENCHMARK transport bytes=${payload.size} elapsedMs=$elapsedMs throughputKBps=${formatThroughputKilobytesPerSecond(payload.size, elapsedMs)} result=${sendResult}"
                                    )
                                    appendBenchmarkCorrelation(
                                        role = "sender.benchmark.result",
                                        tokenHex = envelope.tokenHex,
                                        peerIdValue = peerId.value,
                                        outcome = sendResult.toString(),
                                    )
                                    if (sendResult is SendResult.Sent) {
                                        val receipt =
                                            withTimeoutOrNull(BENCHMARK_RECEIPT_TIMEOUT_MS) {
                                                receiptDeferred?.await()
                                            }
                                        if (receipt == null) {
                                            synchronized(pendingBenchmarkReceipts) {
                                                pendingBenchmarkReceipts.remove(envelope.tokenHex)
                                            }
                                            appendLog(
                                                "BENCHMARK receipt timeout peer=${peerId.value.takeLast(6)} token=${envelope.tokenHex}"
                                            )
                                        } else {
                                            appendLog(
                                                "BENCHMARK receipt confirmed peer=${peerId.value.takeLast(6)} token=${envelope.tokenHex} bytes=${receipt.totalBytes}"
                                            )
                                        }
                                    } else {
                                        synchronized(pendingBenchmarkReceipts) {
                                            pendingBenchmarkReceipts.remove(envelope.tokenHex)
                                        }
                                    }
                                }
                                if (sendResult is SendResult.Sent) {
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


    private fun buildBenchmarkPayload(totalBytes: Int): BenchmarkPayloadEnvelope {
        val tokenHex = nextBenchmarkTokenHex()
        return BenchmarkPayloadEnvelope.create(totalBytes = totalBytes, tokenHex = tokenHex)
    }

    private fun buildHelloPayload(): ByteArray {
        return "hello mesh from ${Build.MODEL}".encodeToByteArray()
    }

    private fun shouldInitiateHello(peerId: PeerId): Boolean {
        if (launchConfig.benchmarkPayloadBytes == null) {
            return true
        }
        if (launchConfig.forceInitiator) {
            return true
        }
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

    private const val AUTO_SEND_ATTEMPTS: Int = 20
    private const val AUTO_SEND_RETRY_DELAY_MS: Long = 2_000
    private const val ROUTE_READY_POLL_INTERVAL_MS: Long = 50L
    private const val ROUTE_READY_WAIT_TIMEOUT_MS: Long = 45_000L
    private const val BENCHMARK_RECEIPT_TIMEOUT_MS: Long = 120_000L
    private const val BENCHMARK_FALLBACK_DISCOVERY_TIMEOUT_MS: Long = 120_000L
    private const val BENCHMARK_ROUTE_SETTLE_DELAY_MS: Long = 0L
    private val BENCHMARK_SEND_DEADLINE = 30.seconds
    private const val BENCHMARK_MAGIC: String = "MLBM1000"
    private const val BENCHMARK_RECEIPT_PREFIX: String = "MLBM1_ACK:"
    private const val BENCHMARK_HEADER_BYTES: Int = 16
    private const val BENCHMARK_TOKEN_HEX_LENGTH: Int = 16
    private const val MAX_LOG_LINES: Int = 500
    private const val CORRELATION_SUMMARY_LINES: Int = 4
    private const val CORRELATION_SUMMARY_CHARS: Int = 96
    private val PASSIVE_RECEIPT_SEND_DEADLINE = 3.seconds
    private const val PASSIVE_RECEIPT_WINDOW_MS: Long = 18_000L
    private const val PASSIVE_RECEIPT_RETRY_DELAY_MS: Long = 500L
    private const val TAG = "MeshLinkProof"
}
