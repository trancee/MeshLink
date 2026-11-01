# MeshLink public-API prototype (THROWAWAY)

> **Throwaway code.** This directory exists only to answer ticket 15, "What public API shape makes
> MeshLink easiest to use?". It implements the accepted design
> (`.scratch/meshlink-v1/issues/15-public-api-shape.md`, "Design (accepted 2026-11-01)") as a stub
> over an in-memory fake mesh. No BLE, no crypto, no Android target. Do not build on it; delete it
> once the findings below have been folded into the spec.

## How to run

All commands run from `prototypes/public-api/`. The JDK is 21 (see Deviations).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# 1. Kotlin: five reference scenarios, lifecycle/error cases, @sample check (JVM)
./gradlew --max-workers=2 jvmTest

# 2. iOS: release XCFramework (iosArm64 + iosSimulatorArm64, SKIE, library evolution)
./gradlew --max-workers=2 assembleMeshLinkKitReleaseXCFramework
./scripts/sync-xcframework.sh          # copies it into swift/Frameworks for the binaryTarget

# 3. Swift: overlay tests and raw (no-overlay) tests on one simulator, Xcode 16.4
cd swift
UDID=$(xcrun simctl create meshlink-api-proto "iPhone 16" com.apple.CoreSimulator.SimRuntime.iOS-18-5)
xcrun simctl boot "$UDID"
xcodebuild test -scheme MeshLinkSwift -destination "platform=iOS Simulator,id=$UDID" -derivedDataPath DerivedData
xcrun simctl shutdown "$UDID" && xcrun simctl delete "$UDID"
```

### Observed results (2026-11-01, this machine)

| Command | Result |
|---|---|
| `./gradlew --max-workers=2 --rerun-tasks jvmTest` | `BUILD SUCCESSFUL in 16s`. LifecycleAndErrorsTest 10/10, ReferenceScenariosTest 6/6, SamplesTest 1/1. |
| `./gradlew --max-workers=2 assembleMeshLinkKitReleaseXCFramework` | `BUILD SUCCESSFUL in 1m 38s`. Slices `ios-arm64` and `ios-arm64-simulator`; `vtool -show-build` reports `minos 15.0` for both; `.swiftinterface` present. |
| `xcodebuild test -scheme MeshLinkSwift …` (iPhone 16, iOS 18.5) | `** TEST SUCCEEDED **`. OverlayScenarioTests 15/15 (ReferenceScenariosTests 6, LifecycleAndErrorsTests 9), RawScenarioTests 6/6. |
| Same build with `SWIFT_VERSION=6` (`build-for-testing`) | `** TEST BUILD FAILED **`. One error, in a test (see Swift 6 below). Experiment not committed. |

Mutation check on the Kotlin side: two deliberate faults made two tests fail and were then reverted.
The faults were inbox per-sender share = capacity, and a broadcast forwarded one hop past its hop
limit. The mutation check proves the fair-share and hop-limit tests are not vacuous.

Two environment problems came up during the Swift run:

- The first `xcodebuild test` crashed with `DVTAssertions: ASSERTION FAILURE … (launchSession) should not be nil`.
  - The destination iPhone 16 (iOS 18.6) had no data on disk: `simctl boot` reported "cannot be located on disk".
  - Fix: create a dedicated simulator, boot it, and delete it afterward, as the recipe above does.
  - An iOS 18.5 simulator that was already booted (another agent's) was left untouched.
- `vtool` reports `sdk 26.4` on the slices even though Xcode 16.4 is selected. I did not investigate further. It did not affect linking or tests.

## Layout

```
build.gradle.kts            Kotlin 2.4.21, SKIE 0.10.16, jvm + iosArm64 + iosSimulatorArm64, XCFramework "MeshLinkKit"
src/commonMain/…/meshlink/  Public API (MeshLink, MeshLinkConfig, Preset, SecurityProfile, MeshState, Peer, NodeId,
                            Messaging, Diagnostics, MeshLinkException, MeshLinkPlatform)
  internal/                 NodeEngine seam, bounded Inbox (fair share), DiagnosticsBuffer
  fake/                     FakeMesh / FakeHost / FakeNode: in-memory medium with topology, RSSI, Bluetooth/permission toggles
