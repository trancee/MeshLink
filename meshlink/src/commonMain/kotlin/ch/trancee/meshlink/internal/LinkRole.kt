@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink.internal

import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/** Which end of a link this node is; for the lab apps (ADR 0017 decision 7). */
@HiddenFromObjC
public enum class LinkRole {
    /** This node connected to the peer's advertisement. */
    CENTRAL,

    /** The peer connected to this node's advertisement. */
    PERIPHERAL,
}
