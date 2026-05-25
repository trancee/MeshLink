package ch.trancee.meshlink.reference.advanced

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal fun Modifier.referenceActionAccessibility(label: String, testTag: String): Modifier {
    return this.testTag(testTag).semantics { contentDescription = label }
}
