package ch.trancee.meshlink.vectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorRunnerTest {
    private val documents: Map<String, Json> = vectorFiles.mapValues { (_, text) -> Json.parse(text()) }

    @Test
    fun everyVectorFilePasses() {
        val reports = VectorRunner(vectorSets, vectorSkips).run(documents)

        reports.forEach(::println)
        assertEquals(documents.keys, reports.map { it.file }.toSet())
    }

    @Test
    fun everySetAndSkipNamesAnEmbeddedFile() {
        assertEquals(documents.keys, vectorSets.map { it.file }.toSet())
        assertTrue(vectorSkips.all { it.file in documents }, "skip for a file that isn't embedded")
    }

    @Test
    fun aTestWithoutHandlerOrSkipFailsTheRun() {
        val runner = VectorRunner(listOf(SET), emptyList())

        val failure = assertFailsWith<AssertionError> { runner.run(mapOf(FILE to Json.parse(TWO_LAYOUTS))) }

        assertEquals("$FILE group layout-b tcId 2: no handler and no skip", failure.message)
    }

    @Test
    fun aFileWithoutASetFailsTheRun() {
        val runner = VectorRunner(emptyList(), emptyList())

        val failure = assertFailsWith<AssertionError> { runner.run(mapOf(FILE to Json.parse(TWO_LAYOUTS))) }

        assertEquals("$FILE has no vector set", failure.message)
    }

    @Test
    fun aStaleSkipFailsTheRun() {
        val handled = Skip.Pending(FILE, "layout-a", 1)
        val unmatched = Skip.NotApplicable(FILE, "layout-c", "none")
        val skipB = Skip.Pending(FILE, "layout-b", 1)

        val handledFailure =
            assertFailsWith<AssertionError> {
                VectorRunner(listOf(SET), listOf(skipB, handled)).run(mapOf(FILE to Json.parse(TWO_LAYOUTS)))
            }
        val unmatchedFailure =
            assertFailsWith<AssertionError> {
                VectorRunner(listOf(SET), listOf(skipB, unmatched)).run(mapOf(FILE to Json.parse(TWO_LAYOUTS)))
            }

        assertEquals("$FILE group layout-a: skipped but handled", handledFailure.message)
        assertEquals("$FILE group layout-c: skip matches no test", unmatchedFailure.message)
    }

    @Test
    fun aWrongNumberOfTestsFailsTheRun() {
        val runner = VectorRunner(listOf(SET), listOf(Skip.Pending(FILE, "layout-b", 1)))

        val failure =
            assertFailsWith<AssertionError> {
                runner.run(
                    mapOf(FILE to Json.parse(TWO_LAYOUTS.replace("\"numberOfTests\":2", "\"numberOfTests\":3"))),
                )
            }

        assertEquals("$FILE: numberOfTests is 3, found 2 tests", failure.message)
    }

    /** T10: one corrupted expected byte in a copy of each file fails the run, naming the test. */
    @Test
    fun aCorruptedExpectedByteFailsEverySet() {
        val runner = VectorRunner(vectorSets, vectorSkips)
        vectorSets.filter { it.handlers.isNotEmpty() }.forEach { set ->
            val document = documents.getValue(set.file)
            val (case, handler) =
                set.cases(document).firstNotNullOf { raw ->
                    val handler = set.handlers[set.key(raw.group, raw.test)]
                    if (handler != null && raw.result == "valid" && raw.test[handler.expected].string.isNotEmpty()) {
                        raw to handler
                    } else {
                        null
                    }
                }
            val flipped = flipLastBit(case.test[handler.expected].string)
            val corrupted = document.replace(case.test, case.test.with(handler.expected, flipped))
            val label = "${set.file} group ${set.key(case.group, case.test)} tcId ${case.tcId}: "

            val failure = assertFailsWith<AssertionError>(set.file) { runner.run(mapOf(set.file to corrupted)) }

            assertTrue(failure.message.orEmpty().startsWith(label), "${set.file}: ${failure.message}")
        }
    }

    /** The last byte, so a layout's leading type byte, and with it the test's key, stays the same. */
    private fun flipLastBit(hex: String): String {
        val last = (hex.takeLast(2).toInt(HEX) xor 1).toString(HEX).padStart(2, '0')
        return hex.dropLast(2) + last
    }

    private fun Json.with(
        key: String,
        value: String,
    ): Json = Json.Object((this as Json.Object).members + (key to Json.Text(value)))

    private fun Json.replace(
        target: Json,
        replacement: Json,
    ): Json =
        when {
            this === target -> replacement
            this is Json.Object -> Json.Object(members.mapValues { it.value.replace(target, replacement) })
            this is Json.Array -> Json.Array(items.map { it.replace(target, replacement) })
            else -> this
        }

    private companion object {
        const val HEX = 16
        const val FILE = "test/two-layouts.json"
        const val TWO_LAYOUTS =
            """{"numberOfTests":2,"testGroups":[{"layout":"layout-a","tests":[{"tcId":1,"result":"valid"}]},""" +
                """{"layout":"layout-b","tests":[{"tcId":2,"result":"invalid"}]}]}"""
        val SET = VectorSet(FILE, mapOf("layout-a" to Handler("none") {})) { group, _ -> group["layout"].string }
    }
}
