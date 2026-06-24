package ch.trancee.meshlink.api.android

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkBootstrap

internal interface AndroidBootstrapContextCarrier {
    val context: Context
}

/** Creates a typed MeshLink bootstrap handle from an Android application context. */
public fun meshLinkBootstrap(context: Context): MeshLinkBootstrap {
    val appContext = context.applicationContext
    return object : MeshLinkBootstrap(), AndroidBootstrapContextCarrier {
        override val context: Context = appContext
    }
}
