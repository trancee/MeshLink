package ch.trancee.meshlink.integration

internal actual fun usedHeapBytesOrNull(): Long? {
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

internal actual fun requestHeapStabilization(): Unit {
    repeat(3) {
        System.gc()
        Thread.sleep(25L)
    }
}
