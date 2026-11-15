package ch.trancee.lab.host

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.lab.LabSession
import ch.trancee.lab.labSession

/**
 * The Android lab host: it runs the shared scenario named by the `args` launch extra
 * (`am start --es args scenario=…,run=…`) and shows its latest status line.
 */
class MainActivity : ComponentActivity() {
    private val status = mutableStateOf("idle")
    private var session: LabSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Text(status.value, Modifier.padding(16.dp))
                }
            }
        }
        val args = intent.getStringExtra("args")
        if (args != null) {
            session = labSession(this, args).also { lab -> lab.start { line -> runOnUiThread { status.value = line } } }
        }
    }

    override fun onDestroy() {
        session?.cancel()
        super.onDestroy()
    }
}
