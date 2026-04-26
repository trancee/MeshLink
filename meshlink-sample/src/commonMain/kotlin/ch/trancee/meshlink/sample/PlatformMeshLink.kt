package ch.trancee.meshlink.sample

import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkConfig

/**
 * Platform-specific factory that creates a [MeshLinkApi] instance wired to the real BLE
 * transport for the current target.
 *
 * - **Android**: delegates to [ch.trancee.meshlink.api.MeshLink.createAndroid] via [appContext]
 *   set in [MainActivity.onCreate].
 * - **iOS**: delegates to [ch.trancee.meshlink.api.MeshLink.createIos].
 *
 * Called once per app composition from [App] inside a [androidx.compose.runtime.remember] block,
 * so the instance is created at most once per process lifetime.
 */
expect fun createPlatformMeshLink(config: MeshLinkConfig): MeshLinkApi
