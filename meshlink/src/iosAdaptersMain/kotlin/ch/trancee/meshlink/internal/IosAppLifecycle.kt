@file:OptIn(ExperimentalForeignApi::class)

package ch.trancee.meshlink.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np

private const val NANOS_PER_MILLI = 1_000_000uL

/**
 * The app's lifecycle as UIKit posts it on [center], which posts it for scene-based apps too. The
 * starting state is read on the main queue, where `UIApplication` lives. The clock is Darwin's
 * `CLOCK_MONOTONIC`, which keeps counting while the app is suspended and the device sleeps.
 */
internal class IosAppLifecycle(
    center: NSNotificationCenter = NSNotificationCenter.defaultCenter,
) : AppLifecycle {
    private val mutableBackground = MutableStateFlow(false)

    override val background: StateFlow<Boolean> = mutableBackground

    init {
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ ->
            mutableBackground.value = true
        }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ ->
            mutableBackground.value = false
        }
        dispatch_async(dispatch_get_main_queue()) {
            val state = UIApplication.sharedApplication.applicationState
            mutableBackground.value = state == UIApplicationState.UIApplicationStateBackground
        }
    }

    override fun continuousMillis(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.toUInt()) / NANOS_PER_MILLI).toLong()
}
