package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration.Companion.milliseconds

internal fun buildMeshEngineRuntimeTransferAndInboundPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    session: MeshEngineRuntimeSessionAssembly,
): MeshEngineRuntimeTransferAndInboundPhase {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val sessionAndHopTransport = session.sessionAndHopTransport
    val messageDeliverySupport =
        buildMeshEngineRuntimeMessageDeliverySupport(
            environment = environment,
            support = support,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    val outboundPreparationSupport =
        buildMeshEngineRuntimeOutboundPreparationSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    val deliveryRetrySupport =
        buildMeshEngineRuntimeDeliveryRetrySupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
        )
    val inlineSendSupport =
        buildMeshEngineRuntimeInlineSendSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            outboundPreparationSupport = outboundPreparationSupport,
            deliveryRetrySupport = deliveryRetrySupport,
        )
    val transferSupport =
        buildMeshEngineRuntimeTransferSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            messageDeliverySupport = messageDeliverySupport,
        )
    val largeTransferSupport =
        buildMeshEngineRuntimeLargeTransferSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            outboundPreparationSupport = outboundPreparationSupport,
            deliveryRetrySupport = deliveryRetrySupport,
        )
    val inboundSupport =
        buildMeshEngineRuntimeInboundSupport(
            environment = environment,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            messageDeliverySupport = messageDeliverySupport,
            transferSupport = transferSupport,
        )
    return MeshEngineRuntimeTransferAndInboundPhase(
        inlineSendSupport = inlineSendSupport,
        transferSupport = transferSupport,
        largeTransferSupport = largeTransferSupport,
        inboundSupport = inboundSupport,
    )
}

