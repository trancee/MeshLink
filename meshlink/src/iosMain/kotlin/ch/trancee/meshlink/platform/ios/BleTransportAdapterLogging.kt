@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlinx.cinterop.toKString
import platform.posix.getenv

private const val PEER_LOG_SUFFIX_CHARS: Int = 6
private const val ENV_VALUE_NUMERIC_TRUE: String = "1"
private const val ENV_VALUE_BOOLEAN_TRUE: String = "true"
private const val ENV_VALUE_YES: String = "yes"

internal const val TRANSPORT_TELEMETRY_ENV: String = "MESHLINK_TRANSPORT_TELEMETRY"
internal const val TRANSPORT_DEBUG_ENV: String = "MESHLINK_TRANSPORT_DEBUG"

/**
 * Diagnostic-only override for [shouldInitiateL2cap]'s normal key-hash tie-break, used to make
 * connection-admission-control validation (`hasConnectionBudget()`, see power/PowerPolicy.kt)
 * reproducible on physical hardware. The tie-break that decides which side of a peer pair locally
 * initiates the outgoing L2CAP connect is otherwise effectively random per mesh identity (it
 * compares each side's key hash), so exercising the admission gate's deferral branch against a real
 * multi-peer physical fleet could not be forced -- see
 * docs/explanation/ble-connection-robustness.md's iOS admission-control validation notes. Setting
 * this to a truthy value (see [readEnvironmentFlag]) makes this device always attempt to locally
 * initiate the L2CAP connect for every discovered L2CAP-capable peer, regardless of key-hash
 * comparison. Never read outside of [shouldInitiateL2cap]; never set in normal product usage.
 */
internal const val FORCE_L2CAP_INITIATOR_ENV: String = "MESHLINK_FORCE_L2CAP_INITIATOR"

internal inline fun BleTransportAdapter.log(message: () -> String): Unit {
    if (transportDebugLoggingEnabled) {
        emitTransportLog(message())
    }
}

internal fun BleTransportAdapter.log(message: String): Unit {
    log { message }
}

internal inline fun BleTransportAdapter.reportLog(message: () -> String): Unit {
    if (transportDebugLoggingEnabled || telemetryEnabled) {
        emitTransportLog(message())
    }
}

internal fun BleTransportAdapter.reportLog(message: String): Unit {
    reportLog { message }
}

internal fun BleTransportAdapter.emitTransportLog(message: String): Unit {
    println("MeshLinkTransport $message")
}

internal fun readEnvironmentFlag(name: String): Boolean {
    return getenv(name)?.toKString()?.lowercase()?.let { value ->
        value == ENV_VALUE_NUMERIC_TRUE || value == ENV_VALUE_BOOLEAN_TRUE || value == ENV_VALUE_YES
    } ?: false
}

internal fun PeerId.logSuffix(): String {
    return value.takeLast(PEER_LOG_SUFFIX_CHARS)
}

internal fun String.logSuffix(): String {
    return takeLast(PEER_LOG_SUFFIX_CHARS)
}
