package ch.trancee.meshlink.internal

import platform.darwin.dispatch_queue_create

/** CoreBluetooth on one serial queue, which the node's dispatcher runs on too. */
internal actual fun iosPort(): PlatformPort {
    val queue = dispatch_queue_create("ch.trancee.meshlink.bluetooth", null)
    val bluetooth = IosCoreBluetooth(queue)
    val dispatcher = QueueDispatcher(queue)
    return ApplePlatformPort(bluetooth, IosKeychain(), dispatcher, iosVersion(), IosAppLifecycle(), IosOsLog())
}
