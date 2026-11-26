package ch.trancee.meshlink.internal

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import ch.trancee.meshlink.DiagnosticEvent.Severity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 37])
class AndroidLogTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun eachSeverityIsWrittenUnderTheMeshLinkTagAtItsPriority() {
        // Arrange
        val log = AndroidPlatformPort(app).log
        ShadowLog.clear()

        // Act
        log.write(Severity.Debug, "ML3006 GattOperationRetried count=1", "")
        log.write(Severity.Info, "ML1001 Started count=1", "profile=Unsecured")
        log.write(Severity.Warning, "ML2002 ScanFailed count=1 platformCode=6", "")
        log.write(Severity.Error, "ML8001 ScannerRegistrationFailed count=2", "peer=ab12")

        // Assert
        val written = ShadowLog.getLogsForTag("MeshLink").map { it.type to it.msg }
        val expected =
            listOf(
                Log.DEBUG to "ML3006 GattOperationRetried count=1",
                Log.INFO to "ML1001 Started count=1 profile=Unsecured",
                Log.WARN to "ML2002 ScanFailed count=1 platformCode=6",
                Log.ERROR to "ML8001 ScannerRegistrationFailed count=2 peer=ab12",
            )
        assertEquals(expected, written)
    }
}
