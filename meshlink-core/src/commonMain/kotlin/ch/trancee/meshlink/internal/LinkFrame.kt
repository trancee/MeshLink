package ch.trancee.meshlink.internal

/**
 * The link-local frames this node handles (spec "Link-local frame summary"). [decode] reports
 * every other link-local type as [DecodeError.UNKNOWN_TYPE], which the receiver drops without
 * closing the link, and types `0x00` and `0xC0`–`0xFF` as [DecodeError.RESERVED_TYPE].
 */
public sealed interface LinkFrame {
    /** The frame's bytes. */
    public fun encode(): ByteArray

    /**
     * `FRAGMENT` (spec "Fragments"): piece [number] of stream [streamId], [last] if it ends the
     * stream, carrying 1 to [DATA_MAX] bytes of the fragmented frame.
     */
    public class Fragment(
        public val streamId: Int,
        public val number: Int,
        public val last: Boolean,
        public val data: ByteArray,
    ) : LinkFrame {
        init {
            require(streamId in 0..BYTE_MASK) { "stream_id must be a byte, not $streamId" }
            require(number in 0..NUMBER_MAX) { "number must be in 0..$NUMBER_MAX, not $number" }
            require(data.size in DATA_LENGTH) { "data must be 1 to $DATA_MAX bytes, not ${data.size}" }
        }

        override fun encode(): ByteArray {
            val index = number or if (last) LAST else 0
            return byteArrayOf(TYPE.toByte(), streamId.toByte()) + u16(index) + data
        }

        public companion object {
            /** The most data one fragment carries: a record body less the 4-byte header. */
            public const val DATA_MAX: Int = 1_020

            /** The largest fragment number; bit 15 of the index marks the last fragment. */
            public const val NUMBER_MAX: Int = 0x7FFF
            internal const val TYPE = 0x01

            /** The fragment header: type, `stream_id` and `index`. */
            public const val HEADER: Int = 4
            private const val STREAM = 1
            private const val INDEX = 2
            private const val LAST = 0x8000
            private val DATA_LENGTH = 1..DATA_MAX

            /** [frame] as stream [streamId]: pieces of [dataMax] bytes, numbered from 0, the last marked. */
            public fun split(
                streamId: Int,
                frame: ByteArray,
                dataMax: Int,
            ): List<Fragment> {
                val count = (frame.size + dataMax - 1) / dataMax
                return List(count) {
                    val end = minOf(frame.size, (it + 1) * dataMax)
                    Fragment(streamId, it, it == count - 1, frame.copyOfRange(it * dataMax, end))
                }
            }

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size < HEADER -> {
                        Decoded.Invalid(DecodeError.TRUNCATED)
                    }

                    bytes.size - HEADER !in DATA_LENGTH -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        val index = bytes.u16(INDEX)
                        val data = bytes.copyOfRange(HEADER, bytes.size)
                        Decoded.Valid(Fragment(bytes.u8(STREAM), index and NUMBER_MAX, index >= LAST, data))
                    }
                }
        }
    }

    /**
     * `HANDSHAKE` (spec "HANDSHAKE frame"): the selected protocol [version], the Noise message's
     * [step] (1 to 3) and the [noiseMessage] itself, 1 to [NOISE_MESSAGE_MAX] bytes.
     */
    public class Handshake(
        public val version: Int,
        public val step: Int,
        public val noiseMessage: ByteArray,
    ) : LinkFrame {
        init {
            require(version in 1..BYTE_MASK) { "version must be in 1..255, not $version" }
            require(step in STEPS) { "step must be in 1..3, not $step" }
            require(noiseMessage.size in MESSAGE_LENGTH) { "noise_message must be 1 to $NOISE_MESSAGE_MAX bytes" }
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), version.toByte(), step.toByte()) + noiseMessage

        public companion object {
            /** The largest Noise message a `HANDSHAKE` frame carries, in bytes (D-20). */
            public const val NOISE_MESSAGE_MAX: Int = 4_096

            /** The largest `HANDSHAKE` frame: the 3-byte header and [NOISE_MESSAGE_MAX]. */
            public const val FRAME_MAX: Int = 3 + NOISE_MESSAGE_MAX
            internal const val TYPE = 0x02
            private const val VERSION = 1
            private const val STEP = 2
            private const val MESSAGE = 3
            private val STEPS = 1..3
            private val MESSAGE_LENGTH = 1..NOISE_MESSAGE_MAX

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size < MESSAGE -> {
                        Decoded.Invalid(DecodeError.TRUNCATED)
                    }

                    bytes.u8(VERSION) == 0 || bytes.u8(STEP) !in STEPS || bytes.size - MESSAGE !in MESSAGE_LENGTH -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        val message = bytes.copyOfRange(MESSAGE, bytes.size)
                        Decoded.Valid(Handshake(bytes.u8(VERSION), bytes.u8(STEP), message))
                    }
                }
        }
    }

    /**
     * `PLAIN_HELLO` (spec "Unsecured link"): the sender's selected protocol [version], its mesh
     * identifier digest and its claimed NodeId. The profile byte is always `unsecured`.
     */
    public class PlainHello(
        public val version: Int,
        public val meshIdDigest: ByteArray,
        public val nodeId: ByteArray,
    ) : LinkFrame {
        init {
            require(version in 1..BYTE_MASK) { "version must be in 1..255, not $version" }
            require(meshIdDigest.size == ID_LENGTH) { "mesh_id_digest must be $ID_LENGTH bytes" }
            require(nodeId.size == ID_LENGTH) { "node_id must be $ID_LENGTH bytes" }
        }

        override fun encode(): ByteArray =
            byteArrayOf(TYPE.toByte(), version.toByte(), WireProfile.UNSECURED.code.toByte()) + meshIdDigest + nodeId

        internal companion object {
            const val TYPE = 0x03
            const val LENGTH = 67
            const val ID_LENGTH = 32
            private const val VERSION = 1
            private const val PROFILE = 2
            private const val DIGEST = 3

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size != LENGTH -> {
                        Decoded.Invalid(sizeError(bytes, LENGTH))
                    }

                    bytes.u8(VERSION) == 0 || bytes.u8(PROFILE) != WireProfile.UNSECURED.code -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        Decoded.Valid(
                            PlainHello(
                                bytes.u8(VERSION),
                                bytes.copyOfRange(DIGEST, DIGEST + ID_LENGTH),
                                bytes.copyOfRange(DIGEST + ID_LENGTH, LENGTH),
                            ),
                        )
                    }
                }
        }
    }

    /** `CLOSE` (spec "Close"): the sender disconnects next. [code] is kept as received. */
    public class Close(
        public val code: Int,
    ) : LinkFrame {
        public constructor(reason: CloseReason) : this(reason.code)

        init {
            require(code in 0..BYTE_MASK) { "reason must be a byte, not $code" }
        }

        /** The reason; an unknown code means [CloseReason.NORMAL]. */
        public val reason: CloseReason get() = CloseReason.of(code)

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), code.toByte())

        internal companion object {
            const val TYPE = 0x04
            const val LENGTH = 2

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                if (bytes.size == LENGTH) {
                    Decoded.Valid(Close(bytes.u8(1)))
                } else {
                    Decoded.Invalid(sizeError(bytes, LENGTH))
                }
        }
    }

    /**
     * `REKEY` (spec "Rekey", D-21): the sender called Noise `Rekey()` on its sending cipher state
     * after this record, so the receiver rekeys its receiving one after processing it.
     */
    public data object Rekey : LinkFrame {
        internal const val TYPE = 0x05
        private const val LENGTH = 1

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte())

        internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
            if (bytes.size == LENGTH) Decoded.Valid(Rekey) else Decoded.Invalid(DecodeError.TRAILING_BYTES)
    }

    /**
     * `CATCH_UP` (spec "Catch-up", D-25): what the sender offers a new link, each list oldest first: the
     * `message_id`s of the broadcasts it received in the last `catch-up-window` ([offered]), the unicasts it
     * [held] and its [tombstones].
     */
    public class CatchUp(
        public val offered: List<ByteArray>,
        public val held: List<Held>,
        public val tombstones: List<Tombstone>,
    ) : LinkFrame {
        init {
            require(offered.size <= OFFERED_MAX) { "a CATCH_UP offers $OFFERED_MAX IDs at most, not ${offered.size}" }
            require(offered.all { it.size == MESSAGE_ID }) { "every message ID must be $MESSAGE_ID bytes" }
            require(held.size <= HELD_MAX) { "a CATCH_UP lists $HELD_MAX held unicasts at most, not ${held.size}" }
            require(tombstones.size <= TOMBSTONES_MAX) {
                "a CATCH_UP lists $TOMBSTONES_MAX tombstones at most, not ${tombstones.size}"
            }
        }

        override fun encode(): ByteArray =
            byteArrayOf(TYPE.toByte()) +
                section(offered) +
                section(held.map { it.messageId + it.destinationTag }) +
                section(tombstones.map { it.messageId + it.k })

        /** A unicast the sender holds: its [messageId] and [destinationTag]. */
        public class Held(
            public val messageId: ByteArray,
            public val destinationTag: ByteArray,
        ) {
            init {
                require(messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
                require(destinationTag.size == TAG) { "destination_tag must be $TAG bytes" }
            }
        }

        public companion object {
            /** `catch-up-offered-max`: the most broadcasts one `CATCH_UP` offers. */
            public const val OFFERED_MAX: Int = 128

            /** `catch-up-held-max`: the most held unicasts one `CATCH_UP` lists. */
            public const val HELD_MAX: Int = 100

            /** `catch-up-tombstones-max`: the most tombstones one `CATCH_UP` lists. */
            public const val TOMBSTONES_MAX: Int = 128

            internal const val TYPE = 0x0A
            private const val TAG = 8
            private const val K = 16
            private const val COUNT = 2

            // Each list's maximum and entry width, in frame order.
            private val SECTIONS =
                listOf(OFFERED_MAX to MESSAGE_ID, HELD_MAX to MESSAGE_ID + TAG, TOMBSTONES_MAX to MESSAGE_ID + K)

            /**
             * A `CATCH_UP` of the newest entries of each list, which run oldest first: a list over its
             * maximum loses its oldest.
             */
            public fun newest(
                offered: List<ByteArray>,
                held: List<Held>,
                tombstones: List<Tombstone>,
            ): CatchUp =
                CatchUp(
                    offered.takeLast(OFFERED_MAX),
                    held.takeLast(HELD_MAX),
                    tombstones.takeLast(TOMBSTONES_MAX),
                )

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> {
                val lists = mutableListOf<List<ByteArray>>()
                var offset = 1
                var error: DecodeError? = null
                for ((max, width) in SECTIONS) {
                    val count = if (bytes.size < offset + COUNT) -1 else bytes.u16(offset)
                    val first = offset + COUNT
                    offset = first + count * width
                    error =
                        when {
                            count < 0 || (count <= max && bytes.size < offset) -> DecodeError.TRUNCATED
                            count > max -> DecodeError.OUT_OF_RANGE
                            else -> null
                        }
                    if (error != null) break
                    lists += List(count) { bytes.copyOfRange(first + it * width, first + (it + 1) * width) }
                }
                return when {
                    error != null -> Decoded.Invalid(error)
                    bytes.size != offset -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
                    else -> Decoded.Valid(frame(lists))
                }
            }

            private fun frame(lists: List<List<ByteArray>>): CatchUp {
                val (offered, held, tombstones) = lists
                return CatchUp(
                    offered,
                    held.map { Held(it.copyOf(MESSAGE_ID), it.copyOfRange(MESSAGE_ID, it.size)) },
                    tombstones.map { Tombstone(it.copyOf(MESSAGE_ID), it.copyOfRange(MESSAGE_ID, it.size)) },
                )
            }

            private fun section(entries: List<ByteArray>): ByteArray =
                entries.fold(u16(entries.size)) { bytes, entry ->
                    bytes + entry
                }
        }
    }

    /**
     * `IHAVE` (spec "Plumtree", D-26): 1 to [IDS_MAX] `message_id`s of broadcasts the sender holds,
     * announced on a lazy link.
     */
    public class Ihave(
        public val messageIds: List<ByteArray>,
    ) : LinkFrame {
        init {
            require(messageIds.size in COUNTS) { "an IHAVE carries 1 to $IDS_MAX message IDs, not ${messageIds.size}" }
            require(messageIds.all { it.size == MESSAGE_ID }) { "every message ID must be $MESSAGE_ID bytes" }
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + entries(messageIds)

        public companion object {
            /** The most message IDs one `IHAVE` carries (spec `ihave-ids-max`). */
            public const val IDS_MAX: Int = 32
            internal const val TYPE = 0x0B
            private val COUNTS = 1..IDS_MAX

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> = entries(bytes, 1, COUNTS, MESSAGE_ID, ::Ihave)
        }
    }

    /** `GRAFT` (spec "Plumtree", D-26): asks the peer for the frame of [messageId] and makes the link eager. */
    public class Graft(
        public val messageId: ByteArray,
    ) : LinkFrame {
        init {
            require(messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + messageId

        internal companion object {
            const val TYPE = 0x0C
            private const val LENGTH = 1 + MESSAGE_ID

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                if (bytes.size == LENGTH) {
                    Decoded.Valid(Graft(bytes.copyOfRange(1, LENGTH)))
                } else {
                    Decoded.Invalid(sizeError(bytes, LENGTH))
                }
        }
    }

    /** `PRUNE` (spec "Plumtree", D-26): the link becomes lazy in both directions. */
    public data object Prune : LinkFrame {
        internal const val TYPE = 0x0D
        private const val LENGTH = 1

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte())

        internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
            if (bytes.size == LENGTH) Decoded.Valid(Prune) else Decoded.Invalid(DecodeError.TRAILING_BYTES)
    }

    /**
     * `BEARER_SWITCH` (spec "Bearer switch", D-43): the sender's last record on GATT; its stream
     * continues on the link's L2CAP CoC channel with the next record.
     */
    public data object BearerSwitch : LinkFrame {
        internal const val TYPE = 0x0F
        private const val LENGTH = 1

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte())

        internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
            if (bytes.size == LENGTH) Decoded.Valid(BearerSwitch) else Decoded.Invalid(DecodeError.TRAILING_BYTES)
    }

    /** `BUSY` (spec "Busy"): the receiver refused stream [streamId]. [code] is kept as received. */
    public class Busy(
        public val streamId: Int,
        public val code: Int,
    ) : LinkFrame {
        public constructor(streamId: Int, reason: BusyReason) : this(streamId, reason.code)

        init {
            require(streamId in 0..BYTE_MASK) { "stream_id must be a byte, not $streamId" }
            require(code in 0..BYTE_MASK) { "reason must be a byte, not $code" }
        }

        /** The reason; an unknown code means [BusyReason.REASSEMBLY_SLOTS]. */
        public val reason: BusyReason get() = BusyReason.of(code)

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), streamId.toByte(), code.toByte())

        internal companion object {
            const val TYPE = 0x06
            const val LENGTH = 3

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                if (bytes.size == LENGTH) {
                    Decoded.Valid(Busy(bytes.u8(1), bytes.u8(2)))
                } else {
                    Decoded.Invalid(sizeError(bytes, LENGTH))
                }
        }
    }

    /**
     * `NEIGHBOR_LIST` (spec "Neighbor list", D-24): the sender's current advertisement identifier
     * [ownId], the one it advertises after its next rotation [nextId], and the current identifiers
     * of up to [NEIGHBORS_MAX] link peers. Each identifier is an advertisement's resolvable tag.
     */
    public class NeighborList(
        public val ownId: ByteArray,
        public val nextId: ByteArray,
        public val neighbors: List<ByteArray>,
    ) : LinkFrame {
        init {
            require(ownId.size == ID && nextId.size == ID) { "own_id and next_id must be $ID bytes" }
            require(neighbors.size <= NEIGHBORS_MAX) { "at most $NEIGHBORS_MAX neighbors, not ${neighbors.size}" }
            requireIds(neighbors)
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + ownId + nextId + entries(neighbors)

        public companion object {
            /** The most neighbor identifiers one list carries (spec `neighbor-list-max`). */
            public const val NEIGHBORS_MAX: Int = 32
            internal const val TYPE = 0x07
            private const val OWN = 1
            private const val NEXT = OWN + ID
            private const val COUNT = NEXT + ID

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                entries(bytes, COUNT, 0..NEIGHBORS_MAX) {
                    NeighborList(bytes.copyOfRange(OWN, NEXT), bytes.copyOfRange(NEXT, COUNT), it)
                }
        }
    }

    /**
     * `SHUFFLE` (spec "Shuffle"): 1 to [ENTRIES_MAX] advertisement identifiers from the sender's
     * passive view. The receiver answers with [ShuffleReply] on the same link.
     */
    public class Shuffle(
        public val entries: List<ByteArray>,
    ) : LinkFrame {
        init {
            require(entries.size in COUNTS) { "a shuffle carries 1 to $ENTRIES_MAX entries, not ${entries.size}" }
            requireIds(entries)
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + entries(entries)

        public companion object {
            /** The most identifiers a shuffle or its reply carries (spec `shuffle-entries-max`). */
            public const val ENTRIES_MAX: Int = 5
            internal const val TYPE = 0x08
            private val COUNTS = 1..ENTRIES_MAX

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> = entries(bytes, 1, COUNTS) { Shuffle(it) }
        }
    }

    /** `SHUFFLE_REPLY` (spec "Shuffle"): 0 to [Shuffle.ENTRIES_MAX] identifiers from the replier's passive view. */
    public class ShuffleReply(
        public val entries: List<ByteArray>,
    ) : LinkFrame {
        init {
            require(entries.size <= Shuffle.ENTRIES_MAX) { "a reply carries at most ${Shuffle.ENTRIES_MAX} entries" }
            requireIds(entries)
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + entries(entries)

        internal companion object {
            const val TYPE = 0x09
            private val COUNTS = 0..Shuffle.ENTRIES_MAX

            fun decode(bytes: ByteArray): Decoded<LinkFrame> = entries(bytes, 1, COUNTS) { ShuffleReply(it) }
        }
    }

    /**
     * `RESOLVING_KEY_UPDATE` (spec "Resolving key update", D-41): the sender's next resolving key
     * [next] with the Unix second it expires.
     */
    public class ResolvingKeyUpdate(
        public val next: ResolvingKey,
    ) : LinkFrame {
        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + next.encode()

        internal companion object {
            const val TYPE = 0x0E

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when (val key = ResolvingKey.decode(bytes.copyOfRange(1, bytes.size))) {
                    is Decoded.Valid -> Decoded.Valid(ResolvingKeyUpdate(key.value))
                    is Decoded.Invalid -> key
                }
        }
    }

    /**
     * `CREDIT` (spec "Flow control", D-47): the central grants the peripheral [credits] more GATT
     * notifications.
     */
    public class Credit(
        public val credits: Int,
    ) : LinkFrame {
        init {
            require(credits in 1..U16_MAX) { "credits must be 1 to $U16_MAX, not $credits" }
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + u16(credits)

        internal companion object {
            const val TYPE = 0x10
            const val LENGTH = 3
            private const val U16_MAX = 0xFFFF

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size != LENGTH -> Decoded.Invalid(sizeError(bytes, LENGTH))
                    bytes.u16(1) == 0 -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    else -> Decoded.Valid(Credit(bytes.u16(1)))
                }
        }
    }

    public companion object {
        private const val RESERVED_FROM = 0xC0

        // Each link-local frame type's decoder.
        private val decoders: Map<Int, (ByteArray) -> Decoded<LinkFrame>> =
            mapOf(
                Fragment.TYPE to { Fragment.decode(it) },
                Handshake.TYPE to { Handshake.decode(it) },
                PlainHello.TYPE to { PlainHello.decode(it) },
                Close.TYPE to { Close.decode(it) },
                Rekey.TYPE to { Rekey.decode(it) },
                Busy.TYPE to { Busy.decode(it) },
                NeighborList.TYPE to { NeighborList.decode(it) },
                Shuffle.TYPE to { Shuffle.decode(it) },
                ShuffleReply.TYPE to { ShuffleReply.decode(it) },
                CatchUp.TYPE to { CatchUp.decode(it) },
                Ihave.TYPE to { Ihave.decode(it) },
                Graft.TYPE to { Graft.decode(it) },
                Prune.TYPE to { Prune.decode(it) },
                ResolvingKeyUpdate.TYPE to { ResolvingKeyUpdate.decode(it) },
                BearerSwitch.TYPE to { BearerSwitch.decode(it) },
                Credit.TYPE to { Credit.decode(it) },
            )

        /** Decodes one frame; never throws. */
        public fun decode(bytes: ByteArray): Decoded<LinkFrame> {
            val type = if (bytes.isEmpty()) -1 else bytes.u8(0)
            val decoder = decoders[type]
            return when {
                type < 0 -> Decoded.Invalid(DecodeError.TRUNCATED)
                type == 0 || type >= RESERVED_FROM -> Decoded.Invalid(DecodeError.RESERVED_TYPE)
                decoder == null -> Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                else -> decoder(bytes)
            }
        }

        // An advertisement identifier: the resolvable tag, payload bytes 5–12.
        private const val ID = Advertisement.RESOLVABLE_TAG_LENGTH

        private fun requireIds(ids: List<ByteArray>) {
            require(ids.all { it.size == ID }) { "every identifier must be $ID bytes" }
        }

        // A count byte and its identifiers.
        private fun entries(ids: List<ByteArray>): ByteArray {
            val count = byteArrayOf(ids.size.toByte())
            return ids.fold(count) { bytes, id -> bytes + id }
        }

        // A routing header's `message_id`.
        private const val MESSAGE_ID = 12

        // The [width]-byte identifiers after the count byte at [count], if their number is in [counts] and fills
        // the frame.
        private fun entries(
            bytes: ByteArray,
            count: Int,
            counts: IntRange,
            width: Int = ID,
            frame: (List<ByteArray>) -> LinkFrame,
        ): Decoded<LinkFrame> {
            if (bytes.size <= count) return Decoded.Invalid(DecodeError.TRUNCATED)
            val number = bytes.u8(count)
            val first = count + 1
            val length = first + number * width
            return when {
                number !in counts -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                bytes.size != length -> {
                    Decoded.Invalid(sizeError(bytes, length))
                }

                else -> {
                    val entries = List(number) { bytes.copyOfRange(first + it * width, first + (it + 1) * width) }
                    Decoded.Valid(frame(entries))
                }
            }
        }

        private fun sizeError(
            bytes: ByteArray,
            length: Int,
        ): DecodeError = if (bytes.size < length) DecodeError.TRUNCATED else DecodeError.TRAILING_BYTES
    }
}

/**
 * Why a link closed (spec "Close"). Reasons are diagnostic, except that [DUPLICATE_LINK] keeps the
 * peer. The registry's codes run from 0x00 without gaps, so entries stay in code order.
 */
public enum class CloseReason {
    NORMAL,
    DUPLICATE_LINK,
    PROTOCOL_ERROR,
    HANDSHAKE_FAILED,
    UNSUPPORTED_VERSION,
    WRONG_MESH,
    RESOURCE_LIMIT,
    SHUTTING_DOWN,
    ;

    /** The one-byte wire code. */
    public val code: Int get() = ordinal

    public companion object {
        /** The reason with [code], or [NORMAL] for an unknown code. */
        public fun of(code: Int): CloseReason = entries.getOrElse(code) { NORMAL }
    }
}

/** Why a receiver refused a stream (spec "Busy"). Codes start at 0x01 and run without gaps. */
public enum class BusyReason {
    /** The link already reassembles its maximum number of streams, or its one large stream. */
    REASSEMBLY_SLOTS,

    /** The node's shared budget for large streams is spent. */
    LARGE_BUDGET,
    ;

    /** The one-byte wire code. */
    public val code: Int get() = ordinal + 1

    public companion object {
        /** The reason with [code], or [REASSEMBLY_SLOTS] for an unknown code. */
        public fun of(code: Int): BusyReason = entries.getOrElse(code - 1) { REASSEMBLY_SLOTS }
    }
}
