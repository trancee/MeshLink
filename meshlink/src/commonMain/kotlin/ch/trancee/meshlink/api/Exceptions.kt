package ch.trancee.meshlink.api

public sealed class MeshLinkException protected constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    public class InvalidConfiguration public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class InvalidStateTransition public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class PermissionDenied public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class TransportFailure public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class StorageFailure public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class CryptoFailure public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)

    public class PlatformFailure public constructor(
        message: String,
        cause: Throwable? = null,
    ) : MeshLinkException(message, cause)
}
