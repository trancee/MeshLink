package ch.trancee.meshlink.reference.platform

import android.content.Context
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode

internal fun createAndroidPlatformServices(context: Context): DefaultPlatformServices {
    return DefaultPlatformServices(
        platformName = "Android",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = androidReadinessGuidance(),
        nowProvider = { System.currentTimeMillis() },
        platformContext = context,
        documentStore = AndroidReferenceDocumentStore(),
    )
}
