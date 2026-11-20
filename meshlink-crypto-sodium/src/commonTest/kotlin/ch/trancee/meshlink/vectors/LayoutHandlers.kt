package ch.trancee.meshlink.vectors

import ch.trancee.meshlink.crypto.sodium.hex
import ch.trancee.meshlink.internal.Advertisement
import ch.trancee.meshlink.internal.CapabilityRecord
import ch.trancee.meshlink.internal.Container
import ch.trancee.meshlink.internal.Content
import ch.trancee.meshlink.internal.Decoded
import ch.trancee.meshlink.internal.HandshakePayload
import ch.trancee.meshlink.internal.IdentityCertificate
import ch.trancee.meshlink.internal.LinkFrame
import ch.trancee.meshlink.internal.LinkRecord
import ch.trancee.meshlink.internal.MessageContent
import ch.trancee.meshlink.internal.MessageFrame
import ch.trancee.meshlink.internal.ReceiptContent
import ch.trancee.meshlink.internal.RecordReader
import ch.trancee.meshlink.internal.UnicastFrame
import ch.trancee.meshlink.internal.WireProfile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * A handler for a layout file (`encoded` with `decoded` or `error`). A test with `decoded` must
 * decode to those fields; one that [roundTrips], by default a `valid` one, must also re-encode to
 * `encoded`, and every decoder input derived from it ([mutationInputs]) that decodes must re-encode
 * to itself. A test with `error` must fail with that error code.
 */
private fun <T> layout(
    decode: (VectorCase, ByteArray) -> Decoded<T>,
    encode: (VectorCase, T) -> ByteArray,
    fuzz: (ByteArray) -> Unit = {},
    roundTrips: (VectorCase) -> Boolean = { it.result == "valid" },
    assertFields: (Json, T) -> Unit,
): Handler =
    Handler("encoded") { case ->
        val encoded = case.hex("encoded")
        val result = decode(case, encoded)
        if (case.test.has("decoded")) {
            if (result !is Decoded.Valid) fail("decoded to ${(result as Decoded.Invalid).error.code}")
            val value = result.value
            assertFields(case.test["decoded"], value)
            if (roundTrips(case)) {
                assertEquals(case.test["encoded"].string, encode(case, value).hex(), "re-encoded")
                mutationInputs(encoded).forEach { input ->
                    when (val mutated = decode(case, input)) {
                        is Decoded.Valid -> assertContentEquals(input, encode(case, mutated.value), input.hex())
                        is Decoded.Invalid -> Unit
                    }
                    fuzz(input)
                }
            }
        } else {
            assertEquals(case.test["error"].string, assertIs<Decoded.Invalid>(result).error.code)
        }
    }

private fun Json.code(): Int = string.removePrefix("0x").toInt(HEX)

internal val advertisement: Handler =
    layout({ _, bytes -> Advertisement.decode(bytes) }, { _, value -> value.encode() }) { decoded, value ->
        assertEquals(decoded["layout_version"].int, Advertisement.LAYOUT_VERSION, "layout_version")
        assertEquals(decoded["flags"].int, value.flags, "flags")
        assertEquals(decoded["mesh_tag"].string, value.meshTag.hex(), "mesh_tag")
        assertEquals(decoded["resolvable_tag"].string, value.resolvableTag.hex(), "resolvable_tag")
        assertEquals(decoded["random"].string, value.random.hex(), "random")
    }

internal val capability: Handler =
    layout({ _, bytes -> CapabilityRecord.decode(bytes) }, { _, value -> value.encode() }) { decoded, value ->
        assertEquals(decoded["versions"].list.map { it["version"].int }, value.versions, "versions")
        assertEquals(
            decoded["extensions"].list.map { it["type"].code() to it["value"].string },
            value.extensions.map { it.type to it.value.hex() },
            "extensions",
        )
    }

internal val certificate: Handler =
    layout({ _, bytes -> IdentityCertificate.decode(bytes) }, { _, value -> value.encode() }) { decoded, value ->
        assertEquals(decoded["version"].int, IdentityCertificate.VERSION, "version")
        assertEquals(decoded["profile"].code(), value.profile.code, "profile")
        assertEquals(decoded["ed25519_key"].string, value.ed25519Key.hex(), "ed25519_key")
        assertEquals(decoded["x25519_key"].string, value.x25519Key.hex(), "x25519_key")
        val xWingKey = if (decoded.has("xwing_key")) decoded["xwing_key"].string else null
        assertEquals(xWingKey, value.xWingKey?.hex(), "xwing_key")
        assertEquals(decoded["not_before"].uInt, value.notBefore, "not_before")
        assertEquals(decoded["not_after"].uInt, value.notAfter, "not_after")
        assertEquals(decoded["signature"].string, value.signature.hex(), "signature")
    }

