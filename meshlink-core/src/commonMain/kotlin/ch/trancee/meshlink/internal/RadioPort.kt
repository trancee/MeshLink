package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.Flow

/**
 * Everything the protocol core asks of a Bluetooth LE radio (ADR 0017 decision 1; ADR 0009 GATT
 * bearer). A platform adapter implements it with no protocol logic of its own; `VirtualAir` in
 * `:meshlink-testing` implements it for many nodes in one process.
 *
 * It covers the GATT bearer as `docs/spec/link.md` "Roles and bearers" describes it: advertise
 * and scan for the MESH service, connect as central, serve and read the capability characteristic,
 * and an ordered byte stream per link. It also covers the L2CAP CoC bearer a link may switch to
 * (spec "Bearer switch"): listen for channels as peripheral, open one as central. Every operation
 * reports failure as a [RadioResult], never as an exception.
 */
public interface RadioPort {
    /**
     * What the radio observed, in order: sightings, links that peers opened to it, disconnects, and
     * advertising, scanning or serving that failed after its start returned [RadioResult.Ok].
     */
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

    /**
     * Starts accepting unencrypted, unbonded L2CAP CoC channels and returns the PSM the platform
     * assigned, which the node offers in its capability record. Each channel a central opens
     * arrives as [RadioEvent.ChannelOpened]. Returns the same PSM if the radio already listens.
     * Fails with [RadioFailure.UNSUPPORTED] where the platform has no LE CoC.
     */
    public suspend fun listenForChannels(): RadioResult<Int>

    /** Stops accepting channels; open channels stay open. Does nothing if the radio is not listening. */
    public fun stopListening()

    /** Connects to [peer] as central and negotiates the ATT MTU. */
    public suspend fun connect(peer: PeerHandle): RadioResult<CentralLink>

    /**
     * The most links this radio can hold as peripheral, or `null` if the platform sets no cap
     * (spec "Connect retries and link caps"). The core refuses a central beyond it.
     */
    public val peripheralLinkCap: Int?

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

    /**
     * [link] closed, whichever side or the air closed it. Its [RadioLink.incoming] has completed.
     * [transient] marks a loss the platform reported with no error some 30 s in, an unanswered ATT
     * request (library rule R8), which a central reconnects after.
     */
    public class Disconnected(
        public val link: RadioLink,
        public val transient: Boolean = false,
    ) : RadioEvent

    /**
     * A central opened [channel] to this radio, which listens for channels. Its [RadioStream.peer]
     * equals the [RadioLink.peer] of that central's GATT link.
     */
    public class ChannelOpened(
        public val channel: RadioStream,
    ) : RadioEvent

    /**
     * Advertising, scanning or serving the capability record failed for [reason] after its start
     * returned [RadioResult.Ok], as platforms that answer through a later callback report it.
     */
    public class Failed(
        public val reason: RadioFailure,
    ) : RadioEvent
}

/** An ordered byte stream in each direction between this radio and one peer. */
public interface RadioStream {
    /** The other end of the stream. */
    public val peer: PeerHandle

    /** The peer's stream, in order, in chunks of any size. Completes when the stream closes. */
    public val incoming: Flow<ByteArray>

    /** Sends [bytes] as the next bytes of this side's stream. */
    public suspend fun write(bytes: ByteArray): RadioResult<Unit>

    /** Closes the stream at both ends. Does nothing if closed. */
    public fun disconnect()
}

/**
 * One GATT link and its ordered byte stream in each direction (spec: the central writes without
 * response, the peripheral notifies). Each chunk of [incoming] is one write or notification;
 * [disconnect] gives both sides [RadioEvent.Disconnected].
 */
public interface RadioLink : RadioStream {
    /** The negotiated ATT MTU; each chunk of the stream carries at most `attMtu - 3` bytes. */
    public val attMtu: Int
}

/**
 * A link this radio opened as central, which can also read the peer's capability characteristic
 * and open an L2CAP CoC channel to it.
 */
public interface CentralLink : RadioLink {
    /** The value the peripheral serves through [RadioPort.serveCapability]. */
    public suspend fun readCapability(): RadioResult<ByteArray>

    /**
     * Opens an unencrypted, unbonded L2CAP CoC channel to [psm] on this link's peer. The channel
     * closes when this link closes. Fails with [RadioFailure.UNSUPPORTED] where the platform has no
     * LE CoC.
     */
    public suspend fun openChannel(psm: Int): RadioResult<RadioStream>
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

    /** The platform has no radio MeshLink can use, so the radio does nothing. */
    UNSUPPORTED,

    /** The platform radio refused or failed the operation, for example out of resources. */
    RADIO_ERROR,

    /** A connect failed for a cause that often clears on retry, such as Android GATT status 133 or 147. */
    TRANSIENT,
}
