package ch.trancee.meshlink.fuzz

import kotlin.reflect.KClass

/**
 * A Jazzer target: [target] has a static `fuzzerTestOneInput(ByteArray)`, and [covers] names the
 * decoders it reaches, as `Class.method` with the JVM binary class name inside the package.
 */
class FuzzTarget(
    val target: KClass<*>,
    val covers: Set<String>,
) {
    /** The corpus directory name. */
    val name: String = checkNotNull(target.simpleName).removeSuffix("Fuzzer")
}

/** Every target `:meshlink-core:fuzz` runs, in order. */
val fuzzTargets: List<FuzzTarget> =
    listOf(
        FuzzTarget(AdvertisementFuzzer::class, setOf("Advertisement\$Companion.decode")),
        FuzzTarget(CapabilityRecordFuzzer::class, setOf("CapabilityRecord\$Companion.decode")),
        FuzzTarget(LinkRecordFuzzer::class, setOf("LinkRecord.decode", "RecordReader.read")),
        FuzzTarget(
            LinkFrameFuzzer::class,
            setOf(
                "LinkFrame\$Companion.decode",
                "LinkFrame\$PlainHello\$Companion.decode",
                "LinkFrame\$Handshake\$Companion.decode",
                "LinkFrame\$Fragment\$Companion.decode",
                "LinkFrame\$Busy\$Companion.decode",
                "LinkFrame\$NeighborList\$Companion.decode",
                "LinkFrame\$Shuffle\$Companion.decode",
                "LinkFrame\$ShuffleReply\$Companion.decode",
                "LinkFrame\$Close\$Companion.decode",
                "LinkFrame\$Rekey.decode",
                "LinkFrame\$BearerSwitch.decode",
                "LinkFrame\$ResolvingKeyUpdate\$Companion.decode",
                "LinkFrame\$Credit\$Companion.decode",
            ),
        ),
        FuzzTarget(HandshakePayloadFuzzer::class, setOf("HandshakePayload\$Companion.decode", "Tlv\$Companion.decode")),
        FuzzTarget(IdentityCertificateFuzzer::class, setOf("IdentityCertificate\$Companion.decode")),
        FuzzTarget(MessageFrameFuzzer::class, setOf("MessageFrame\$Companion.decode", "Container\$Companion.decode")),
        FuzzTarget(UnicastFrameFuzzer::class, setOf("UnicastFrame\$Companion.decode")),
        FuzzTarget(RouteRequestFuzzer::class, setOf("RouteRequest\$Companion.decode")),
        FuzzTarget(RouteReplyFuzzer::class, setOf("RouteReply\$Companion.decode")),
        FuzzTarget(BroadcastFrameFuzzer::class, setOf("BroadcastFrame\$Companion.decode")),
        FuzzTarget(FloodFrameFuzzer::class, setOf("FloodFrame\$Companion.decode")),
        FuzzTarget(
            MessageContentFuzzer::class,
            setOf("MessageContent\$Companion.decode", "Content\$Companion.unframe", "Content\$Companion.extensions"),
        ),
        FuzzTarget(ReceiptContentFuzzer::class, setOf("ReceiptContent\$Companion.decode")),
        FuzzTarget(ResolvingKeyFuzzer::class, setOf("ResolvingKey\$Companion.decode")),
    )
