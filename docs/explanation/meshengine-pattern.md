# The MeshEngine Coordinator Pattern

## The pattern

MeshLink has two layers at the top:

```
MeshLink (public)  →  MeshEngine (internal coordinator)  →  Subsystems
```

- **MeshLink:** Thin lifecycle shell. Holds Identity, Engine, Transport. Manages MeshLinkState FSM. Delegates everything to MeshEngine.
- **MeshEngine:** Owns all domain logic composition. Creates and wires all subsystems. Routes events between them. Single coordinator.

## Why this layering

### MeshLink is the API boundary

`MeshLink` implements `MeshLinkApi` — it's the only public class consumers interact with. Its responsibilities are narrow:

1. Lifecycle state machine (Idle → Starting → Running → Stopping → Stopped)
2. Platform factory methods (createAndroid, createIos)
3. Delegating every method call to MeshEngine
4. Holding the top-level CoroutineScope

It does NOT contain routing logic, transfer logic, handshake logic, or diagnostic emission.

### MeshEngine is the internal wiring layer

`MeshEngine.create()` builds every subsystem and wires them together:

```kotlin
internal class MeshEngine private constructor(
    private val transport: BleTransport,
    private val routeCoordinator: RouteCoordinator,
    private val deliveryPipeline: DeliveryPipeline,
    private val transferEngine: TransferEngine,
    private val powerManager: PowerManager,
    private val handshakeManager: NoiseHandshakeManager,
    private val presenceTracker: PresenceTracker,
    private val meshStateManager: MeshStateManager,
    private val pseudonymRotator: PseudonymRotator,
    private val trustStore: TrustStore,
    private val identity: Identity,
    private val storage: SecureStorage,
    private val diagnosticSink: DiagnosticSink,
    private val cryptoProvider: CryptoProvider,
) {
    companion object {
        fun create(config: MeshEngineConfig, ...): MeshEngine { ... }
    }
}
```

## Why not expose subsystems directly?

An alternative design would have `MeshLink` hold references to `TrustStore`, `DeliveryPipeline`, etc., and call them directly. This was rejected because:

1. **Coupling:** If MeshLink calls DeliveryPipeline directly, changing the delivery pipeline interface breaks the public API layer.
2. **Coordination:** Many operations touch multiple subsystems. `forgetPeer()` must clear TrustStore + RoutingTable + DeliveryPipeline + PresenceTracker. MeshEngine coordinates this.
3. **Testing:** MeshEngine can be tested in isolation with VirtualMeshTransport. MeshLink is just a thin shell over it.

## How methods flow

```
meshLink.forgetPeer(peerId)
  → engine.forgetPeer(peerId)
    → trustStore.removeKey(peerId)
    → routeCoordinator.retractPeer(peerId)
    → deliveryPipeline.clearPendingFor(peerId)
    → presenceTracker.removePeer(peerId)
    → diagnosticSink.emit(PEER_FORGOTTEN) { ... }
```

## Constructor injection, no service locator

Every subsystem receives its dependencies via constructor parameters. There is no dependency injection framework, no global state, no `ServiceLocator.get<TrustStore>()`.

This means:
- The dependency graph is visible in `MeshEngine.create()`
- Testing any subsystem in isolation requires only passing mocks/fakes to its constructor
- No hidden coupling through shared mutable state

## The DiagnosticSink cascade

`DiagnosticSink` is injected into every subsystem constructor:

```
MeshEngine.create()
  → RoutingEngine(diagnosticSink, ...)
  → DeliveryPipeline(diagnosticSink, ...)
  → TransferEngine(diagnosticSink, ...)
  → PowerManager(diagnosticSink, ...)
  → NoiseHandshakeManager(diagnosticSink, ...)
```

Any subsystem can emit events without knowing about the others. The single `DiagnosticSink` instance aggregates all events into one `SharedFlow` that consumers collect on.

## When to add a new subsystem

If you're adding a new protocol capability:

1. Create the subsystem class in its own package
2. Accept `DiagnosticSink` (and other deps) via constructor
3. Add it as a field on `MeshEngine`
4. Wire it in `MeshEngine.create()`
5. Add delegation methods on `MeshEngine` for any new public API methods
6. Add thin delegations on `MeshLink`
7. Update BCV baseline (`apiDump`)
