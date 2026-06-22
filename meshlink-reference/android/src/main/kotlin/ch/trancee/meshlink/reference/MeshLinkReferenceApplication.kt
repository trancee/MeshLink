package ch.trancee.meshlink.reference

import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication

class MeshLinkReferenceApplication : MultiDexApplication() {
    override fun attachBaseContext(base: Context) {
        Log.i("MeshLinkReference", "reference application attachBaseContext -> multidex.install")
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}
