package ch.trancee.meshlink.bench

import ch.trancee.meshlink.bench.crypto.CryptoBench
import ch.trancee.meshlink.bench.crypto.Sodium
import ch.trancee.meshlink.bench.crypto.VectorCheck

/** Entry point for the Swift app: vector checks and crypto benchmarks, as a plain-text report. */
public object CryptoBenchmarks {
    public fun vectors(): String {
        check(Sodium.initResult >= 0) { "sodium_init failed" }
        return VectorCheck.run().lines.joinToString("\n")
    }

    public fun run(warmup: Int, iterations: Int): String {
        check(Sodium.initResult >= 0) { "sodium_init failed" }
        val lines = ArrayList<String>()
        lines += "libsodium ${Sodium.version} warmup=$warmup iterations=$iterations"
        lines += VectorCheck.run().lines
        lines += CryptoBench(warmup, iterations).run()
        return lines.joinToString("\n")
    }
}
