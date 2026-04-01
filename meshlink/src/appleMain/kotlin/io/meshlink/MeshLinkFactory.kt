package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.CryptoProvider
import io.meshlink.transport.IosBleTransport
import io.meshlink.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Swift-friendly factory for creating [MeshLink] instances.
 *
 * Kotlin/Native does not export default parameter values to Objective-C/Swift,
 * so this object provides convenience methods that supply sensible defaults for
 * platform-specific parameters like [CoroutineScope] and clock functions.
 */
object MeshLinkFactory {

    /**
     * Create a [MeshLink] instance wired with [IosBleTransport].
     *
     * Usage from Swift:
     * ```swift
     * let config = MeshLinkConfig.companion.chatOptimized { _ in }
     * let meshLink = MeshLinkFactory.shared.create(config: config)
     * ```
     */
    fun create(config: MeshLinkConfig = MeshLinkConfig.chatOptimized()): MeshLink {
        val scope = CoroutineScope(Dispatchers.Main)
        val transport = IosBleTransport(scope)
        return MeshLink(
            transport = transport,
            config = config,
            crypto = CryptoProvider(),
            coroutineContext = EmptyCoroutineContext,
            clock = { currentTimeMillis() },
        )
    }

    /**
     * Create a [MeshLink] instance with a custom [IosBleTransport].
     */
    fun create(transport: IosBleTransport, config: MeshLinkConfig = MeshLinkConfig.chatOptimized()): MeshLink {
        return MeshLink(
            transport = transport,
            config = config,
            crypto = CryptoProvider(),
            coroutineContext = EmptyCoroutineContext,
            clock = { currentTimeMillis() },
        )
    }
}
