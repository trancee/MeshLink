package ch.trancee.meshlink.vectors

import ch.trancee.meshlink.crypto.sodium.hex
import kotlin.test.fail

/** One test of a vector file, with the group it belongs to and the handler key it dispatches on. */
internal class VectorCase(
    val file: String,
    val key: String,
    val group: Json,
    val test: Json,
    val tcId: Int,
    val result: String,
) {
    val label: String get() = "$file group $key tcId $tcId"

    fun hex(name: String): ByteArray = test[name].string.hex()

    fun flagged(flag: String): Boolean = test.has("flags") && test["flags"].list.any { it.string == flag }
}

/** Checks one [VectorCase]; [expected] names the test field holding the expected output's hex. */
internal class Handler(
    val expected: String,
    val check: (VectorCase) -> Unit,
)

/** How the runner reads one vector file: where its tests are, how each dispatches, and the handlers. */
internal class VectorSet(
    val file: String,
    val handlers: Map<String, Handler>,
    val cases: (Json) -> List<RawCase> = ::groupedCases,
    val key: (group: Json, test: Json) -> String,
)

/** A test before dispatch. */
internal class RawCase(
    val group: Json,
    val test: Json,
    val tcId: Int,
    val result: String,
)

/** Wycheproof's and tier 1's layout: `testGroups`, each with `tests` carrying `tcId` and `result`. */
internal fun groupedCases(document: Json): List<RawCase> =
    document["testGroups"].list.flatMap { group ->
        group["tests"].list.map { RawCase(group, it, it["tcId"].int, it["result"].string) }
    }

/** Tests that no handler runs yet, each with the reason. A handled or unmatched key is stale. */
internal sealed class Skip(
    val file: String,
    val key: String,
) {
    /** Code that a later ticket lands. */
    class Pending(
        file: String,
        key: String,
        val ticket: Int,
    ) : Skip(file, key)

    /** Tests outside MeshLink's scope. */
    class NotApplicable(
        file: String,
        key: String,
        val reason: String,
    ) : Skip(file, key)
}

/** What one file's run checked, by `result`, and how many tests it skipped. */
internal class FileReport(
    val file: String,
    val checked: Map<String, Int>,
    val skipped: Int,
) {
    override fun toString(): String {
        val counts = RESULTS.joinToString { "${checked[it] ?: 0} $it" }
        return "$file: $counts, $skipped skipped"
    }

    private companion object {
        val RESULTS = listOf("valid", "invalid", "acceptable")
    }
}

/**
 * Runs every test of every vector file through its [VectorSet]'s handlers (ADR 0017 decision 10).
 * A file without a set, a test with neither a handler nor a [Skip], and a stale [Skip] fail the run;
 * so does a mismatch, with the file, group key and `tcId` in the message.
 */
internal class VectorRunner(
    private val sets: List<VectorSet>,
    private val skips: List<Skip>,
) {
    fun run(documents: Map<String, Json>): List<FileReport> = documents.map { (file, document) -> run(file, document) }

    private fun run(
        file: String,
        document: Json,
    ): FileReport {
        val set = sets.singleOrNull { it.file == file } ?: fail("$file has no vector set")
        val cases = set.cases(document)
        if (document.has("numberOfTests") && document["numberOfTests"].int != cases.size) {
            fail("$file: numberOfTests is ${document["numberOfTests"].int}, found ${cases.size} tests")
        }
        val checked = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        var skipped = 0
        cases.forEach { raw ->
            val case = VectorCase(file, set.key(raw.group, raw.test), raw.group, raw.test, raw.tcId, raw.result)
            seen += case.key
            if (case.result !in RESULTS) fail("${case.label}: unknown result ${case.result}")
            val handler = set.handlers[case.key]
            when {
                handler != null -> {
                    check(handler, case)
                    checked[case.result] = (checked[case.result] ?: 0) + 1
                }

                skips.any { it.file == file && it.key == case.key } -> {
                    skipped++
                }

                else -> {
                    fail("${case.label}: no handler and no skip")
                }
            }
        }
        checkCoverage(set, seen)
        return FileReport(file, checked, skipped)
    }

    /** Every skip and every handler of [set] must match a test, and no key may be both. */
    private fun checkCoverage(
        set: VectorSet,
        seen: Set<String>,
    ) {
        skips.filter { it.file == set.file }.forEach { skip ->
            if (skip.key in set.handlers) fail("${set.file} group ${skip.key}: skipped but handled")
            if (skip.key !in seen) fail("${set.file} group ${skip.key}: skip matches no test")
        }
        (set.handlers.keys - seen).forEach { fail("${set.file} group $it: handler matches no test") }
    }

    private fun check(
        handler: Handler,
        case: VectorCase,
    ) {
        try {
            handler.check(case)
        } catch (failure: AssertionError) {
            throw AssertionError("${case.label}: ${failure.message}", failure)
        } catch (failure: RuntimeException) {
            throw AssertionError("${case.label}: unexpected $failure", failure)
        }
    }

    private companion object {
        val RESULTS = setOf("valid", "invalid", "acceptable")
    }
}
