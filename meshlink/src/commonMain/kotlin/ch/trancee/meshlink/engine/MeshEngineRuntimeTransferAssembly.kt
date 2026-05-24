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
            localIdentity = environment.localIdentity,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            trustSupport = routingAndTrust.trustSupport,
            mutableMessages = environment.compatibilitySurface.mutableMessages,
            emitHopSessionFailed = sessionAndHopTransport.hopTransportSupport::emitHopSessionFailed,
            emitDiagnostic = support.emitDiagnostic,
        )
    val outboundRecipientTrustSupport =
        buildMeshEngineRuntimeOutboundRecipientTrustSupport(
            localIdentity = environment.localIdentity,
            trustStore = environment.trustStore,
            routeCoordinator = sharedState.routeCoordinator,
            emitDiagnostic = support.emitDiagnostic,
        )
    val outboundDirectEnvelopeSupport =
        buildMeshEngineRuntimeOutboundDirectEnvelopeSupport(
            localIdentity = environment.localIdentity,
            recipientTrustSupport = outboundRecipientTrustSupport,
        )
    val inlineMessagePreparationSupport =
        buildMeshEngineRuntimeInlineMessagePreparationSupport(
            localIdentity = environment.localIdentity,
            directEnvelopeSupport = outboundDirectEnvelopeSupport,
            createMessageId = sharedState.sequenceGenerator::createMessageId,
            emitEncryptFailure = { peerId, cause ->
                sessionAndHopTransport.hopTransportSupport.emitHopSessionFailed(
                    peerId = peerId,
                    stage = "delivery.send.encrypt",
                    reason = DiagnosticReason.TRUST_FAILURE,
                    metadata = mapOf("cause" to cause),
                )
            },
        )
    val outboundTransferPreparationSupport =
        buildMeshEngineRuntimeOutboundTransferPreparationSupport(
            localIdentity = environment.localIdentity,
            directEnvelopeSupport = outboundDirectEnvelopeSupport,
            routingSupport = routingAndTrust.routingSupport,
            createMessageId = sharedState.sequenceGenerator::createMessageId,
            createTransferId = sharedState.sequenceGenerator::createTransferId,
            emitEncryptFailure = { peerId, cause ->
                sessionAndHopTransport.hopTransportSupport.emitHopSessionFailed(
                    peerId = peerId,
                    stage = "transfer.encrypt",
                    reason = DiagnosticReason.TRUST_FAILURE,
                    metadata = mapOf("cause" to cause),
                )
            },
            emitDiagnostic = support.emitDiagnostic,
        )
    val outboundTransferLifecycleSupport =
        buildMeshEngineRuntimeOutboundTransferLifecycleSupport(
            outboundTransfers = sharedState.outboundTransfers,
            prepareOutboundTransferSession =
                outboundTransferPreparationSupport::prepareOutboundTransferSession,
            scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
        )
    val deliveryRetrySupport =
        buildMeshEngineRuntimeDeliveryRetrySupport(
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
    val inlineDiscoverySuspensionSupport =
        buildMeshEngineRuntimeDiscoverySuspensionSupport(
            setDiscoverySuspended = environment.platformBridge::setDiscoverySuspended,
            suspendAction = "inline.discoverySuspend",
            resumeAction = "inline.discoveryResume",
        )
    val transferDiscoverySuspensionSupport =
        buildMeshEngineRuntimeDiscoverySuspensionSupport(
            setDiscoverySuspended = environment.platformBridge::setDiscoverySuspended,
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
    val inlineOutboundDeliveryAdapter =
        buildMeshEngineRuntimeInlineOutboundDeliveryAdapter(
            inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            sessionSupport = sessionAndHopTransport.sessionSupport,
            hopTransportSupport = sessionAndHopTransport.hopTransportSupport,
            inlineMessagePreparationSupport = inlineMessagePreparationSupport,
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
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        )
    val largeTransferOutboundDeliveryAdapter =
        buildMeshEngineRuntimeLargeTransferOutboundDeliveryAdapter(
            routingSupport = routingAndTrust.routingSupport,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            currentTopologyVersion = { sharedState.routeCoordinator.topologyVersion.value },
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
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
            inlineOutboundDeliveryAdapter = inlineOutboundDeliveryAdapter,
            largeTransferOutboundDeliveryAdapter = largeTransferOutboundDeliveryAdapter,
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
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        outboundDeliverySupport = outboundDeliverySupport,
        transferSupport = transferSupport,
        inboundSupport = inboundSupport,
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
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
): MeshEngineTransferSupport {
    val state =
        MeshEngineTransferState(
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
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
            routeMetadata = routeMetadata,
            emitDiagnostic = support.emitDiagnostic,
        )
    return MeshEngineTransferSupport(
        state = state,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
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
