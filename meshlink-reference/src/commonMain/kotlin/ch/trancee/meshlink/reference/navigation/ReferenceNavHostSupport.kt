package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

@Composable
internal fun rememberReferenceWorkflowTitles(): Map<ReferenceSurfaceId, String> {
    return remember {
        ReferenceWorkflowCatalog.descriptors().associate { descriptor ->
            descriptor.surfaceId to descriptor.title
        }
    }
}

@Composable
internal fun rememberLastRouteBySection(): MutableMap<ReferencePrimarySection, ReferenceSurfaceId> {
    return remember {
        mutableStateMapOf<ReferencePrimarySection, ReferenceSurfaceId>().apply {
            ReferencePrimarySection.entries.forEach { section ->
                put(section, section.defaultSurface)
            }
        }
    }
}