src/commonTest/…/meshlink/  ReferenceScenariosTest (5 scenarios), LifecycleAndErrorsTest, samples/ (@sample per ADR 0006)
scripts/sync-xcframework.sh Copies the release XCFramework into swift/Frameworks (ignored by git)
swift/Package.swift         binaryTarget MeshLinkKit + overlay MeshLinkSwift + MeshLinkTestSupport + 2 test targets
swift/Sources/MeshLinkSwift Overlay (59 non-blank lines): Data <-> KotlinByteArray, Swift defaults, static create, builder sugar
swift/Tests/OverlayScenarioTests  5 scenarios + Blocked/rejection/error/NodeId/cancellation/threading cases, with overlay
swift/Tests/RawScenarioTests      the same 5 scenarios, without the overlay (imports MeshLinkKit only)
```

## Choices where the spec is silent

**Naming and structure**
- **Framework name is `MeshLinkKit`.** This avoids a Swift module/type clash with class `MeshLink`. The alternative, a module named `MeshLink`, was not tested.
- **Package is `ch.trancee.meshlink`.** The ObjC prefix generated is `MLK`.
- **Public sealed hierarchies are sealed classes, not interfaces.** This applies to `Preset`, `MeshState`, `Requirement`, `Reachability`, `SendResult`, `DeliveryStatus` and `MeshLinkException`.
  - Leaves are `data object`s and data classes.
  - Effect in Swift: `NSSet<MLKRequirement *>` becomes a typed `Set<Requirement>`, and every value is NSObject-`Hashable`.

**Lifecycle and instances**
- **"One MeshLink per process" is enforced per `MeshLinkPlatform`, not per process.** The fake needs several nodes in one process.
  - `create` throws `MeshLinkException.InstanceAlreadyOpen` while another unclosed instance holds the same platform. This is my reading of "a second running instance gets a typed rejection".
- **`MeshLink` implements `AutoCloseable`.** `close()` does not suspend; it completes the `messages` and `diagnostics` flows and releases the platform.
  - `start()` and `stop()` throw `MeshLinkException.Closed` after `close()`.
  - `start()` and `stop()` are annotated `@Throws(MeshLinkException::class, CancellationException::class)`. K/N requires `CancellationException` on suspend functions.

**Messaging**
- **Read receipt: `ReceivedMessage.markRead()`.** It is idempotent and a no-op when the sender did not request a read receipt.
- **Delivery receipt timing.** The delivery receipt is sent when the message leaves the bounded inbox, that is, when the app takes it from `messages`.
  - `messages` and `diagnostics` are single-collector flows.
  - `ReceivedMessage.payload` returns a copy on every access.
- **`SendOptions(directOnly, deliveryReceipt, readReceipt, messageLifetimeSeconds: Int?)`.**
  - It uses Kotlin defaults; Swift has to pass all four arguments because SKIE default arguments are off.
  - `SendOptions.Default` and `BroadcastOptions.Default` live in the companions.
- **Times.** There is no `kotlin.time.Duration` in the API because value classes don't export; lifetimes are `Int` seconds. Instants are `kotlin.time.Instant`.

**Codes and limits**
- **Code ranges.**
  - `RejectionReason`: 100–105 (NotRunning, PayloadSize, InvalidDestination, HopLimitOutOfRange, LifetimeOutOfRange, ReceiptNotAllowed).
  - `FailureReason`: 200–202 (NoRoute, NoDirectLink, NotAcknowledged).
  - `DiagnosticCode`: 1001–1901 (1001 SecurityProfileNone, 1101 PeerRejectedMeshMismatch, 1201 InboxSenderShareExceeded, 1202 InboxFull, 1301 MessageExpired, 1401 StartBlocked, 1901 DiagnosticsOverflow).
- **Config validation in `build()`.** Failures throw `InvalidConfig(setting, problem)`. Rules:
  - meshId: 1–64 chars.
  - meshKey: exactly 32 bytes, and only with a non-None profile.
  - Diagnostics buffer: 16–4096 events.
  - maxPayloadBytes: 1–65536.
  - inboxCapacity: 4–4096.
  - maxHopLimit: 1–16.
  - Message lifetime: 1–604800 s.
  - Custom preset name: not blank.
  - `describe()` prints `name: value` lines and marks overridden settings with `(override)`.
- **Built-in preset values are placeholders, not proposals.**

  | Preset | Max payload | Inbox | Max hop | Lifetime s | Store-and-forward | Delivery rcpt | Read rcpt | Duty |
  |---|---|---|---|---|---|---|---|---|
  | OfflineChat | 4096 | 64 | 6 | 3600 | no | yes | yes | Balanced |
  | EmergencyBroadcast | 512 | 128 | 10 | 1800 | yes | yes | no | Balanced |
  | ProximityDiscovery | 256 | 16 | 1 | 60 | no | no | no | LowPower |
  | LocalMultiplayer | 512 | 256 | 2 | 5 | no | yes | no | LowLatency |
  | FieldTeams | 4096 | 64 | 8 | 86400 | yes | yes | yes | LowPower |

- **Proximity and inbox rules.**
  - Buckets: Immediate ≥ −55 dBm, Near ≥ −75 dBm, otherwise Far; Unknown for routed peers.
  - Smoothing: each new RSSI sample is averaged with the previous value.
  - Inbox fair share is `max(1, capacity / 4)` per sender.
- **`PermissionKind` is {NearbyDevices, Location}.**

**Swift overlay contents**
- Only idioms:
  - `KotlinByteArray(data:)` and `.data`
  - `send(to:data:options:)` and `broadcast(_:hopLimit:options:)`
  - `SendOptions` init with Swift defaults, plus `.default`
  - `MeshLink.create(config:platform:)`
  - `Builder.meshId(_:)` and `meshKey(_: Data)`

## Deviations

- **JDK 21 instead of 25.** JDK 25 is not installed on this machine; Gradle and the toolchain run on `/opt/homebrew/opt/openjdk@21`.
- **Gradle wrapper 9.7.1 was generated with system Gradle 9.8.2.**
- **No Android target, so no AGP.** It was skipped to save time and memory; JVM covers `commonMain` behavior.
  - The Android `Context`-wrapping platform is therefore untested.
- **One instance per platform object instead of per process** (see choices above).
- **The fake (`FakeMesh` and friends) is public in `commonMain`** so that Swift tests can drive it. As a result it also ships inside the XCFramework (see Recommendation 6).

## Ergonomics findings

### Size per scenario

Lines are non-blank lines inside `// region` markers. The Swift tests pack loops onto one line, so non-whitespace characters are the fairer measure.

