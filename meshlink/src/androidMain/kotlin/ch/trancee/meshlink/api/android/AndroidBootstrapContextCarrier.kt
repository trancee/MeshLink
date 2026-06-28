package ch.trancee.meshlink.api.android

import android.content.Context

/** Public bridge that carries the Android application context into the MeshLink bootstrap path. */
public interface AndroidBootstrapContextCarrier {
    public val context: Context
}
