package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

@Composable
internal fun rememberReferenceWorkflowTitles(): Map<ReferenceSurface, String> {
    return remember {
        ReferenceWorkflowCatalog.descriptors().associate { descriptor ->
            descriptor.surface to descriptor.title
        }
    }
}

@Composable
internal fun rememberLastRouteBySection(): MutableMap<ReferencePrimarySection, ReferenceSurface> {
    return remember {
        mutableStateMapOf<ReferencePrimarySection, ReferenceSurface>().apply {
            ReferencePrimarySection.entries.forEach { section ->
                put(section, section.defaultSurface)
            }
        }
    }
}
