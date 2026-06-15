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
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.android.meshLinkBootstrap
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
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
    private var gattBenchmarkServer: ProofGattBenchmarkServer? = null
    private var gattNotifyBenchmarkClient: ProofGattNotifyBenchmarkClient? = null
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
            if (
                currentLaunchConfig != resolvedLaunchConfig ||
                    (
                        resolvedLaunchConfig.primaryTransport == ProofBenchmarkTransport.MeshLink &&
                            meshLink == null
                        ) ||
                    (
                        resolvedLaunchConfig.primaryTransport != ProofBenchmarkTransport.MeshLink &&
                            meshLink != null
                        )
            ) {
                this.launchConfig = resolvedLaunchConfig
                gattBenchmarkServer?.stop()
                gattNotifyBenchmarkClient?.stop()
                meshLink =
                    if (resolvedLaunchConfig.primaryTransport == ProofBenchmarkTransport.MeshLink) {
                        ch.trancee.meshlink.api.meshLink(
                            config = meshLinkConfig {
                                appId = resolvedLaunchConfig.appId
                                regulatoryRegion = RegulatoryRegion.DEFAULT
                                powerMode = resolvedLaunchConfig.powerMode
                                if (resolvedLaunchConfig.disableAutoSend) {
                                    deliveryRetryDeadline = PASSIVE_RECEIPT_SEND_DEADLINE
                                }
                            },
                            bootstrap = meshLinkBootstrap(appContext!!),
                        )
                    } else {
                        null
                    }
                gattBenchmarkServer = null
                gattNotifyBenchmarkClient = null
                currentLaunchConfig = resolvedLaunchConfig
                collectorsStarted = false
                running = false
                runtimeStateText = MeshLinkState.Uninitialized.toString()
                synchronized(knownPeers) { knownPeers.clear() }
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
                    if (resolvedLaunchConfig.primaryTransport == ProofBenchmarkTransport.MeshLink) {
                        computeLocalAdvertisementKeyHash(
                            context = appContext!!,
                            appId = resolvedLaunchConfig.appId,
                        )
                    } else {
                        null
                    }
                localAdvertisementKeyHashHex = localAdvertisementKeyHash?.toLowerHexString()
                clearPersistedLogs()
                val primaryTransportLabel = resolvedLaunchConfig.primaryTransport.logLabel()
                val benchmarkTransportLabel = resolvedLaunchConfig.benchmarkTransport.logLabel()
                val keyHashSuffix =
                    localAdvertisementKeyHashHex?.let { keyHash -> " keyHash=$keyHash" } ?: ""
                appendLog(
                    "MeshLink proof app ready on ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}) appId=${resolvedLaunchConfig.appId} powerMode=${resolvedLaunchConfig.powerMode.logLabel()} primaryTransport=$primaryTransportLabel benchmarkTransport=$benchmarkTransportLabel$keyHashSuffix",
                )
            }
        }
    }

    fun start(): Job {
        val context = appContext ?: error("MeshLinkProofRuntime.initialize must be called first")
        val bluetoothReadiness = ProofBluetoothContract.inspect(context)
        if (!bluetoothReadiness.ready) {
            return scope.launch {
                runtimeStateText = "Error(${bluetoothReadiness.reason ?: "Bluetooth unavailable"})"
                appendLog("Bluetooth preflight failed; ${bluetoothReadiness.reason}")
                updatesFlow.tryEmit(Unit)
            }
        }
        when (launchConfig.primaryTransport) {
            ProofBenchmarkTransport.GattPrototype -> {
                return scope.launch {
                    val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                    if (!launchConfig.disableAutoSend) {
                        runtimeStateText = "Error(GATT benchmark)"
                        appendLog(
                            "gatt.benchmark.start() failed: Android GATT prototype currently supports passive server mode only; relaunch with meshlink.disableAutoSend=true"
                        )
                        updatesFlow.tryEmit(Unit)
                        return@launch
                    }
                    val context =
                        appContext ?: error("MeshLinkProofRuntime.initialize must be called first")
                    val bluetoothManager =
                        context.getSystemService(BluetoothManager::class.java)
                            ?: error("BluetoothManager is unavailable")
                    val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser
                    val server =
                        gattBenchmarkServer
                            ?: ProofGattBenchmarkServer(
                                context = context,
                                bluetoothManager = bluetoothManager,
                                advertiser = advertiser,
                                logger = ::appendLog,
                                appId = launchConfig.appId,
                            )
                                .also { createdServer ->
                                    gattBenchmarkServer = createdServer
                                }
                    val result = runCatching { server.start() }
                    result.onSuccess {
                        running = true
                        runtimeStateText = "Running(GATT benchmark passive)"
                        appendLog("gatt.benchmark.start() -> Started")
                        if (launchConfig.benchmarkColdStart) {
                            appendLog(
                                "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=Started mode=gattPrototype"
                            )
                        }
                    }.onFailure { error ->
                        running = false
                        runtimeStateText = "Error(GATT benchmark)"
                        appendLog(
                            "gatt.benchmark.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                    updatesFlow.tryEmit(Unit)
                }
            }
            ProofBenchmarkTransport.GattNotifyPrototype -> {
                return scope.launch {
                    val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val context =
                        appContext ?: error("MeshLinkProofRuntime.initialize must be called first")
                    val bluetoothManager =
                        context.getSystemService(BluetoothManager::class.java)
                            ?: error("BluetoothManager is unavailable")
                    val client =
                        gattNotifyBenchmarkClient
                            ?: ProofGattNotifyBenchmarkClient(
                                context = context,
                                bluetoothManager = bluetoothManager,
                                logger = ::appendLog,
                                stateDidChange = { state ->
                                    runtimeStateText = state
                                    updatesFlow.tryEmit(Unit)
                                },
                                appId = launchConfig.appId,
                            )
                                .also { createdClient ->
                                    gattNotifyBenchmarkClient = createdClient
                                }
                    val result = runCatching { client.start() }
                    result.onSuccess {
                        running = true
                        appendLog("gatt.notify.start() -> Started")
                        if (launchConfig.benchmarkColdStart) {
                            appendLog(
                                "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=Started mode=gattNotifyPrototype"
                            )
                        }
                    }.onFailure { error ->
                        running = false
                        runtimeStateText = "Error(GATT notify benchmark)"
                        appendLog(
                            "gatt.notify.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                    updatesFlow.tryEmit(Unit)
                }
            }
            ProofBenchmarkTransport.MeshLink -> {
                ensureCollectors()
                return scope.launch {
                    val startedAtNanos = SystemClock.elapsedRealtimeNanos()
                    val result = runCatching { requireMeshLink().start() }
                    result.onSuccess { startResult ->
                        appendLog("mesh.start() -> $startResult")
                        if (launchConfig.benchmarkColdStart) {
                            appendLog(
                                "BENCHMARK coldStart elapsedMs=${elapsedMillisSince(startedAtNanos)} result=$startResult",
                            )
                        }
                        applyBenchmarkPowerSnapshot()
                    }.onFailure { error ->
                        appendLog(
                            "mesh.start() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                        if (launchConfig.benchmarkColdStart) {
                            appendLog(
                                "BENCHMARK coldStart failed=${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                            )
                        }
                    }
                }
            }
        }
    }

    fun stop(): Job {
        return when (launchConfig.primaryTransport) {
            ProofBenchmarkTransport.GattPrototype -> {
                scope.launch {
                    val result = runCatching { gattBenchmarkServer?.stop() ?: Unit }
                    result.onSuccess {
                        running = false
                        runtimeStateText = MeshLinkState.Stopped.toString()
                        appendLog("gatt.benchmark.stop() -> Stopped")
                    }.onFailure { error ->
                        appendLog(
                            "gatt.benchmark.stop() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                    updatesFlow.tryEmit(Unit)
                }
            }
            ProofBenchmarkTransport.GattNotifyPrototype -> {
                scope.launch {
                    val result = runCatching { gattNotifyBenchmarkClient?.stop() ?: Unit }
                    result.onSuccess {
                        running = false
                        runtimeStateText = MeshLinkState.Stopped.toString()
                        appendLog("gatt.notify.stop() -> Stopped")
                    }.onFailure { error ->
                        appendLog(
                            "gatt.notify.stop() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                    updatesFlow.tryEmit(Unit)
                }
            }
            ProofBenchmarkTransport.MeshLink -> {
                scope.launch {
                    synchronized(passiveReceiptRetryJobs) {
                        passiveReceiptRetryJobs.values.forEach(Job::cancel)
                        passiveReceiptRetryJobs.clear()
                    }
                    val result = runCatching { requireMeshLink().stop() }
                    result.onSuccess { stopResult ->
                        appendLog("mesh.stop() -> $stopResult")
                    }.onFailure { error ->
                        appendLog(
                            "mesh.stop() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                }
            }
        }
    }

    fun sendHelloToFirstPeer(): Job {
        if (launchConfig.benchmarkTransport != ProofBenchmarkTransport.MeshLink) {
            appendLog("Send Hello is unavailable in benchmark-only transport mode")
            return Job()
        }
        val peerId = synchronized(knownPeers) { knownPeers.values.firstOrNull()?.peerId }
        if (peerId == null) {
            appendLog("No discovered peer is available yet")
            return Job()
        }
        return scope.launch {
            val result = runCatching {
                requireMeshLink().send(peerId, "hello mesh from ${Build.MODEL}".encodeToByteArray())
            }
            result.onSuccess { sendResult ->
                appendLog("mesh.send(${peerId.value.takeLast(6)}) -> $sendResult")
            }.onFailure { error ->
                appendLog(
                    "mesh.send() failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                )
            }
        }
    }

    fun appendLog(message: String): Unit {
        Log.i(TAG, message)
        val persistedLogs = synchronized(logLines) {
            logLines += message
            while (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }
            logLines.joinToString(separator = "\n") + "\n"
        }
        persistLogs(persistedLogs)
        updatesFlow.tryEmit(Unit)
    }

    private fun schedulePassiveBenchmarkReceipt(
        peerId: PeerId,
        benchmarkPayload: BenchmarkPayloadEnvelope,
    ): Unit {
        val tokenHex = benchmarkPayload.tokenHex
        val receiptPayload =
            BenchmarkReceipt(
                    tokenHex = benchmarkPayload.tokenHex,
                    totalBytes = benchmarkPayload.totalBytes,
                )
                .encode()
        synchronized(passiveReceiptRetryJobs) {
            passiveReceiptRetryJobs.remove(tokenHex)?.cancel()
            passiveReceiptRetryJobs[tokenHex] =
                scope.launch {
                    var attempt = 0
                    val deadlineAtMs = SystemClock.elapsedRealtime() + PASSIVE_RECEIPT_WINDOW_MS
                    try {
                        while (SystemClock.elapsedRealtime() < deadlineAtMs) {
                            val peerKnown =
                                synchronized(knownPeers) { knownPeers.containsKey(peerId.value) }
                            if (!peerKnown) {
                                appendBenchmarkCorrelation(
                                    role = "passive.receipt.wait",
                                    tokenHex = tokenHex,
                                    peerIdValue = peerId.value,
                                    outcome = "peerUnavailable.attempt${attempt + 1}",
                                )
                                delay(PASSIVE_RECEIPT_RETRY_DELAY_MS)
                                continue
                            }
                            attempt += 1
                            val sendResult =
                                runCatching {
                                    requireMeshLink().send(
                                        peerId = peerId,
                                        payload = receiptPayload,
                                        priority = DeliveryPriority.HIGH,
                                    )
                                }
                            sendResult
                                .onSuccess { result ->
                                    appendLog(
                                        "BENCHMARK receipt send(${peerId.value.takeLast(6)}) -> $result token=$tokenHex attempt=$attempt",
                                    )
                                    appendBenchmarkCorrelation(
                                        role = "passive.receipt.result",
                                        tokenHex = tokenHex,
                                        peerIdValue = peerId.value,
                                        outcome = "$result.attempt$attempt",
                                    )
                                    if (result is SendResult.Sent) {
                                        return@launch
                                    }
                                }
                                .onFailure { error ->
                                    appendLog(
                                        "BENCHMARK receipt failed for ${peerId.value.takeLast(6)}: ${error.javaClass.simpleName}: ${error.message.orEmpty()} token=$tokenHex attempt=$attempt",
                                    )
                                    appendBenchmarkCorrelation(
                                        role = "passive.receipt.error",
                                        tokenHex = tokenHex,
                                        peerIdValue = peerId.value,
                                        outcome = "${error.javaClass.simpleName}:${error.message.orEmpty()}.attempt$attempt",
                                    )
                                }
                            delay(PASSIVE_RECEIPT_RETRY_DELAY_MS)
                        }
                        appendLog(
                            "BENCHMARK receipt abandoned token=$tokenHex peer=${peerId.value.takeLast(6)} deadlineMs=$PASSIVE_RECEIPT_WINDOW_MS",
                        )
                        appendBenchmarkCorrelation(
                            role = "passive.receipt.expired",
                            tokenHex = tokenHex,
                            peerIdValue = peerId.value,
                            outcome = "deadlineExpired",
                        )
                    } finally {
                        synchronized(passiveReceiptRetryJobs) {
                            passiveReceiptRetryJobs.remove(tokenHex)
                        }
                    }
                }
        }
    }

    private fun appendBenchmarkCorrelation(
        role: String,
        tokenHex: String,
        peerIdValue: String,
        outcome: String,
    ): Unit {
        val knownPeersSummary =
            synchronized(knownPeers) {
                knownPeers.keys.map { peer -> peer.takeLast(6) }.sorted().joinToString(
                    prefix = "[",
                    postfix = "]",
                )
            }
        val recentPeerEvents = recentLogSummary(limit = CORRELATION_SUMMARY_LINES) { line ->
            line.startsWith("Peer ")
        }
        val recentDiagnostics = recentLogSummary(limit = CORRELATION_SUMMARY_LINES) { line ->
            line.startsWith("DIAG ")
        }
        val recentRouteTimeline = peerTimelineLogSummary(peerIdValue, CORRELATION_SUMMARY_LINES)
        val lastTransition = peerTimelineEntries(peerIdValue, limit = 1).lastOrNull() ?: "none"
        val routeState = peerRouteState(peerIdValue)
        val peerSuffix = peerIdValue.takeLast(6)
        appendLog(
            "BENCHMARK correlation role=$role token=$tokenHex peer=$peerSuffix outcome=$outcome state=$runtimeStateText knownPeers=$knownPeersSummary",
        )
        appendLog("BENCHMARK correlation token=$tokenHex recentPeers=$recentPeerEvents")
        appendLog("BENCHMARK correlation token=$tokenHex recentDiags=$recentDiagnostics")
        appendLog(
            "BENCHMARK correlation token=$tokenHex routeState=$routeState lastTransition=$lastTransition",
        )
        appendLog("BENCHMARK correlation token=$tokenHex routeTimeline=$recentRouteTimeline")
    }

    private fun peerTimelineLogSummary(peerIdValue: String, limit: Int): String {
        val entries = peerTimelineEntries(peerIdValue = peerIdValue, limit = limit)
        return entries.joinToString(prefix = "[", postfix = "]", separator = " | ")
    }

    private fun peerTimelineEntries(peerIdValue: String, limit: Int): List<String> {
        return synchronized(logLines) {
            logLines
                .filter { line -> isPeerTimelineLine(line, peerIdValue) }
                .takeLast(limit)
                .map(::summarizeLogLine)
        }
    }

    private fun isPeerTimelineLine(line: String, peerIdValue: String): Boolean {
        return (line.startsWith("Peer ") && line.contains(peerIdValue)) ||
            (line.startsWith("DIAG ") && line.contains("peerId=$peerIdValue"))
    }

    private fun peerRouteState(peerIdValue: String): String {
        val lastRouteDiagnostic =
            synchronized(logLines) {
                logLines.lastOrNull { line ->
                    line.startsWith("DIAG ") &&
                        line.contains("peerId=$peerIdValue") &&
                        line.contains("routeAvailable=")
                }
            } ?: return "unknown"
        return when {
            lastRouteDiagnostic.contains("routeAvailable=true") -> "available"
            lastRouteDiagnostic.contains("routeAvailable=false") -> "unavailable"
            else -> "unknown"
        }
    }

    private fun recentLogSummary(limit: Int, predicate: (String) -> Boolean): String {
        val selectedLines =
            synchronized(logLines) {
                logLines.filter(predicate).takeLast(limit).map(::summarizeLogLine)
            }
        return selectedLines.joinToString(prefix = "[", postfix = "]", separator = " | ")
    }

    private fun summarizeLogLine(line: String): String {
        val singleLine = line.replace('\n', ' ').trim()
        return if (singleLine.length > CORRELATION_SUMMARY_CHARS) {
            singleLine.take(CORRELATION_SUMMARY_CHARS) + "…"
        } else {
            singleLine
        }
    }

    private fun clearPersistedLogs(): Unit {
        val context = appContext ?: return
        context.deleteFile(PROOF_LOG_FILE_NAME)
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
        if (launchConfig.benchmarkTransport != ProofBenchmarkTransport.MeshLink) {
            return
        }
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
            mesh.peerEvents.collectLatest(::handlePeerEvent) }
        scope.launch { mesh.diagnosticEvents.collectLatest(::handleDiagnosticEvent) }
        scope.launch { mesh.messages.collectLatest(::handleInboundMessage) }
    }

    private fun handlePeerEvent(event: PeerEvent): Unit {
        when (event) {
            is PeerEvent.Found -> {
                synchronized(knownPeers) {
                    knownPeers[event.peerId.value] = KnownPeer.from(event.peerId)
                }
                appendLog("Peer found: ${event.peerId.value} (${event.state})")
                scheduleAutoHello(event.peerId)
            }
            is PeerEvent.Lost -> {
                synchronized(knownPeers) {
                    knownPeers.remove(event.peerId.value)
                }
                synchronized(autoSendJobs) {
                    autoSendJobs.remove(event.peerId.value)?.cancel()
                }
                appendLog("Peer lost: ${event.peerId.value}")
            }
            is PeerEvent.StateChanged -> {
                appendLog("Peer state changed: ${event.peerId.value} -> ${event.state}")
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

    private fun scheduleAutoHello(peerId: PeerId): Unit {
        synchronized(autoSendJobs) {
            if (autoSendJobs.containsKey(peerId.value)) {
                return
            }
            if (launchConfig.disableAutoSend) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because passive benchmark mode is enabled"
                )
                return
            }
            if (launchConfig.benchmarkPayloadBytes == null && !shouldInitiateHello(peerId)) {
                appendLog(
                    "auto-send skipped for ${peerId.value.takeLast(6)} because this device is not the initiator"
                )
                return
            }
            autoSendJobs[peerId.value] =
                scope.launch {
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
        if (launchConfig.benchmarkTransport != ProofBenchmarkTransport.MeshLink) {
            return
        }
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
