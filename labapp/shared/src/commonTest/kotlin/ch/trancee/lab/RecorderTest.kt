package ch.trancee.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class RecorderTest {
    @Test
    fun writesOneJsonObjectWithTheMonotonicMillisecondsTheEventAndEscapedFields() {
        val time = TestTimeSource()
        val lines = mutableListOf<String>()
        val recorder = Recorder(lines::add, time.markNow())
        time += 1_250.milliseconds

        recorder.record(
            "send",
            "text" to "a\"b\\c\n",
            "bytes" to 3,
            "ok" to true,
            "none" to null,
            "list" to listOf("x", 2L),
        )

        assertEquals(
            """{"t_ms":1250,"event":"send","text":"a\"b\\c\u000a","bytes":3,"ok":true,"none":null,"list":["x",2]}""",
            lines.single(),
        )
    }
}
