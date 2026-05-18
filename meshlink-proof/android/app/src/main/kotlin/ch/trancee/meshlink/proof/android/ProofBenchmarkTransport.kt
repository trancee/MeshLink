package ch.trancee.meshlink.proof.android

import java.util.Locale

internal enum class ProofBenchmarkTransport {
    MeshLink,
    GattPrototype,
    GattNotifyPrototype,
}

internal fun parseProofBenchmarkTransport(rawValue: String?): ProofBenchmarkTransport {
    return when (rawValue?.lowercase(Locale.US)) {
        "gatt", "gattprototype", "gatt-prototype" -> ProofBenchmarkTransport.GattPrototype
        "gatt-notify", "gattnotify", "gattnotifyprototype", "gatt-notify-prototype" ->
            ProofBenchmarkTransport.GattNotifyPrototype
        else -> ProofBenchmarkTransport.MeshLink
    }
}