| Scenario | Kotlin lines / chars | Swift + overlay lines / chars | Swift raw lines / chars |
|---|---|---|---|
| Offline chat | 23 / 1082 | 23 / 1443 | 24 / 1513 |
| Emergency broadcast | 14 / 765 | 14 / 834 | 14 / 891 |
| Proximity discovery | 24 / 856 | 15 / 1041 | 15 / 1061 |
| Local multiplayer | 19 / 945 | 16 / 1144 | 18 / 1266 |
| Field teams (2 tests) | 28 / 1435 | 28 / 1757 | 30 / 1953 |
| **Total** | **108 / 5083** | **96 / 6219 (+22 % chars)** | **101 / 6684 (+31 %)** |

Raw Swift also needs 9 lines of `KotlinByteArray` ↔ `String` helpers inside the test class.

The overlay saves 465 characters over the five scenarios, which is 7 % of the raw total. Most of the remaining gap to Kotlin comes from three sources:
- argument labels;
- `.shared` / `.companion`;
- `guard case … = onEnum(of:) else { return XCTFail() }` where Kotlin writes `assertIs<…>()`.

### Side by side (offline chat, from passing tests)

```kotlin
val sent = alice.send(bob.localNodeId, "Hi Bob".encodeToByteArray(), SendOptions(deliveryReceipt = true, readReceipt = true))
val message = assertIs<SendResult.Accepted>(sent).message
val received = bob.messages.first()
message.status.first { it == DeliveryStatus.Delivered }
received.markRead()
```

