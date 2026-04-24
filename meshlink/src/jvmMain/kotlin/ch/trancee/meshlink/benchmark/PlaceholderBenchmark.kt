package ch.trancee.meshlink.benchmark

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

/**
 * Placeholder benchmark that proves the kotlinx-benchmark infrastructure is wired up correctly.
 *
 * Real benchmark suites (CryptoBenchmark, WireFormatBenchmark, …) will be added in later slices
 * once the corresponding implementations exist. See spec/12-platform-and-testing.md §B for the full
 * benchmark suite plan and ±10–15% regression thresholds.
 *
 * Internal — JMH generates subclasses in the same package; internal (package-private at JVM
 * bytecode level) is sufficient for JMH access and keeps this out of the public ABI.
 */
@State(Scope.Benchmark)
internal class PlaceholderBenchmark {

    @Benchmark fun integerAddition(): Int = 1 + 1
}
