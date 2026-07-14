package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.engine.lifecycle.buildMeshEngineRuntimeDiscoverySuspensionSupport
import ch.trancee.meshlink.engine.routing.buildMeshEngineRuntimeInboundSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundDeliveryMode
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundDeliverySupport
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundTransferLifecycleSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeDeliveryRetrySupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeInlineMessagePreparationSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeInlineOutboundDeliveryAdapter
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeLargeTransferOutboundDeliveryAdapter
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeMessageDeliverySupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeOutboundDeliverySupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeOutboundDirectEnvelopeSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeOutboundRecipientTrustSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeOutboundTransferLifecycleSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeOutboundTransferPreparationSupport
import ch.trancee.meshlink.engine.transfer.buildMeshEngineRuntimeTransferSupport
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineRuntimeTransferAndInboundPhase(
    val sendPayload:
        suspend (
            MeshEngineOutboundDeliveryMode,
            PeerId,
            ByteArray,
            DeliveryPriority,
            MeshEngineHardRunToken,
        ) -> SendResult,
    val handleEncryptedDataFrame: suspend (PeerId, ByteArray) -> Unit,
    val abortLocalTransfers: suspend (TransferAbortReasonCode) -> Unit,
    val clearOutboundTransfers: suspend () -> Unit,
)

internal fun buildMeshEngineRuntimeTransferAndInboundPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    foundation: MeshEngineRuntimeFoundationAssembly,
    session: MeshEngineRuntimeSessionAssembly,
): MeshEngineRuntimeTransferAndInboundPhase {
    val sharedState = foundation.sharedState
    val routingAndTrust = foundation.routingAndTrust
    val messageDeliverySupport =
        buildMeshEngineRuntimeMessageDeliverySupport(
            localIdentity = environment.localIdentity,
            runtimeGate = environment.compatibilitySurface.runtimeGate,
            trustSupport = routingAndTrust.trustSupport,
            mutableMessages = environment.compatibilitySurface.mutableMessages,
            emitHopSessionFailed = session.emitHopSessionFailed,
            emitDiagnostic = support.emitDiagnostic,
        )
    val sendTransferTowardsDestination =
        createMeshEngineRuntimeSendTransferTowardsDestination(
            routeCoordinator = sharedState.routeCoordinator,
            sendEncryptedWireFrame = session.sendEncryptedWireFrame,
        )
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit = { peerId, action ->
        environment.platformBridge.clearQueuedOutboundFrames(peerId = peerId, action = action)
    }
    val outboundDeliveryPhase =
        buildMeshEngineRuntimeTransferOutboundDeliveryPhase(
            environment = environment,
            support = support,
            sharedState = sharedState,
            routingAndTrust = routingAndTrust,
            session = session,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
        )
    val transferSupport =
        buildMeshEngineRuntimeTransferSupport(
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            isLocalPeerId = session.isLocalPeerId,
            transferRegistry = sharedState.transferRegistry,
            outboundTransferLifecycleSupport =
                outboundDeliveryPhase.outboundTransferLifecycleSupport,
            sendEncryptedWireFrame = session.sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
            deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            routeMetadata = { peerId, metadata ->
                routingAndTrust.routingSupport.peerRouteMetadata(
                    peerId = peerId,
                    metadata = metadata,
                )
            },
            emitDiagnostic = support.emitDiagnostic,
        )
    val inboundSupport =
        buildMeshEngineRuntimeInboundSupport(
            localIdentity = environment.localIdentity,
            sessionRegistry = sharedState.sessionRegistry,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            emitHopSessionFailed = session.emitHopSessionFailed,
            decryptHopPayload = session.decryptHopPayload,
            captureHardRunToken = environment.compatibilitySurface.runtimeGate::captureHardRunToken,
            forwardMessageToNextHop = session.forwardMessageToNextHop,
            deliverInnerEnvelope = messageDeliverySupport::deliverInnerEnvelope,
            forwardEndToEndHandshakeFrame = session.forwardEndToEndHandshakeFrame,
            handleLocalEndToEndHandshakeFrame = { _, frame ->
                session.handleLocalEndToEndHandshakeFrame(frame)
            },
            transferSupport = transferSupport,
        )
    return MeshEngineRuntimeTransferAndInboundPhase(
        sendPayload = outboundDeliveryPhase.outboundDeliverySupport::sendPayload,
        handleEncryptedDataFrame = inboundSupport::handleEncryptedDataFrame,
        abortLocalTransfers = transferSupport::abortLocalTransfers,
        clearOutboundTransfers = outboundDeliveryPhase.outboundTransferLifecycleSupport::clearAll,
    )
}