```swift
// with overlay
let sent = alice.send(to: bob.localNodeId, data: Data("Hi Bob".utf8), options: SendOptions(deliveryReceipt: true, readReceipt: true))
guard case .accepted(let accepted) = onEnum(of: sent) else { return XCTFail("rejected: \(sent)") }
let received = try await first(bob.messages)
_ = try await first(accepted.message.status) { $0 is DeliveryStatus.Delivered }
received.markRead()

// raw
let options = SendOptions(directOnly: false, deliveryReceipt: true, readReceipt: true, messageLifetimeSeconds: nil)
let sent = alice.send(to: bob.localNodeId, payload: bytes("Hi Bob"), options: options)   // bytes(): hand-written KotlinByteArray loop
```

### Swift awkward spots (all observed in the compiled tests or the generated interface)

| Spot | What we saw | Severity |
|---|---|---|
| `KotlinByteArray` | No `Data` bridge. Raw code copies byte by byte with `get(index:)`/`set(index:value:)` and `Int8(bitPattern:)`. | High; the overlay fixes it |
| Boxed types | `messageLifetimeSeconds: Int?` arrives as `KotlinInt?`, so raw code writes `KotlinInt(value: 1)`. The header has 17 `MLKInt` uses. No `KotlinUnit` appears in user code. | Medium |
| No default arguments | `SendOptions(...)` needs all four arguments in raw Swift. | Medium; the overlay fixes it |
| Companion and objects | `MeshLink.companion.create(config:platform:)`, `SendOptions.companion.Default`, `Preset.OfflineChat.shared`, `Requirement.BluetoothOff.shared`, `NodeId.companion.fromHex(hex:)`. | Low–medium noise |
| Mangled names | The swiftinterface has `__Sealed` (88), `__Kotlin` (24), and an `__Severity`/`__SecurityProfile`/… twin per enum. The header leaks `Kotlinx_coroutines_coreFlow`, `Skie_Suspend__N__…` and constants such as `MAX_MESH_ID_LENGTH`. The scenarios needed none of them. | Low (autocomplete clutter) |
| Labels | `setPresent(isPresent:)`, `setBluetoothOn(on:)`, `PresetOverrides.Builder().maxPayloadBytes(bytes: 64)`, `Builder.meshId(id:)`. | Low |
| `onEnum` | One `onEnum(of:)` per sealed level; offline chat nests two (`SendResult`, then `Reachability`). For a single state check, `$0 is DeliveryStatus.Delivered` reads better than a switch. Exhaustive switches compiled. | Fine |
| Builder vs DSL | Both work. SKIE exports the DSL as `meshLinkConfig(preset:securityProfile:) { $0.meshId = "…" }`, which reads as well as the builder. The fluent `MeshLinkConfig.Builder(...).meshId("…").build()` needs the overlay for unlabeled setters. | Fine |
| `Preset.Custom` | `Preset.Custom(name:base:overrides:)` with `PresetOverrides.Builder()…build()`; no issue. | Fine |
| `NodeId` | `Hashable` via NSObject `isEqual`/`hash`. A `NodeId` parsed from hex (a different instance) finds the original key in a `[NodeId: String]`, and `Set([parsed, original]).count == 1`. | Fine |
| `StateFlow.value` | `node.peers.value` and `node.state.value` read synchronously. `MeshState.Blocked(requirements:)` compares with `==`, and `requirements` is a typed `Set<Requirement>`. | Fine |
| Cancellation | `Task.cancel()` ended a `for await` over `peers` (a never-completing StateFlow) within 2 s. I did not separately verify that the Kotlin collector was cancelled. | Fine |
| Errors | `try` works. To switch on the subtype you need `(error as NSError).kotlinException as? MeshLinkException`, then `onEnum(of:)`, as in `case .invalidConfig(let invalid)` with `invalid.setting == "meshKey"`. There is no direct `catch let e as MeshLinkException`. | Medium |
| Threading | `for await` inside a `@MainActor` closure resumed on the main thread (`pthread_main_np() != 0`). All scenario test classes are `@MainActor` and needed no explicit hops. | Fine |
| `SecurityProfile.none` | Compiled and worked as `.none` in non-optional positions. In an `Optional<SecurityProfile>` context it would be ambiguous with `Optional.none`; untested. | Risk |
| Swift 6 mode | One error: `non-sendable result type 'MeshState?' cannot be sent from nonisolated context in call to instance method 'next()'`, when iterating `node.state` in a `@MainActor` closure from a nonisolated test. SKIE marks only enums `Sendable`; Kotlin classes produced no other diagnostics. | Low–medium |

