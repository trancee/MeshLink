package ch.trancee.meshlink.api.android;

import android.content.Context;
import ch.trancee.meshlink.api.MeshLinkBootstrap;

public final class ContextBootstrap extends MeshLinkBootstrap {
    public final Context context;

    public ContextBootstrap(Context context) {
        this.context = context.getApplicationContext();
    }
}
