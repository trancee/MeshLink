package meshlink.proto.codec

/**
 * Modes: sizes | truncation | fuzz [iterations] [seed] | bench [batches] | alloc.
 * The same JAR runs on the JVM and, dexed, on Android through app_process.
 */
fun main(args: Array<String>) {
    val mode = args.getOrElse(0) { "all" }
    println("# runtime=${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")} " +
        "os=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    when (mode) {
        "sizes" -> sizeReport().forEach(::println)
        "truncation" -> truncationReport().forEach(::println)
        "fuzz" -> {
            val iterations = args.getOrElse(1) { "1000000" }.toInt()
            val seed = args.getOrElse(2) { "0x4D455348" }.removePrefix("0x").toLong(16)
            println("# seed=0x${seed.toString(16)} iterations=$iterations")
            println(FuzzReport.HEADER)
            val cc = CodecC()
            for (s in fuzzSamples) {
                val t0 = System.nanoTime()
                println("${fuzzBC(s, iterations, seed, cc)}")
                println("${fuzzA(s, iterations, seed)}")
                System.err.println("# ${s.name} ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
        }
        "bench" -> {
            val batches = args.getOrElse(1) { "15" }.toInt()
            println(BenchResult.HEADER)
            runBench(listOf(CodecA, CodecB, CodecC()), benchSampleNames, batches = batches) { println(it) }
        }
        "alloc" -> Alloc.report().forEach(::println)
        else -> error("unknown mode $mode")
    }
}
