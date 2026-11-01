package ch.trancee.meshlink

import ch.trancee.meshlink.fake.FakeMesh
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

/** Runs [body] against a fake mesh on the test scheduler's virtual time, closing it afterwards. */
fun meshTest(body: suspend TestScope.(fake: FakeMesh) -> Unit) = runTest {
    val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }
    val fake = FakeMesh(StandardTestDispatcher(testScheduler), clock)
    try {
        body(fake)
    } finally {
        fake.close()
    }
}
