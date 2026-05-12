package ch.trancee.meshlink.proof.android

import java.util.Locale

internal enum class ProofBenchmarkTransport {
    MeshLink,
    GattPrototype,
}

internal fun parseProofBenchmarkTransport(rawValue: String?): ProofBenchmarkTransport {
    return when (rawValue?.lowercase(Locale.US)) {
        "gatt", "gattprototype", "gatt-prototype" -> ProofBenchmarkTransport.GattPrototype
        else -> ProofBenchmarkTransport.MeshLink
    }
}