internal val handshakePayload: Handler =
    layout({ _, bytes -> HandshakePayload.decode(bytes) }, { _, value -> value.encode() }) { decoded, value ->
        assertEquals(
            decoded["tlvs"].list.map { it["type"].code() to it["value"].string },
            value.tlvs.map { it.type to it.value.hex() },
            "tlvs",
        )
    }

/** Every decoder input also runs through a [RecordReader] in [READER_CHUNK]-byte writes. */
internal val record: Handler =
    layout(
        { _, bytes -> LinkRecord.decode(bytes) },
        { _, body -> LinkRecord.encode(body) },
        { input ->
            val reader = RecordReader()
            input.toList().chunked(READER_CHUNK).forEach { reader.read(it.toByteArray()) }
        },
    ) { decoded, body -> assertEquals(decoded["body"].string, body.hex(), "body") }

/** The link-local frames whose codec exists, and the frames it refuses by type. */
internal val linkFrame: Handler =
    layout({ _, bytes -> LinkFrame.decode(bytes) }, { _, frame -> frame.encode() }) { decoded, frame ->
        when (frame) {
            is LinkFrame.Handshake -> {
                assertEquals(decoded["type"].code(), HANDSHAKE, "type")
                assertEquals(decoded["version"].int, frame.version, "version")
                assertEquals(decoded["step"].int, frame.step, "step")
                assertEquals(decoded["noise_message"].string, frame.noiseMessage.hex(), "noise_message")
            }

            is LinkFrame.PlainHello -> {
                assertEquals(decoded["type"].code(), PLAIN_HELLO, "type")
                assertEquals(decoded["version"].int, frame.version, "version")
                assertEquals(decoded["profile"].int, WireProfile.UNSECURED.code, "profile")
                assertEquals(decoded["mesh_id_digest"].string, frame.meshIdDigest.hex(), "mesh_id_digest")
                assertEquals(decoded["node_id"].string, frame.nodeId.hex(), "node_id")
            }

            is LinkFrame.Close -> {
                assertEquals(decoded["type"].code(), CLOSE, "type")
                assertEquals(decoded["reason"].int, frame.code, "reason")
            }

            is LinkFrame.Fragment -> {
                assertEquals(decoded["type"].code(), FRAGMENT, "type")
                assertEquals(decoded["stream_id"].int, frame.streamId, "stream_id")
                assertEquals(decoded["index"].int, frame.number or if (frame.last) LAST_BIT else 0, "index")
                assertEquals(decoded["data"].string, frame.data.hex(), "data")
            }

            LinkFrame.Rekey -> {
                assertEquals(decoded["type"].code(), REKEY, "type")
            }

            LinkFrame.BearerSwitch -> {
                assertEquals(decoded["type"].code(), BEARER_SWITCH, "type")
            }

            is LinkFrame.Busy -> {
                assertEquals(decoded["type"].code(), BUSY, "type")
                assertEquals(decoded["stream_id"].int, frame.streamId, "stream_id")
                assertEquals(decoded["reason"].int, frame.code, "reason")
            }

            is LinkFrame.NeighborList -> {
                assertEquals(decoded["type"].code(), NEIGHBOR_LIST, "type")
                assertEquals(decoded["own_id"].string, frame.ownId.hex(), "own_id")
                assertEquals(decoded["next_id"].string, frame.nextId.hex(), "next_id")
                val neighbors = decoded["neighbors"].list.map { it["id"].string }
                assertEquals(neighbors, frame.neighbors.map { it.hex() }, "neighbors")
            }

            is LinkFrame.Shuffle -> {
                assertEquals(decoded["type"].code(), SHUFFLE, "type")
                assertEquals(decoded["entries"].list.map { it["id"].string }, frame.entries.map { it.hex() }, "entries")
            }

            is LinkFrame.ShuffleReply -> {
                assertEquals(decoded["type"].code(), SHUFFLE_REPLY, "type")
                assertEquals(decoded["entries"].list.map { it["id"].string }, frame.entries.map { it.hex() }, "entries")
            }

            is LinkFrame.ResolvingKeyUpdate -> {
                assertEquals(decoded["type"].code(), RESOLVING_KEY_UPDATE, "type")
                assertEquals(decoded["resolving_key"].string, frame.next.key.hex(), "resolving_key")
                assertEquals(decoded["not_after"].uInt, frame.next.notAfter, "not_after")
            }

            is LinkFrame.Credit -> {
                assertEquals(decoded["type"].code(), CREDIT, "type")
                assertEquals(decoded["credits"].int, frame.credits, "credits")
            }
        }
    }

