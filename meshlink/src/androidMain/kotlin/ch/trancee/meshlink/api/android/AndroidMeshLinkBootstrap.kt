package ch.trancee.meshlink.api.android

import android.content.Context
import ch.trancee.meshlink.api.MeshLinkBootstrap

/** Public Android bootstrap handle accepted by [ch.trancee.meshlink.api.MeshLink]. */
public class AndroidMeshLinkBootstrap(override val context: Context) :
    MeshLinkBootstrap(), AndroidBootstrapContextCarrier
