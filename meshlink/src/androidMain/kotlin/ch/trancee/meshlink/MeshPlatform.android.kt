package ch.trancee.meshlink

import android.content.Context
import ch.trancee.meshlink.internal.AndroidPlatformPort

private val lock = Any()

@Volatile private var androidPlatform: MeshPlatform? = null

/**
 * The platform of this Android app, created from [context]'s application on the first call and the
 * same on every later call. Nodes on it share the one Bluetooth radio, so one of them runs at a time.
 *
 * The app requests the runtime permissions itself; [MeshState.Blocked] names the missing ones.
 */
public fun MeshPlatform.Companion.android(context: Context): MeshPlatform =
    synchronized(lock) {
        androidPlatform ?: MeshPlatform(AndroidPlatformPort(context)).also { androidPlatform = it }
    }