internal val messageFrame: Handler =
    layout({ _, bytes -> MessageFrame.decode(bytes) }, { _, frame -> frame.encode() }) { decoded, frame ->
        assertEquals(decoded["type"].code(), MessageFrame.TYPE, "type")
        assertEquals(decoded["hop_limit"].int, frame.header.hopLimit, "hop_limit")
        assertEquals(decoded["message_id"].string, frame.header.messageId.hex(), "message_id")
        assertEquals(decoded["expiry"].uInt, frame.header.expiry, "expiry")
        assertEquals(decoded["destination_tag"].string, frame.header.destinationTag.hex(), "destination_tag")
        assertEquals(decoded["source_tag"].string, frame.header.sourceTag.hex(), "source_tag")
        assertEquals(decoded["commitment"].string, frame.commitment.hex(), "commitment")
        val container = decoded["container"]
        assertEquals(container["kind"].code(), frame.container.kind, "container.kind")
        when (val actual = frame.container) {
            is Container.IkInitiator -> {
                assertEquals(container["noise_message"].string, actual.noiseMessage.hex(), "container.noise_message")
            }

            is Container.Transport -> {
                assertEquals(container["nonce"].uLong, actual.nonce, "container.nonce")
                assertEquals(container["ciphertext"].string, actual.ciphertext.hex(), "container.ciphertext")
            }

            is Container.Unsecured -> {
                assertEquals(container["sender_node_id"].string, actual.senderNodeId.hex(), "container.sender_node_id")
                assertEquals(container["plaintext"].string, actual.plaintext.hex(), "container.plaintext")
            }
        }
    }

/** An unknown unicast type: `acceptable`, relayed by its header, so it round-trips and is mutated too. */
internal val unicastFrame: Handler =
    layout(
        { _, bytes -> UnicastFrame.decode(bytes) },
        { _, frame -> frame.encode() },
        roundTrips = { true },
    ) { decoded, frame ->
        assertEquals(decoded["type"].code(), frame.type, "type")
        assertEquals(decoded["hop_limit"].int, frame.header.hopLimit, "hop_limit")
        assertEquals(decoded["message_id"].string, frame.header.messageId.hex(), "message_id")
        assertEquals(decoded["expiry"].uInt, frame.header.expiry, "expiry")
        assertEquals(decoded["destination_tag"].string, frame.header.destinationTag.hex(), "destination_tag")
        assertEquals(decoded["source_tag"].string, frame.header.sourceTag.hex(), "source_tag")
        assertEquals(decoded["body"].string, frame.body.hex(), "body")
    }

/** `inner-plaintext.json`: [message] for the `inner-message` groups, else receipts; `secured` per group. */
internal fun innerContent(message: Boolean): Handler =
    layout<Content>(
        { case, bytes ->
            val secured = case.group["secured"].bool
            if (message) MessageContent.decode(bytes, secured) else ReceiptContent.decode(bytes, secured)
        },
        { case, content -> content.encode(case.group["secured"].bool) },
    ) { decoded, content ->
        assertEquals(decoded["message_id"].string, content.messageId.hex(), "message_id")
        assertEquals(decoded["expiry"].uInt, content.expiry, "expiry")
        assertEquals(
            decoded["extensions"].list.map { it["type"].code() to it["value"].string },
            content.extensions.map { it.type to it.value.hex() },
            "extensions",
        )
        if (content is MessageContent) {
            assertEquals(decoded["k"].string, content.k.hex(), "k")
            assertEquals(decoded["payload"].string, content.payload.hex(), "payload")
        }
    }

private const val HEX = 16
private const val READER_CHUNK = 5
private const val FRAGMENT = 0x01
private const val HANDSHAKE = 0x02
private const val PLAIN_HELLO = 0x03
private const val CLOSE = 0x04
private const val REKEY = 0x05
private const val BUSY = 0x06
private const val NEIGHBOR_LIST = 0x07
private const val SHUFFLE = 0x08
private const val SHUFFLE_REPLY = 0x09
private const val RESOLVING_KEY_UPDATE = 0x0E
private const val BEARER_SWITCH = 0x0F
private const val CREDIT = 0x10
private const val LAST_BIT = 0x8000
