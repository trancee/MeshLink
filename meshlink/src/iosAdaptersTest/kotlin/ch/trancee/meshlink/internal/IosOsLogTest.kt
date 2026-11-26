package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent.Severity
import kotlin.test.Test

class IosOsLogTest {
    // Kotlin/Native has no OSLog platform library, so OSLogStore can't read the entries back; `log
    // stream --predicate 'subsystem == "ch.trancee.meshlink"'` on the host shows them.
    @Test
    fun everySeverityIsWrittenWithAndWithoutAHiddenPart() {
        // Arrange
        val log = AppleLog(IosOsLog())

        // Act
        Severity.entries.forEach {
            log.write(it, "ML1001 Started count=1", "profile=Unsecured")
            log.write(it, "ML2002 ScanFailed count=1 platformCode=6", "")
        }
    }
}