### SKIE issues

None of #208, #199, #143 or #151 was hit:

- **#208:** the package has no Swift macros.
- **#199:** Kotlin is 2.4.21, and `is`/`onEnum` on sealed data objects worked.
- **#143:** the API exposes no `StateFlow<Enum>`. `MeshState` is sealed, and enums only appear inside classes. A future `StateFlow<SecurityProfile>` would be exposed to #143.
- **#151:** SKIE default arguments are off and none are exported.

I found no new SKIE bug.

### Is the Swift overlay justified? **Yes, but thin.**

Evidence:
- Raw tests need hand-written `KotlinByteArray` copy loops (9 lines).
- `SendOptions` needs all four arguments plus `KotlinInt(value:)`.
- Every factory call needs `.companion`.
- Together that is 465 characters over the five scenarios, plus helper code every app would rewrite.

Everything else (flows, async, sealed switches, `NodeId` keys, the DSL) worked without it. The overlay stays at 59 lines with no logic.

## Recommendations (proposals only; none applied)

1. **Add an error accessor to the overlay.** For example, `extension Error { var meshLinkException: MeshLinkException? }`, or a Swift `MeshLinkError` enum mirror.
   - Evidence: catching a typed subtype takes `(error as NSError).kotlinException as? MeshLinkException` plus `onEnum`.
2. **Remove the nullable boxed `Int?` from the API.** `SendOptions.messageLifetimeSeconds: Int?` exports as `KotlinInt?`. Use a non-null `Int` with a documented "preset lifetime" sentinel, or add a separate `withMessageLifetime(seconds)`.
   - Evidence: raw Swift writes `KotlinInt(value: 1)`. `PresetOverrides` getters are also `MLKInt *`.
3. **Keep the overlay's `Data` bridge, and benchmark it before v1.** The byte-by-byte copy makes one ObjC call per byte. I did not measure its cost.
4. **Keep `MeshLink.create` in the companion, and give Swift a static `create` in the overlay.** The raw form `MeshLink.companion.create` is the most frequent noise.
5. **Keep both the Kotlin DSL and the Swift builder, and document the DSL closure as an option for Swift.** It reads well (`meshLinkConfig(...) { $0.meshId = … }`). This puts the spec's "Swift uses a fluent builder" in perspective; the builder is not required.
6. **Move the fake mesh to a separate test-fixtures artifact.** Today `FakeMesh`, `FakeHost` and their `Skie_Suspend__…` shims appear in the production header. Apps want a fake, but not in the shipping binary.
7. **Rename `SecurityProfile.None`.** Candidates: `Unsecured` or `Open`. The Swift `.none` case is a known Optional-ambiguity trap; I did not observe a failure here.
8. **Add Swift 6 guidance to the docs:** consume flows inside a `@MainActor` context. Optionally state that MeshLink types are not `Sendable`.
   - Evidence: the single Swift 6 error.

## Open problems

- The Android target, `Context` wrapping and the AGP 9.3.3 build are untested.
- Real concurrency is untested. The JVM tests run on a virtual clock with one serial dispatcher. The Swift tests use real time (1 s lifetime) with a `limitedParallelism(1)` view of `Dispatchers.Default`.
- The cost of `KotlinByteArray` conversion and of the flow bridge per message was not measured.
- Single-collector `messages` and `diagnostics` flows: the behavior with two concurrent Swift collectors was not tested.
