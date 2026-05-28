package ch.trancee.meshlink.api.android

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkBootstrap

/** Creates a typed MeshLink bootstrap handle from an Android application context. */
public fun meshLinkBootstrap(context: Context): MeshLinkBootstrap {
    return ContextBootstrap(context.applicationContext)
}

internal class ContextBootstrap internal constructor(internal val context: Context) :
    MeshLinkBootstrap()
