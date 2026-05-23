package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.wire.WireFrame

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
    val inlineDiscoverySuspensionSupport =
        buildMeshEngineRuntimeDiscoverySuspensionSupport(
            environment = environment,
            suspendAction = "inline.discoverySuspend",
            resumeAction = "inline.discoveryResume",
        )
    val transferDiscoverySuspensionSupport =
        buildMeshEngineRuntimeDiscoverySuspensionSupport(
            environment = environment,
            suspendAction = "transfer.discoverySuspend",
            resumeAction = "transfer.discoveryResume",
        )
    val sendTransferTowardsDestination =
        createMeshEngineRuntimeSendTransferTowardsDestination(
            sharedState = sharedState,
            sessionAndHopTransport = sessionAndHopTransport,
        )
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit = { peerId, action ->
        environment.platformBridge.clearQueuedOutboundFrames(peerId = peerId, action = action)
    }
    val inlineSendSupport =
        buildMeshEngineRuntimeInlineSendSupport(
            inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            sessionSupport = sessionAndHopTransport.sessionSupport,
            hopTransportSupport = sessionAndHopTransport.hopTransportSupport,
            outboundPreparationSupport = outboundPreparationSupport,
            discoverySuspensionSupport = inlineDiscoverySuspensionSupport,
            ttlMillisFor = sharedState.ttlMillisFor,
            scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
            emitDiagnostic = support.emitDiagnostic,
        )
    val transferSupport =
        buildMeshEngineRuntimeTransferSupport(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            sessionAndHopTransport = sessionAndHopTransport,
            messageDeliverySupport = messageDeliverySupport,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
        )
    val largeTransferSupport =
        buildMeshEngineRuntimeLargeTransferSupport(
            outboundTransfers = sharedState.outboundTransfers,
            routingSupport = routingAndTrust.routingSupport,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            currentTopologyVersion = { sharedState.routeCoordinator.topologyVersion.value },
            outboundPreparationSupport = outboundPreparationSupport,
            discoverySuspensionSupport = transferDiscoverySuspensionSupport,
            scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
            emitDiagnostic = support.emitDiagnostic,
        )
    val outboundDeliverySupport =
        buildMeshEngineRuntimeOutboundDeliverySupport(
            deliveryRetryDeadline = environment.config.deliveryRetryDeadline,
            deliveryRetrySupport = deliveryRetrySupport,
            inlineSendSupport = inlineSendSupport,
            largeTransferSupport = largeTransferSupport,
        )
    val inboundSupport =
        buildMeshEngineRuntimeInboundSupport(
            localIdentity = environment.localIdentity,
            sessionRegistry = sharedState.sessionRegistry,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
            decryptHopPayload = sessionAndHopTransport.hopTransportSupport::decryptHopPayload,
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            forwardMessageToNextHop =
                sessionAndHopTransport.peerFlowSupport::forwardMessageToNextHop,
            deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            transferSupport = transferSupport,
        )
    return MeshEngineRuntimeTransferAndInboundPhase(
        outboundDeliverySupport = outboundDeliverySupport,
        transferSupport = transferSupport,
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
                emitInlineEncryptFailure = { peerId, cause ->
                    sessionAndHopTransport.hopTransportSupport.emitHopSessionFailed(
                        peerId = peerId,
                        stage = "delivery.send.encrypt",
                        reason = DiagnosticReason.TRUST_FAILURE,
                        metadata = mapOf("cause" to cause),
                    )
                },
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

private fun buildMeshEngineRuntimeDiscoverySuspensionSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    suspendAction: String,
    resumeAction: String,
): MeshEngineDiscoverySuspensionSupport {
    return MeshEngineDiscoverySuspensionSupport { suspended ->
        val action = if (suspended) suspendAction else resumeAction
        environment.platformBridge.setDiscoverySuspended(action = action, suspended = suspended)
    }
}

private fun buildMeshEngineRuntimeOutboundDeliverySupport(
    deliveryRetryDeadline: kotlin.time.Duration,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
    inlineSendSupport: MeshEngineInlineSendSupport,
    largeTransferSupport: MeshEngineLargeTransferSupport,
): MeshEngineOutboundDeliverySupport {
    return MeshEngineOutboundDeliverySupport(
        config = MeshEngineOutboundDeliveryConfig(deliveryRetryDeadline = deliveryRetryDeadline),
        dependencies =
            MeshEngineOutboundDeliveryDependencies(deliveryRetrySupport = deliveryRetrySupport),
        inlineSendSupport = inlineSendSupport,
        largeTransferSupport = largeTransferSupport,
    )
}

private fun buildMeshEngineRuntimeTransferSupport(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    sessionAndHopTransport: MeshEngineRuntimeSessionAndHopTransportPhase,
    messageDeliverySupport: MeshEngineMessageDeliverySupport,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
): MeshEngineTransferSupport {
    val state =
        MeshEngineTransferState(
            outboundTransfers = sharedState.outboundTransfers,
            inboundTransfers = sharedState.inboundTransfers,
            relayTransfers = sharedState.relayTransfers,
        )
    val sendEncryptedWireFrame = sessionAndHopTransport.hopTransportSupport::sendEncryptedWireFrame
    val routeMetadata = { peerId: PeerId, metadata: Map<String, String> ->
        routingAndTrust.routingSupport.peerRouteMetadata(peerId = peerId, metadata = metadata)
    }
    val inboundSupport =
        buildMeshEngineRuntimeInboundTransferSupport(
            inboundTransfers = state.inboundTransfers,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            routeMetadata = routeMetadata,
            emitDiagnostic = support.emitDiagnostic,
        )
    val relaySupport =
        buildMeshEngineRuntimeRelayTransferSupport(
            relayTransfers = state.relayTransfers,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
        )
    val abortSupport =
        buildMeshEngineRuntimeTransferAbortSupport(
            state = state,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
            routeMetadata = routeMetadata,
            emitDiagnostic = support.emitDiagnostic,
        )
    return MeshEngineTransferSupport(
        state = state,
        callbacks =
            MeshEngineTransferCallbacks(
                captureHardRunToken =
                    environment.compatibilitySurface.runtimeGate::captureHardRunToken,
                isLocalPeerId = sessionAndHopTransport.peerFlowSupport::isLocalPeerId,
            ),
        inboundSupport = inboundSupport,
        relaySupport = relaySupport,
        abortSupport = abortSupport,
        emitDiagnostic = support.emitDiagnostic,
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
