package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.Flow

/**
 * Everything the protocol core asks of a Bluetooth LE radio (ADR 0017 decision 1; ADR 0009 GATT
 * bearer). A platform adapter implements it with no protocol logic of its own; `VirtualAir` in
 * `:meshlink-testing` implements it for many nodes in one process.
 *
 * It covers the GATT bearer as `docs/spec/link.md` "Roles and bearers" describes it: advertise
 * and scan for the MESH service, connect as central, serve and read the capability characteristic,
 * and an ordered byte stream per link. Every operation reports failure as a [RadioResult], never as
 * an exception.
 */
public interface RadioPort {
    /** What the radio observed, in order: sightings, links that peers opened to it, and disconnects. */
    public val events: Flow<RadioEvent>

    /**
     * Advertises the MESH service with [payload] as the rotating 128-bit service UUID, replacing
     * any payload advertised before.
     *
     * @param payload exactly [ADVERTISEMENT_LENGTH] bytes.
     */
    public fun startAdvertising(payload: ByteArray): RadioResult<Unit>

    /** Stops advertising; does nothing if the radio is not advertising. */
    public fun stopAdvertising()

    /** Starts reporting [RadioEvent.Sighted] for every MESH advertisement in range. */
    public fun startScanning(): RadioResult<Unit>

    /** Stops scanning; does nothing if the radio is not scanning. */
    public fun stopScanning()

    /**
     * Serves [record] as the capability characteristic value to every central that connects.
     *
     * @param record at most [CAPABILITY_RECORD_MAX] bytes.
     */
    public fun serveCapability(record: ByteArray): RadioResult<Unit>

    /** Connects to [peer] as central and negotiates the ATT MTU. */
    public suspend fun connect(peer: PeerHandle): RadioResult<CentralLink>

    public companion object {
        /** The advertisement payload length in bytes (spec `advertisement-length`). */
        public const val ADVERTISEMENT_LENGTH: Int = 16

        /** The largest capability record in bytes (spec `capability-record-max`). */
        public const val CAPABILITY_RECORD_MAX: Int = 512

        /** The ATT header bytes in a write without response or a notification. */
        public const val ATT_HEADER: Int = 3
    }
}

/**
 * A peer the radio can reach, as the platform identifies it. It is opaque: the core compares
 * handles for equality and passes them back to [RadioPort.connect], nothing else.
 */
public interface PeerHandle

/** An advertisement the radio received: its 16-byte [payload], who sent it, and the signal strength. */
public class Sighting(
    public val payload: ByteArray,
    public val peer: PeerHandle,
    public val rssi: Int,
)

/** Something a [RadioPort] observed. */
public sealed interface RadioEvent {
    /** A MESH advertisement arrived while scanning. */
    public class Sighted(
        public val sighting: Sighting,
    ) : RadioEvent

    /** A central opened [link] to this radio, which is its peripheral. */
    public class Connected(
        public val link: RadioLink,
    ) : RadioEvent

    /** [link] closed, whichever side or the air closed it. Its [RadioLink.incoming] has completed. */
    public class Disconnected(
        public val link: RadioLink,
    ) : RadioEvent
}

/**
 * One GATT link and its ordered byte stream in each direction (spec: the central writes without
 * response, the peripheral notifies).
 */
public interface RadioLink {
    /** The other end of the link. */
    public val peer: PeerHandle

    /** The negotiated ATT MTU; each chunk of the stream carries at most `attMtu - 3` bytes. */
    public val attMtu: Int

    /** The peer's stream, in order, one chunk per write or notification. Completes on disconnect. */
    public val incoming: Flow<ByteArray>

    /** Sends [bytes] as the next bytes of this side's stream, chunked by [attMtu]. */
    public suspend fun write(bytes: ByteArray): RadioResult<Unit>

    /** Closes the link; both sides then get [RadioEvent.Disconnected]. Does nothing if closed. */
    public fun disconnect()
}

/** A link this radio opened as central, which can also read the peer's capability characteristic. */
public interface CentralLink : RadioLink {
    /** The value the peripheral serves through [RadioPort.serveCapability]. */
    public suspend fun readCapability(): RadioResult<ByteArray>
}

/** The outcome of a [RadioPort] operation: [Ok], or [Failed] with the reason. */
public sealed interface RadioResult<out T> {
    /** The operation succeeded with [value]. */
    public class Ok<out T>(
        public val value: T,
    ) : RadioResult<T>

    /** The operation failed for [reason]; nothing changed. */
    public class Failed(
        public val reason: RadioFailure,
    ) : RadioResult<Nothing>
}

/** Why a [RadioPort] operation failed. */
public enum class RadioFailure {
    /** An argument broke the operation's contract, such as an advertisement that is not 16 bytes. */
    INVALID_ARGUMENT,

    /** The peer is out of range or no longer advertising. */
    PEER_UNREACHABLE,

    /** The peer serves no capability characteristic, so it is not a MeshLink node. */
    CAPABILITY_MISSING,

    /** The link closed before or during the operation. */
    LINK_CLOSED,
}
