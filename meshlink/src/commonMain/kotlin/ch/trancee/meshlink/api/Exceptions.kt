package ch.trancee.meshlink.api

/**
 * Base type for all public MeshLink failures exposed to host applications.
 *
 * Delivery outcomes that callers can recover from locally remain modeled as [SendResult].
 * Exceptions are reserved for invalid configuration, invalid API usage, or subsystem failures that
 * prevented MeshLink from executing the requested operation.
 */
public sealed class MeshLinkException
protected constructor(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause) {
    /** Raised when the host app provides an invalid MeshLink configuration value. */
    public class InvalidConfiguration
    public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when the host app requests a lifecycle transition that is not allowed. */
    public class InvalidStateTransition
    public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when the platform denies a permission required for MeshLink operation. */
    public class PermissionDenied public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when the BLE transport cannot complete a requested operation. */
    public class TransportFailure public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when MeshLink cannot read or persist required local state securely. */
    public class StorageFailure public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when the cryptographic provider cannot complete a required operation. */
    public class CryptoFailure public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)

    /** Raised when a platform-specific API fails and its original cause must be wrapped. */
    public class PlatformFailure public constructor(message: String, cause: Throwable? = null) :
        MeshLinkException(message, cause)
}
