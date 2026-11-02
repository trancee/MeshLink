package ch.trancee.meshlink.bench

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/** Kotlin/Native GC hooks for the I2 diagnosis: is footprint growth garbage the GC has not reclaimed yet, or a leak? */
@OptIn(NativeRuntimeApi::class, ExperimentalNativeApi::class, ExperimentalStdlibApi::class)
public object BenchRuntime {
    public fun collectGarbage() {
        GC.collect()
    }

    /** Live Kotlin heap object bytes after the last GC, or -1 when unknown. */
    public fun heapBytesAfterLastGc(): Long =
        GC.lastGCInfo?.memoryUsageAfter?.values?.sumOf { it.totalObjectsSizeBytes } ?: -1
}
