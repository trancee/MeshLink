# Reference app runtime seams

The reference app keeps one intentional runtime seam between the MeshLink SDK and the app-facing live proof timeline.

## System boundary

There are four distinct layers in the live-proof path:

1. **MeshLink runtime surface**
   - emits `peerEvents`
   - owns transport/event semantics
   - should not know about reference-app automation state

2. **Reference runtime glue**
   - `LiveReferenceMeshRuntime`
   - `LiveReferenceMeshControllerAssembly`
   - bridges the hot peer-event flow into retained snapshot state
   - keeps the first discovery event from being dropped during startup

3. **Automation coordinator and driver**
   - `LiveProofAutomationDriver`
   - `LiveProofAutomationCoordinator`
   - converts snapshot state into high-level automation requests
   - does not buffer peer events itself

4. **Step runners**
   - sender and passive step runners
   - react to retained snapshot/timeline state
   - decide when to emit send, wait, retain, export, or complete actions

## Why the seam exists

`MeshLink.peerEvents` is a hot flow. The reference app binds its snapshot and timeline collectors during runtime startup, so the first discovery event can arrive before the app collectors are attached. To keep the first event from being lost, `LiveReferenceMeshRuntime` relays peer events through an app-local `MutableSharedFlow(replay = 1)` before the snapshot projector consumes them.

That replay is a startup shield, not a general-purpose buffering policy.

## Event flow

The live path is intentionally narrow:

- `MeshLink.peerEvents` emits discovery and presence signals
- `LiveReferenceMeshRuntime` relays them into a replaying app-local flow
- the snapshot projector updates retained reference state
- `LiveProofAutomationDriver` samples the current timeline state
- `LiveProofAutomationCoordinator` decides whether discovery, mesh start, or other automation requests should fire
- sender/passive step runners consume the retained snapshot and emit proof actions only when their prerequisites are visible

## What this seam is not

- It is not a change to MeshLink core event semantics.
- It is not a permanent buffering policy for every consumer.
- It is not the automation driver’s job to own buffering.
- It is not the sender step runner’s job to own buffering.
- It is not the passive step runner’s job to own buffering.
- It is not needed for scripted UI automation, which writes directly into the retained reference snapshot.

## Current behavior

- Live discovery events are retained long enough for the reference snapshot projector to observe the first `PeerEvent.Found`.
- Mixed direct-guided runs still announce peer discovery from the current live snapshot.
- The coordinator requests mesh start when the snapshot shows no visible peer yet.
- Passive proof progression remains snapshot-driven; the passive step runner and driver only react to retained state, so they do not need the replay seam themselves.
- The sender step runner follows the same retained-state rule: it logs a wait when no peers are visible, then sends once the coordinator snapshot exposes an eligible target.

## Tests covering the seam

- `LiveReferenceMeshRuntimeTest.firstPeerEventEmittedDuringTheInitialExecuteCallIsRetained`
- `LiveReferenceMeshRuntimeTest.bindLiveReferenceControllerFlowsReplaysPeerEventsQueuedBeforeTheCollectorStarts`
- `LiveProofAutomationCoordinatorTest.mixedDirectGuidedSnapshotWithAVisiblePeerAnnouncesDiscovery`
- `LiveProofAutomationCoordinatorTest.mixedDirectGuidedSnapshotWithoutPeersRequestsMeshStart`
- `LiveProofAutomationStepRunnerTest.directSenderStepWaitsForPeersBeforeSending`
- `LiveProofAutomationDriverMeshStartTest.driverRequestsMeshStartFromTheTimelineStateWithoutCompose`

## Follow-up guidance

If this replay policy ever needs to move lower, it should be justified by a broader consumer need in `meshlink`, not by this reference-app startup race alone.