private fun createMeshEngineRuntimeSendTransferTowardsDestination(
    routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean {
    return { peerId, frame, action, hardRunToken ->
        val route = routeCoordinator.routeFor(peerId)
        val nextHopPeerId = route?.nextHopPeerId ?: peerId
        sendEncryptedWireFrame(nextHopPeerId, frame, action, hardRunToken)
    }
}

private data class MeshEngineRuntimeTransferOutboundDeliveryPhase(
    val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    val outboundDeliverySupport: MeshEngineOutboundDeliverySupport,
)

private fun buildMeshEngineRuntimeTransferOutboundDeliveryPhase(
    environment: MeshEngineRuntimeAssemblyEnvironment,
    support: MeshEngineRuntimeAssemblySupport,
    sharedState: MeshEngineRuntimeSharedState,
    routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
    session: MeshEngineRuntimeSessionAssembly,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
): MeshEngineRuntimeTransferOutboundDeliveryPhase {
    val outboundRecipientTrustSupport =
        buildMeshEngineRuntimeOutboundRecipientTrustSupport(
            trustStore = environment.trustStore,
            ensureEndToEndSession = session.ensureEndToEndSession,
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
                session.emitHopSessionFailed(
                    peerId,
                    "delivery.send.encrypt",
                    DiagnosticReason.TRUST_FAILURE,
                    mapOf("cause" to cause),
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
                session.emitHopSessionFailed(
                    peerId,
                    "transfer.encrypt",
                    DiagnosticReason.TRUST_FAILURE,
                    mapOf("cause" to cause),
                )
            },
            emitDiagnostic = support.emitDiagnostic,
        )
    val outboundTransferLifecycleSupport =
        buildMeshEngineRuntimeOutboundTransferLifecycleSupport(
            transferRegistry = sharedState.transferRegistry,
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
    val inlineOutboundDeliveryAdapter =
        buildMeshEngineRuntimeInlineOutboundDeliveryAdapter(
            inlineMessagePayloadBytes =
                sharedState.runtimePolicies.delivery.inlineMessagePayloadBytes,
            routeCoordinator = sharedState.routeCoordinator,
            routingSupport = routingAndTrust.routingSupport,
            ensureHopSession = session.ensureHopSession,
            sendEncryptedDirectWireFrame = session.sendEncryptedDirectWireFrame,
            emitHopSessionFailed = session.emitHopSessionFailed,
            inlineMessagePreparationSupport = inlineMessagePreparationSupport,
            discoverySuspensionSupport = inlineDiscoverySuspensionSupport,
            ttlMillisFor = sharedState.runtimePolicies.delivery::ttlMillis,
            scheduleRetryDiagnostic = routingAndTrust.scheduleRetryDiagnostic,
            emitDiagnostic = support.emitDiagnostic,
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
            deliveryRetryDeadline = sharedState.runtimePolicies.delivery.retryDeadline,
            deliveryRetrySupport = deliveryRetrySupport,
            inlineOutboundDeliveryAdapter = inlineOutboundDeliveryAdapter,
            largeTransferOutboundDeliveryAdapter = largeTransferOutboundDeliveryAdapter,
        )
    return MeshEngineRuntimeTransferOutboundDeliveryPhase(
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        outboundDeliverySupport = outboundDeliverySupport,
    )
}
