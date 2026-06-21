package ch.trancee.meshlink.reference

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIView
import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.platform.createAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createPlatformServices
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/** iOS entry point that wraps the shared Compose app. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices = createPlatformServices()
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS entry point that renders the shared Compose app into a UIView. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceRootView(): UIView {
    val platformServices = createPlatformServices()
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}

public fun createReferenceAutomationRootViewController(
    storageSubdirectory: String,
    appId: String,
    role: String,
): UIViewController {
    val platformServices =
        createAutomationPlatformServices(storageSubdirectory = storageSubdirectory, blocked = false)
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceAutomationRootView(
    storageSubdirectory: String,
    appId: String,
    role: String,
): UIView {
    val platformServices =
        createAutomationPlatformServices(storageSubdirectory = storageSubdirectory, blocked = false)
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}

@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceLiveAutomationRootViewController(
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
    targetPeerId: String?,
    scenario: String,
): UIViewController {
    val platformServices =
        createLiveAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            appId = appId,
            role = role,
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario,
        )
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS live-proof automation root view helper. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceLiveAutomationRootView(
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
    targetPeerId: String?,
    scenario: String,
): UIView {
    val platformServices =
        createLiveAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            appId = appId,
            role = role,
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario,
        )
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}
