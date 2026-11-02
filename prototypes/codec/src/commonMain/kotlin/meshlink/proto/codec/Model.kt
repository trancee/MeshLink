package meshlink.proto.codec

// Placeholder frames for the codec prototype. Not the MeshLink spec: see Layout.md.

sealed interface DecodeResult

enum class DecodeError : DecodeResult {
    TRUNCATED,
    TRAILING_BYTES,
    UNSUPPORTED_VERSION,
    UNKNOWN_TYPE,
    OUT_OF_RANGE,
    RESERVED_BITS_SET,
}

const val VERSION = 1
const val MIN_LIFETIME = 120
const val MAX_LIFETIME = 4095
const val MAX_PAYLOAD = 16383
const val MAX_FRAGMENT_INDEX = 1023
const val MAX_OFFSET = 16383
const val MAX_MESSAGE = 16384
const val MAX_IHAVE = 32
const val MAX_DIGEST = 512
const val MAX_WINDOW = 63
const val MAX_RECEIPT_STATUS = 4
const val MAX_DENSITY = 2

object FrameType {
    const val UNICAST = 1
    const val BROADCAST = 2
    const val FRAGMENT = 3
    const val RECEIPT = 4
    const val IHAVE = 5
    const val GRAFT = 6
    const val DIGEST = 7
    const val HANDSHAKE = 8
}

object UnicastFlags {
    const val ACK_REQUESTED = 1
    const val E2E = 2
    const val PRIORITY = 4
    const val EXTENSION = 8
    const val ALL = 0xF
}

object BroadcastFlags {
    const val SIGNED = 1
    const val PRIORITY = 2
    const val EXTENSION = 4
    const val ALL = 0x7
}

/**
 * Frames hold byte strings that do not fit a Long (signature, key) as arrays, and the
 * extension and payload as ranges of [body] (zero copy on decode: [body] is the input).
 * Equality for tests and fuzzing is by [describe], which renders content, not identity.
 */
sealed class Frame : DecodeResult {
    abstract fun describe(): String

    final override fun toString(): String = describe()
}

class Unicast(
    val flags: Int,
    val hopLimit: Int,
    val lifetime: Int,
    val source: Long,
    val destination: Long,
    val idLo: Long,
    val idHi: Int,
    val body: ByteArray,
    val extOffset: Int,
    val extLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) : Frame() {
    override fun describe() =
        "Unicast(f=$flags,h=$hopLimit,l=$lifetime,s=$source,d=$destination,id=$idLo/$idHi," +
            "ext=${hex(body, extOffset, extLength)},p=${hex(body, payloadOffset, payloadLength)})"
}

class Broadcast(
    val flags: Int,
    val hopLimit: Int,
    val lifetime: Int,
    val origin: Long,
    val idLo: Long,
    val idHi: Int,
    val signature: ByteArray?,
    val body: ByteArray,
    val extOffset: Int,
    val extLength: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) : Frame() {
    override fun describe() =
        "Broadcast(f=$flags,h=$hopLimit,l=$lifetime,o=$origin,id=$idLo/$idHi," +
            "sig=${signature?.let { hex(it, 0, it.size) }},ext=${hex(body, extOffset, extLength)}," +
            "p=${hex(body, payloadOffset, payloadLength)})"
}

class Fragment(
    val index: Int,
    val last: Boolean,
    val offset: Int,
    val idLo: Long,
    val idHi: Int,
    val body: ByteArray,
    val dataOffset: Int,
    val dataLength: Int,
) : Frame() {
    override fun describe() =
        "Fragment(i=$index,last=$last,o=$offset,id=$idLo/$idHi,d=${hex(body, dataOffset, dataLength)})"
}

class Receipt(val status: Int, val hops: Int, val idLo: Long, val idHi: Int) : Frame() {
    override fun describe() = "Receipt(s=$status,h=$hops,id=$idLo/$idHi)"
}

class IHave(val round: Int, val idLo: LongArray, val idHi: IntArray) : Frame() {
    override fun describe() = "IHave(r=$round,lo=${idLo.contentToString()},hi=${idHi.contentToString()})"
}

class Graft(val round: Int, val idLo: Long, val idHi: Int) : Frame() {
    override fun describe() = "Graft(r=$round,id=$idLo/$idHi)"
}