private fun buildMeshEngineRuntimeMessageDeliverySupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
): MeshEngineMessageDeliverySupport {
    return MeshEngineMessageDeliverySupport(
        localIdentity = environment.localIdentity,
        runtimeGate = environment.compatibilitySurface.runtimeGate,
        trustSupport = routingAndTrust.trustSupport,
        mutableMessages = environment.compatibilitySurface.mutableMessages,
        emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeOutboundPreparationSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
): MeshEngineOutboundPreparationSupport {
    return MeshEngineOutboundPreparationSupport(
        localIdentity = environment.localIdentity,
        trustStore = environment.trustStore,
        state =
            MeshEngineOutboundPreparationState(outboundTransfers = sharedState.outboundTransfers),
        routingContext =
            MeshEngineOutboundPreparationRoutingContext(
                routeCoordinator = sharedState.routeCoordinator,
                routingSupport = routingAndTrust.routingSupport,
            ),
        callbacks =
            MeshEngineOutboundPreparationCallbacks(
                createMessageId = sharedState.sequenceGenerator::createMessageId,
                createTransferId = sharedState.sequenceGenerator::createTransferId,
                emitTransferEncryptFailure = { peerId, cause ->
                    sessionAndHopTransport.hopTransportSupport.emitHopSessionFailed(
                        peerId = peerId,
                        stage = "transfer.encrypt",
                        reason = DiagnosticReason.TRUST_FAILURE,
                        metadata = mapOf("cause" to cause),
                    )
                },
            ),
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeDeliveryRetrySupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
): MeshEngineDeliveryRetrySupport {
    return MeshEngineDeliveryRetrySupport(
        callbacks =
            MeshEngineDeliveryRetryCallbacks(
                awaitRetry = { attempt, remainingBudget, topologyVersion, hardRunToken ->
                    sharedState.deliveryRetryScheduler.awaitRetry(
                        attempt = attempt,
                        remainingBudget = remainingBudget,
                        lastObservedTopologyVersion = topologyVersion,
                        runtimeGate = environment.compatibilitySurface.runtimeGate,
                        hardRunToken = hardRunToken,
                    )
                },
                routeMetadata = { peerId, metadata ->
                    routingAndTrust.routingSupport.peerRouteMetadata(
                        peerId = peerId,
                        metadata = metadata,
                    )
                },
                emitDiagnostic = support.emitDiagnostic,
            )
    )
}

private fun buildMeshEngineRuntimeInlineSendSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
): MeshEngineInlineSendSupport {
    return MeshEngineInlineSendSupport(
        localIdentity = environment.localIdentity,
        config =
            MeshEngineInlineConfig(
                deliveryRetryDeadline = environment.config.deliveryRetryDeadline,
                inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            ),
        routingContext =
            MeshEngineInlineRoutingContext(
                routeCoordinator = sharedState.routeCoordinator,
                routingSupport = routingAndTrust.routingSupport,
            ),
        dependencies =
            MeshEngineInlineDependencies(
                deliveryRetrySupport = deliveryRetrySupport,
                ensureHopSession = { peerId, hardRunToken ->
                    sessionAndHopTransport.sessionSupport.ensureHopSession(peerId, hardRunToken)
                },
                sendEncryptedDirectWireFrame =
                    sessionAndHopTransport.hopTransportSupport::sendEncryptedDirectWireFrame,
                resolveRecipientTrust = outboundPreparationSupport::resolveRecipientTrust,
                scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
                setDiscoverySuspended = { suspended ->
                    val action =
                        if (suspended) {
                            "inline.discoverySuspend"
                        } else {
                            "inline.discoveryResume"
                        }
                    environment.platformBridge.setDiscoverySuspended(
                        action = action,
                        suspended = suspended,
                    )
                },
                emitHopSessionFailed =
                    sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
            ),
        callbacks =
            MeshEngineInlineCallbacks(
                emitDiagnostic = support.emitDiagnostic,
                createMessageId = sharedState.sequenceGenerator::createMessageId,
                ttlMillisFor = sharedState.ttlMillisFor,
            ),
    )
}

private fun buildMeshEngineRuntimeTransferSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    messageDeliverySupport: MeshEngineMessageDeliverySupport,
): MeshEngineTransferSupport {
    val state =
        MeshEngineTransferState(
            outboundTransfers = sharedState.outboundTransfers,
            inboundTransfers = sharedState.inboundTransfers,
            relayTransfers = sharedState.relayTransfers,
        )
    val sendTransferTowardsDestination =
        createMeshEngineRuntimeSendTransferTowardsDestination(
            sharedState = sharedState,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    val relaySupport =
        buildMeshEngineRuntimeRelayTransferSupport(
            state = state,
            sessionAndHopTransport = sessionAndHopTransport,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
        )
    val abortSupport =
        buildMeshEngineRuntimeTransferAbortSupport(
            environment = environment,
            support = support,
            state = state,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
        )
    return MeshEngineTransferSupport(
        state = state,
        routingSupport = routingAndTrust.routingSupport,
        callbacks =
            MeshEngineTransferCallbacks(
                captureHardRunToken =
                    environment.compatibilitySurface.runtimeGate::captureHardRunToken,
                isLocalPeerId = sessionAndHopTransport.peerFlowSupport::isLocalPeerId,
                sendEncryptedWireFrame =
                    sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame,
                deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            ),
        relaySupport = relaySupport,
        abortSupport = abortSupport,
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeRelayTransferSupport(
    state: MeshEngineTransferState,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): MeshEngineRelayTransferSupport {
    return MeshEngineRelayTransferSupport(
        relayTransfers = state.relayTransfers,
        callbacks =
            MeshEngineRelayTransferCallbacks(
                sendEncryptedWireFrame =
                    sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
            ),
    )
}

private fun buildMeshEngineRuntimeTransferAbortSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    state: MeshEngineTransferState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): MeshEngineTransferAbortSupport {
    return MeshEngineTransferAbortSupport(
        state = state,
        callbacks =
            MeshEngineTransferAbortCallbacks(
                sendEncryptedWireFrame =
                    sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
                clearQueuedOutboundFrames = { peerId, action ->
                    environment.platformBridge.clearQueuedOutboundFrames(
                        peerId = peerId,
                        action = action,
                    )
                },
                routeMetadata = { peerId, metadata ->
                    routingAndTrust.routingSupport.peerRouteMetadata(
                        peerId = peerId,
                        metadata = metadata,
                    )
                },
            ),
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeLargeTransferSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    outboundPreparationSupport: MeshEngineOutboundPreparationSupport,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
): MeshEngineLargeTransferSupport {
    return MeshEngineLargeTransferSupport(
        config =
            MeshEngineLargeTransferConfig(
                deliveryRetryDeadline = environment.config.deliveryRetryDeadline,
                ackSettlementTimeout = TRANSFER_ACK_SETTLEMENT_TIMEOUT,
                ackIdleWindow = TRANSFER_ACK_IDLE_WINDOW,
            ),
        state = MeshEngineLargeTransferState(outboundTransfers = sharedState.outboundTransfers),
        routingSupport = routingAndTrust.routingSupport,
        dependencies =
            MeshEngineLargeTransferDependencies(
                runtimeGate = environment.compatibilitySurface.runtimeGate,
                currentTopologyVersion = { sharedState.routeCoordinator.topologyVersion.value },
                deliveryRetrySupport = deliveryRetrySupport,
                prepareOutboundTransferSession =
                    outboundPreparationSupport::prepareOutboundTransferSession,
                scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
                sendTransferTowardsDestination =
                    createMeshEngineRuntimeSendTransferTowardsDestination(
                        sharedState = sharedState,
                        sessionAndHopTransport = sessionAndHopTransport,
                    ),
                setDiscoverySuspended = { suspended ->
                    val action =
                        if (suspended) {
                            "transfer.discoverySuspend"
                        } else {
                            "transfer.discoveryResume"
                        }
                    environment.platformBridge.setDiscoverySuspended(
                        action = action,
                        suspended = suspended,
                    )
                },
                clearQueuedOutboundFrames = { peerId, action ->
                    environment.platformBridge.clearQueuedOutboundFrames(
                        peerId = peerId,
                        action = action,
                    )
                },
            ),
        emitDiagnostic = support.emitDiagnostic,
    )
}

private fun buildMeshEngineRuntimeInboundSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    messageDeliverySupport: MeshEngineMessageDeliverySupport,
    transferSupport: MeshEngineTransferSupport,
): MeshEngineInboundSupport {
    return MeshEngineInboundSupport(
        localIdentity = environment.localIdentity,
        sessionRegistry = sharedState.sessionRegistry,
        routingContext =
            MeshEngineInboundRoutingContext(
                routeCoordinator = sharedState.routeCoordinator,
                routingSupport = routingAndTrust.routingSupport,
            ),
        transport =
            MeshEngineInboundTransport(
                emitHopSessionFailed =
                    sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
                decryptHopPayload = sessionAndHopTransport.hopTransportSupport::decryptHopPayload,
            ),
        messageCallbacks =
            MeshEngineInboundMessageCallbacks(
                captureHardRunToken =
                    environment.compatibilitySurface.runtimeGate::captureHardRunToken,
                forwardMessageToNextHop =
                    sessionAndHopTransport.peerFlowSupport::forwardMessageToNextHop,
                deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            ),
        transferCallbacks =
            MeshEngineInboundTransferCallbacks(
                handleTransferStart = transferSupport::handleTransferStart,
                handleTransferChunk = transferSupport::handleTransferChunk,
                handleTransferAck = transferSupport::handleTransferAck,
                handleTransferComplete = transferSupport::handleTransferComplete,
                handleTransferAbort = transferSupport::handleTransferAbort,
            ),
    )
}

private fun createMeshEngineRuntimeSendTransferTowardsDestination(
    sharedState: MeshEngineRuntimeSharedState,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
): suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean {
    return { peerId, frame, action, hardRunToken ->
        if (hardRunToken != null) {
            sessionAndHopTransport.peerFlowSupport.sendTransferTowardsDestination(
                destinationPeerId = peerId,
                frame = frame,
                action = action,
                hardRunToken = hardRunToken,
            )
        } else {
            val nextHopPeerId = sharedState.routeCoordinator.nextHopFor(peerId) ?: peerId
            sessionAndHopTransport.hopTransportSupport.sendEncryptedWireFrame(
                peerId = nextHopPeerId,
                frame = frame,
                action = action,
            )
        }
    }
}

internal fun buildMeshEngineRuntimeScheduleRetryDiagnostic(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    routingSupport: MeshEngineRoutingSupport,
): (PeerId, DeliveryPriority) -> Unit {
    return { peerId, priority ->
        support.emitDiagnostic(
            DiagnosticCode.NO_ROUTE_AVAILABLE,
            DiagnosticSeverity.WARN,
            "delivery.noRoute",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.DELIVERY_FAILURE,
            routingSupport.peerRouteMetadata(
                peerId,
                metadata =
                    mapOf(
                        "priority" to priority.name,
                        "retryDeadlineMs" to
                            environment.config.deliveryRetryDeadline.inWholeMilliseconds.toString(),
                        "retryBackoffBaseMs" to INITIAL_BACKOFF.inWholeMilliseconds.toString(),
                    ),
            ),
        )
    }
}

private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
private val TRANSFER_ACK_SETTLEMENT_TIMEOUT = 1_500.milliseconds
private val TRANSFER_ACK_IDLE_WINDOW = 100.milliseconds
private val INITIAL_BACKOFF = 250.milliseconds
