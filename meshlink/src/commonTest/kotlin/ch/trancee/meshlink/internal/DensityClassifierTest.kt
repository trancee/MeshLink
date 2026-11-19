package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals

/** A node's density class from its estimates (ADR 0008 decision 2). */
class DensityClassifierTest {
    private val clock = MutableClock()

    @Test
    fun aClassSwitchesUpAt12Point5And62Point5AndDownBelow40And8() {
        // Arrange
        val classifier = DensityClassifier(clock, pinned = null)
        val walk =
            listOf(
                9.99,
                10.0,
                12.49,
                12.5,
                50.0,
                62.49,
                62.5,
                50.0,
                40.0,
                39.99,
                10.0,
                8.0,
                7.99,
                62.5,
                7.99,
            )

        // Act
        val classes =
            walk.map { estimate ->
                clock.now += DWELL_MILLIS
                classifier.classify(estimate)
            }

        // Assert
        val low = DensityClass.LOW
        val medium = DensityClass.MEDIUM
        val high = DensityClass.HIGH
        val expected =
            listOf(low, low, low, medium, medium, medium, high, high, high, medium, medium, medium, low, high, low)
        assertEquals(expected, classes)
    }

    @Test
    fun theFirstEstimateIsClassifiedAtOnceByTheBounds10And50() {
        // Arrange
        val estimates = listOf(9.99, 10.0, 50.0, 50.01)

        // Act
        val before = DensityClassifier(clock, pinned = null).current
        val classes = estimates.map { DensityClassifier(clock, pinned = null).classify(it) }

        // Assert
        assertEquals(DensityClass.LOW, before)
        assertEquals(listOf(DensityClass.LOW, DensityClass.MEDIUM, DensityClass.MEDIUM, DensityClass.HIGH), classes)
    }

    @Test
    fun aNodeStaysInAClassForAtLeast60SecondsFromItsFirstClassificationAndEachSwitch() {
        // Arrange
        val classifier = DensityClassifier(clock, pinned = null)
        val classifyAt = { at: Long, estimate: Double ->
            clock.now = at
            classifier.classify(estimate)
        }

        // Act
        val classes =
            listOf(
                classifyAt(START, LOW),
                classifyAt(START + DWELL_MILLIS - 1, HIGH),
                classifyAt(START + DWELL_MILLIS, HIGH),
                classifyAt(START + 2 * DWELL_MILLIS - 1, LOW),
                classifyAt(START + 2 * DWELL_MILLIS, LOW),
            )

        // Assert
        val expected =
            listOf(
                DensityClass.LOW,
                DensityClass.LOW,
                DensityClass.HIGH,
                DensityClass.HIGH,
                DensityClass.LOW,
            )
        assertEquals(expected, classes)
    }

    @Test
    fun aPinnedClassNeverSwitchesWhateverTheEstimate() {
        // Arrange
        val classifier = DensityClassifier(clock, pinned = DensityClass.MEDIUM)
        val before = classifier.current

        // Act
        val classes =
            listOf(LOW, HIGH, LOW, HIGH).map { estimate ->
                clock.now += DWELL_MILLIS
                classifier.classify(estimate)
            }

        // Assert
        assertEquals(DensityClass.MEDIUM, before)
        assertEquals(List(classes.size) { DensityClass.MEDIUM }, classes)
        assertEquals(DensityClass.MEDIUM, classifier.current)
    }

    /** A clock that reads [now] on both timelines. */
    private class MutableClock : Clock {
        var now = START

        override fun monotonicMillis(): Long = now

        override fun wallClockMillis(): Long = now
    }

    private companion object {
        const val START = 1_000L
        const val DWELL_MILLIS = 60_000L
        const val LOW = 1.0
        const val HIGH = 100.0
    }
}