class Digest(val moreFollows: Boolean, val window: Int, val idLo: LongArray, val idHi: IntArray) : Frame() {
    override fun describe() =
        "Digest(m=$moreFollows,w=$window,lo=${idLo.contentToString()},hi=${idHi.contentToString()})"
}

class Handshake(val suite: Int, val capabilities: Int, val keyDay: Int, val resolvingKey: ByteArray?) : Frame() {
    override fun describe() =
        "Handshake(s=$suite,c=$capabilities,d=$keyDay,k=${resolvingKey?.let { hex(it, 0, it.size) }})"
}

/** The 16 B carried in the rotating 128-bit service UUID. A has no room for [random]. */
class Advert(
    val epochLow: Int,
    val capabilities: Int,
    val density: Int,
    val random: Int,
    val meshTag: Int,
    val resolvableTag: Long,
) : Frame() {
    override fun describe() =
        "Advert(e=$epochLow,c=$capabilities,d=$density,r=$random,m=$meshTag,t=$resolvableTag)"
}

fun hex(b: ByteArray, off: Int, len: Int): String {
    val sb = StringBuilder(len * 2)
    for (i in off until off + len) {
        val v = b[i].toInt() and 0xFF
        sb.append(HEX[v ushr 4]).append(HEX[v and 0xF])
    }
    return sb.toString()
}

private const val HEX = "0123456789abcdef"

/** Caller-contract checks shared by every encoder: the value ranges of Layout.md. */
fun Frame.validate() {
    when (this) {
        is Unicast -> {
            require(flags in 0..UnicastFlags.ALL && hopLimit in 0..255 && lifetime in MIN_LIFETIME..MAX_LIFETIME)
            require(payloadLength in 0..MAX_PAYLOAD && extLength in 0..255)
            require((flags and UnicastFlags.EXTENSION != 0) || extLength == 0)
        }
        is Broadcast -> {
            require(flags in 0..BroadcastFlags.ALL && hopLimit in 0..255 && lifetime in MIN_LIFETIME..MAX_LIFETIME)
            require(payloadLength in 0..MAX_PAYLOAD && extLength in 0..255)
            require((flags and BroadcastFlags.EXTENSION != 0) || extLength == 0)
            require((flags and BroadcastFlags.SIGNED != 0) == (signature != null) && (signature == null || signature.size == 64))
        }
        is Fragment -> require(
            index in 0..MAX_FRAGMENT_INDEX && offset in 0..MAX_OFFSET && dataLength >= 1 &&
                offset + dataLength <= MAX_MESSAGE,
        )
        is Receipt -> require(status in 0..MAX_RECEIPT_STATUS && hops in 0..255)
        is IHave -> require(idLo.size in 1..MAX_IHAVE && idHi.size == idLo.size && round in 0..255)
        is Graft -> require(round in 0..255)
        is Digest -> require(idLo.size in 0..MAX_DIGEST && idHi.size == idLo.size && window in 1..MAX_WINDOW)
        is Handshake -> require(
            suite in 0..15 && capabilities in 0..0xFFF && keyDay in 0..0xFFFF &&
                (resolvingKey == null || resolvingKey.size == 32),
        )
        is Advert -> require(
            epochLow in 0..15 && capabilities in 0..0x1F && density in 0..MAX_DENSITY && random in 0..0x7FFFF,
        )
    }
}

/** Little-endian byte helpers shared by the hand-written codecs A and B. */
fun getLE(b: ByteArray, off: Int, n: Int): Long {
    var v = 0L
    for (i in 0 until n) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
    return v
}

fun putLE(b: ByteArray, off: Int, n: Int, v: Long) {
    for (i in 0 until n) b[off + i] = (v ushr (8 * i)).toByte()
}

interface Codec {
    val name: String

    /** Encodes [frame] into [out] (caller-sized) and returns the length. */
    fun encode(frame: Frame, out: ByteArray): Int

    /** Decodes the first [len] bytes of [buf]: a typed frame or a [DecodeError], never an exception. */
    fun decode(buf: ByteArray, len: Int): DecodeResult

    fun decodeAdvert(buf: ByteArray, len: Int): DecodeResult
}

const val ADVERT_SIZE = 16
