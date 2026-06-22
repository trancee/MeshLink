package ch.trancee.meshlink.reference

import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

internal fun MainActivity.bindReferenceContent(
    platformServices: AndroidLiveAutomationState,
    automationConfig: AutomationConfig,
) {
    lifecycleScope.launch {
        runCatching {
            platformServices.meshLinkController.start()
        }.onSuccess {
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION android.live.controller.start.complete role=${automationConfig.role}",
            )
        }.onFailure { throwable ->
            platformServices.emitAutomationLog(
                "REFERENCE_AUTOMATION android.live.controller.start.failed " +
                    "role=${automationConfig.role} " +
                    "error=${throwable::class.simpleName}: ${throwable.message}",
            )
            throw throwable
        }
    }

    setContent { }
}
