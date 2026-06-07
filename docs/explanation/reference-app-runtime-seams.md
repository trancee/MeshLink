# Reference app runtime seams

The reference app keeps one intentional runtime seam between the MeshLink SDK and the app-facing live proof timeline.

## Why the seam exists

`MeshLink.peerEvents` is a hot flow. The reference app binds its snapshot and timeline collectors during runtime startup, so the first discovery event can arrive before the app collectors are attached. To keep the first event from being lost, `LiveReferenceMeshRuntime` relays peer events through an app-local `MutableSharedFlow(replay = 1)` before the snapshot projector consumes them.

## What this seam is not

- It is not a change to MeshLink core event semantics.
- It is not a permanent buffering policy for every consumer.
- It is not needed for scripted UI automation, which writes directly into the retained reference snapshot.

## Current behavior

- Live discovery events are retained long enough for the reference snapshot projector to observe the first `PeerEvent.Found`.
- Mixed direct-guided runs still announce peer discovery from the current live snapshot.
- The coordinator requests mesh start when the snapshot shows no visible peer yet.

## Tests covering the seam

- `LiveReferenceMeshRuntimeTest.firstPeerEventEmittedDuringTheInitialExecuteCallIsRetained`
- `LiveReferenceMeshRuntimeTest.bindLiveReferenceControllerFlowsReplaysPeerEventsQueuedBeforeTheCollectorStarts`
- `LiveProofAutomationCoordinatorTest.mixedDirectGuidedSnapshotWithAVisiblePeerAnnouncesDiscovery`
- `LiveProofAutomationCoordinatorTest.mixedDirectGuidedSnapshotWithoutPeersRequestsMeshStart`

## Follow-up guidance

If this replay policy ever needs to move lower, it should be justified by a broader consumer need in `meshlink`, not by this reference-app startup race alone.
