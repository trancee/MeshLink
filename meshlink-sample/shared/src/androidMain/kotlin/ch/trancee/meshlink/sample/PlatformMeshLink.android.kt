package ch.trancee.meshlink.sample

import android.content.Context
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkConfig
import ch.trancee.meshlink.api.createAndroid

/**
 * Android application context injected by the host Activity's `onCreate` before [App] is first
 * composed. Using [android.app.Application.getApplicationContext] ensures this reference outlives
 * the Activity without leaking a windowed context.
 *
 * `lateinit` is intentional: it is always set in the host Activity's `onCreate` before
 * [createPlatformMeshLink] is invoked (which happens inside a Compose `remember` block
 * triggered by `setContent { App() }`).
 */
lateinit var appContext: Context

/**
 * Android actual for [createPlatformMeshLink]: creates a [MeshLink] backed by
 * [ch.trancee.meshlink.transport.AndroidBleTransport].
 *
 * [appContext] must be set by [MainActivity] before this is called.
 */
actual fun createPlatformMeshLink(config: MeshLinkConfig): MeshLinkApi =
    MeshLink.createAndroid(appContext, config)
