package ch.trancee.meshlink.api

import android.content.Context

/** Creates a typed MeshLink bootstrap handle from an Android application context. */
public fun androidMeshLinkBootstrap(context: Context): MeshLinkBootstrap {
    return AndroidContextMeshLinkBootstrap(context.applicationContext)
}

internal class AndroidContextMeshLinkBootstrap internal constructor(internal val context: Context) :
    MeshLinkBootstrap()
