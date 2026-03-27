package io.meshlink.sample

import android.app.Application
import io.meshlink.power.AndroidBatteryMonitor

class MeshLinkSampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidBatteryMonitor.init(this)
    }
}
