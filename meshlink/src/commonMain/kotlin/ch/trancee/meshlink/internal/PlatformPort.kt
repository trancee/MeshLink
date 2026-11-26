@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.Requirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * What a platform gives a node: one per platform radio, shared by every node on it. The port's
 * identity is the radio's: at most one node holds a port at a time.
 */
@HiddenFromObjC
public interface PlatformPort {
    /**
     * What the radio is missing, each time it changes; empty when it can run. It emits nothing until
     * the radio knows, and a node stays [ch.trancee.meshlink.MeshState.Starting] until then.
     */
    public val requirements: Flow<Set<Requirement>>

    /**
     * What a node on this radio runs without, each time it changes; empty for a radio that can
     * advertise. A change restarts a running node's discovery.
     */
    public val limitations: Flow<Set<Limitation>>

    /** Where nodes on this platform do their work; it runs one task at a time. */
    public val dispatcher: CoroutineDispatcher

    /** Where the installation identity is kept. */
    public val identityStore: IdentityStore

    /** The platform radio; a node uses it only while it holds the port. */
    public val radio: RadioPort

    /** The platform clocks. */
    public val clock: Clock

    /** The platform's non-cryptographic randomness, such as [SystemRandom]. */
    public val random: RandomSource

    /** The platform log, which nodes with `diagnostics.platformLog` on write their events to. */
    public val log: PlatformLog

    /** The platform's name, such as `Android` or `iOS`, for diagnostic exports. */
    public val platformName: String

    /** The version of the platform's OS, such as `15 (API 35)` or `18.2.0`, for diagnostic exports. */
    public val osVersion: String
}

/** The platform log (ADR 0020 decision 6): Logcat on Android, `os_log` on iOS. */
public fun interface PlatformLog {
    /**
     * Writes one event line at [severity]'s level: [shown], its code, name, count and numbers, which
     * the log may show publicly, then [hidden], its other fields, empty if it has none, which the
     * log should redact where it can.
     */
    public fun write(
        severity: DiagnosticEvent.Severity,
        shown: String,
        hidden: String,
    )
}

/**
 * Which node holds each platform radio; a node runs only while it holds its radio. Every radio in the
 * process shares the registry, but a node claims and watches only its own radio, so a node on another
 * radio, such as one of another mesh, never makes its claim fail.
 */
internal object RadioRegistry {
    private val holders = MutableStateFlow<Map<PlatformPort, Any>>(emptyMap())

    /** The holder of [port], or null while it is free, after every change to the registry. */
    fun holder(port: PlatformPort): Flow<Any?> = holders.map { it[port] }

    /** Makes [owner] the holder of [port] if [port] is free; returns whether it did. */
    fun tryAcquire(
        port: PlatformPort,
        owner: Any,
    ): Boolean = port !in holders.getAndUpdate { if (port in it) it else it + (port to owner) }

    /** Frees [port] if [owner] holds it. */
    fun release(
        port: PlatformPort,
        owner: Any,
    ) {
        holders.update { if (it[port] === owner) it - port else it }
    }
}
