package ch.trancee.meshlink.proof.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)

        val meshLink = MeshLink.createAndroid(
            context = this,
            config = meshLinkConfig {
                appId = "demo.meshlink"
                regulatoryRegion = RegulatoryRegion.DEFAULT
                powerMode = PowerMode.Automatic
            },
        )

        val label = TextView(this).apply {
            text = "MeshLink proof scaffold ready: ${meshLink.state.value}"
        }
        setContentView(label)
    }
}
