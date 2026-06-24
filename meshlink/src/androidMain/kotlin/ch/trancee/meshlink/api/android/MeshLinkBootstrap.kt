package ch.trancee.meshlink.api.android

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.api.MeshLinkBootstrap

private const val MESH_LINK_BOOTSTRAP_LOG_TAG: String = "MeshLinkBootstrap"

internal interface AndroidBootstrapContextCarrier {
    val context: Context
}

/** Creates a typed MeshLink bootstrap handle from an Android application context. */
public fun meshLinkBootstrap(context: Context): MeshLinkBootstrap {
    Log.i(
        MESH_LINK_BOOTSTRAP_LOG_TAG,
        "meshLinkBootstrap.begin thread=${Thread.currentThread().name}",
    )
    Log.i(MESH_LINK_BOOTSTRAP_LOG_TAG, "meshLinkBootstrap.applicationContext.begin")
    val appContext = context.applicationContext
    Log.i(
        MESH_LINK_BOOTSTRAP_LOG_TAG,
        "meshLinkBootstrap.applicationContext.end thread=${Thread.currentThread().name}",
    )
    val bootstrap =
        object : MeshLinkBootstrap(), AndroidBootstrapContextCarrier {
            override val context: Context = appContext
        }
    Log.i(
        MESH_LINK_BOOTSTRAP_LOG_TAG,
        "meshLinkBootstrap.end thread=${Thread.currentThread().name}",
    )
    return bootstrap
}
