package ch.trancee.meshlink.reference.platform

import android.content.Context
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.OkioReferenceDocumentStore

internal fun createAndroidPlatformServices(context: Context): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        nowProvider = { System.currentTimeMillis() },
        platformContext = context,
        documentStore = OkioReferenceDocumentStore(context.filesDir.absolutePath),
    )
}
