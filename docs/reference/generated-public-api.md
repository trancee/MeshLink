# Generated public API symbol tables

This page is generated from the checked-in public API dump used for binary compatibility validation.

Use it as a completeness appendix for the public SDK surface.
For behavior and usage, prefer the human-written [MeshLink SDK API reference](meshlink-sdk-api.md) and [How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md).

## Package `ch.trancee.meshlink.api`

| Type | Kind | Public surface |
|---|---|---|
| `ch.trancee.meshlink.api.BatterySnapshot` | class | `level`, `isCharging()` |
| `ch.trancee.meshlink.api.DeliveryPriority` | enum | constants: `HIGH`, `LOW`, `NORMAL` |
| `ch.trancee.meshlink.api.ForgetPeerResult` | abstract class | — |
| `ch.trancee.meshlink.api.ForgetPeerResult.Forgotten` | object | — |
| `ch.trancee.meshlink.api.ForgetPeerResult.NotFound` | object | — |
| `ch.trancee.meshlink.api.InboundMessage` | class | `originPeerId`, `payload`, `priority`, `receivedAtEpochMillis` |
| `ch.trancee.meshlink.api.MeshLink` | interface | `forgetPeer()`, `diagnosticEvents`, `messages`, `peerEvents`, `state`, `pause()`, `resume()`, `send()`, `start()`, `stop()`, `updateBattery()` |
| `ch.trancee.meshlink.api.MeshLinkBootstrap` | abstract class | — |
| `ch.trancee.meshlink.api.MeshLinkException` | abstract class | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.CryptoFailure` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.InvalidConfiguration` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.InvalidStateTransition` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.PermissionDenied` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.PlatformFailure` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.StorageFailure` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkException.TransportFailure` | nested type | `init` |
| `ch.trancee.meshlink.api.MeshLinkState` | abstract class | — |
| `ch.trancee.meshlink.api.MeshLinkState.Paused` | object | — |
| `ch.trancee.meshlink.api.MeshLinkState.Running` | object | — |
| `ch.trancee.meshlink.api.MeshLinkState.Stopped` | object | — |
| `ch.trancee.meshlink.api.MeshLinkState.Uninitialized` | object | — |
| `ch.trancee.meshlink.api.PauseResult` | abstract class | — |
| `ch.trancee.meshlink.api.PauseResult.AlreadyPaused` | object | — |
| `ch.trancee.meshlink.api.PauseResult.InvalidState` | nested type | `currentState` |
| `ch.trancee.meshlink.api.PauseResult.Paused` | object | — |
| `ch.trancee.meshlink.api.PeerConnectionState` | enum | constants: `CONNECTED`, `DISCONNECTED` |
| `ch.trancee.meshlink.api.PeerEvent` | abstract class | — |
| `ch.trancee.meshlink.api.PeerEvent.Found` | nested type | `peerId`, `state` |
| `ch.trancee.meshlink.api.PeerEvent.Lost` | nested type | `peerId` |
| `ch.trancee.meshlink.api.PeerEvent.StateChanged` | nested type | `peerId`, `state` |
| `ch.trancee.meshlink.api.PeerId` | class | `value` |
| `ch.trancee.meshlink.api.ResumeResult` | abstract class | — |
| `ch.trancee.meshlink.api.ResumeResult.AlreadyRunning` | object | — |
| `ch.trancee.meshlink.api.ResumeResult.InvalidState` | nested type | `currentState` |
| `ch.trancee.meshlink.api.ResumeResult.Resumed` | object | — |
| `ch.trancee.meshlink.api.SendFailureReason` | enum | constants: `PAYLOAD_TOO_LARGE`, `TRANSFER_ABORTED`, `TRANSFER_TIMED_OUT`, `TRUST_FAILURE`, `UNREACHABLE` |
| `ch.trancee.meshlink.api.SendResult` | abstract class | — |
| `ch.trancee.meshlink.api.SendResult.NotSent` | nested type | `reason` |
| `ch.trancee.meshlink.api.SendResult.Sent` | object | — |
| `ch.trancee.meshlink.api.StartResult` | abstract class | — |
| `ch.trancee.meshlink.api.StartResult.AlreadyRunning` | object | — |
| `ch.trancee.meshlink.api.StartResult.InvalidState` | nested type | `currentState` |
| `ch.trancee.meshlink.api.StartResult.Started` | object | — |
| `ch.trancee.meshlink.api.StopResult` | abstract class | — |
| `ch.trancee.meshlink.api.StopResult.AlreadyStopped` | object | — |
| `ch.trancee.meshlink.api.StopResult.Stopped` | object | — |
| `top-level declarations` | top-level | `meshLink()` |

## Package `ch.trancee.meshlink.api.apple`

| Type | Kind | Public surface |
|---|---|---|
| `ch.trancee.meshlink.api.apple.BleTransportBridge` | object | `install()`, `installData()` |
| `ch.trancee.meshlink.api.apple.ChaCha20Poly1305Callbacks` | class | `open`, `seal` |
| `ch.trancee.meshlink.api.apple.CryptoBridge` | object | `install()` |
| `ch.trancee.meshlink.api.apple.CryptoCallbacks` | class | `chacha20Poly1305`, `ed25519`, `hashes`, `keyGeneration`, `randomBytes`, `x25519` |
| `ch.trancee.meshlink.api.apple.CryptoRawKeyPair` | class | `privateKey`, `publicKey` |
| `ch.trancee.meshlink.api.apple.Ed25519Callbacks` | class | `sign`, `verify` |
| `ch.trancee.meshlink.api.apple.HashCallbacks` | class | `hmacSha256`, `sha256` |
| `ch.trancee.meshlink.api.apple.KeyGenerationCallbacks` | class | `generateEd25519KeyPair`, `generateX25519KeyPair` |

## Package `ch.trancee.meshlink.config`

| Type | Kind | Public surface |
|---|---|---|
| `ch.trancee.meshlink.config.MeshLinkConfig` | class | `init`, `appId`, `deliveryRetryDeadline`, `powerMode`, `regulatoryRegion` |
| `ch.trancee.meshlink.config.MeshLinkConfigBuilder` | class | `build()`, `appId`, `deliveryRetryDeadline`, `powerMode`, `regulatoryRegion`, `appId =`, `deliveryRetryDeadline =`, `powerMode =`, `regulatoryRegion =` |
| `ch.trancee.meshlink.config.MeshLinkDsl` | annotation | — |
| `ch.trancee.meshlink.config.PowerMode` | abstract class | — |
| `ch.trancee.meshlink.config.PowerMode.Automatic` | object | — |
| `ch.trancee.meshlink.config.PowerMode.Balanced` | object | — |
| `ch.trancee.meshlink.config.PowerMode.Performance` | object | — |
| `ch.trancee.meshlink.config.PowerMode.PowerSaver` | object | — |
| `ch.trancee.meshlink.config.RegulatoryRegion` | enum | constants: `DEFAULT`, `EU` |
| `top-level declarations` | top-level | `meshLinkConfig()` |

## Package `ch.trancee.meshlink.diagnostics`

| Type | Kind | Public surface |
|---|---|---|
| `ch.trancee.meshlink.diagnostics.DiagnosticCode` | enum | constants: `DELIVERY_QUEUED`, `DELIVERY_RETRYING`, `DELIVERY_RETRY_SCHEDULED`, `DELIVERY_SUCCEEDED`, `DELIVERY_UNREACHABLE`, `HOP_SESSION_ESTABLISHED`, `HOP_SESSION_FAILED`, `MESH_PAUSED`, `MESH_RESUMED`, `MESH_STARTED`, `MESH_STOPPED`, `NO_ROUTE_AVAILABLE`, `POWER_MODE_CHANGED`, `ROUTE_CONVERGED`, `ROUTE_DISCOVERED`, `ROUTE_EXPIRED`, `ROUTE_RETRACTED`, `ROUTE_UPDATED`, `SIZE_LIMIT_REJECTED`, `TRANSFER_COMPLETED`, `TRANSFER_FAILED`, `TRANSFER_PROGRESS`, `TRANSFER_STARTED`, `TRANSPORT_MODE_CHANGED`, `TRUST_ESTABLISHED`, `TRUST_FAILURE` |
| `ch.trancee.meshlink.diagnostics.DiagnosticEvent` | class | `init`, `code`, `metadata`, `peerSuffix`, `reason`, `severity`, `stage` |
| `ch.trancee.meshlink.diagnostics.DiagnosticReason` | enum | constants: `DELIVERY_FAILURE`, `DELIVERY_RETRY`, `POWER_CHANGE`, `ROUTE_CHANGE`, `SIZE_LIMIT`, `STATE_CHANGE`, `TRANSFER_FAILURE`, `TRANSPORT_CHANGE`, `TRUST_FAILURE` |
| `ch.trancee.meshlink.diagnostics.DiagnosticSeverity` | enum | constants: `DEBUG`, `ERROR`, `INFO`, `WARN` |
