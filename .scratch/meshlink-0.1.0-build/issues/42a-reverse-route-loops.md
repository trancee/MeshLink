# Learn reverse routes only from first copies

Status: done
Type: AFK
Blocked by: 37

## Behavior

Split from ticket 42, whose research probes found it. A relay learned a reverse route from every copy of a unicast frame, including copies it then dropped as duplicates within `unicast-seen-window`. A later copy over a longer flood path overwrote the first copy's route, two relays pointed at each other for the destination, and the seen window dropped the frame between them on every retry.

- **Rule (D-50):** a relay drops a copy whose `message_id` it forwarded within `unicast-seen-window` before it records a reverse route; a dropped copy teaches no route. A copy that passes the window still teaches its route when its hop limit stops it.
- **Evidence:** 50 static nodes at low density (mean 6 peers in range) delivered 115 of 123 messages within their 30 min lifetime, with sender and destination linked over 6–12 hops. With the rule, 123 of 123, and every 100- and 200-node probe cell delivered every message.
- No wire change.

## Spec and ADR references

- [routing.md, Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding), steps 2 and 3.
- [D-50](../../../docs/spec/decisions.md#d-50-reverse-routes-from-duplicate-copies), [D-29](../../../docs/spec/decisions.md#d-29-duplicate-suppression).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md), 2026-11-20 amendment "reverse-route loops".

## Acceptance criteria

- [x] A `RouterTest` case: after a first copy from A and a duplicate from B within the seen window, a frame for the source goes to A only. It fails before the fix.
- [x] A `routing.feature` scenario: Dave, linked to both Bob and Carol, which are linked to each other, sends "Ping" to Alice, and Alice's reply "Pong" reaches Dave once. It fails before the fix, because Bob and Carol learn their routes to Dave through each other.
- [x] The existing rule that a copy whose next hop limit would be 0 still teaches its route keeps passing.
- [x] The spec, D-50, the ADR 0008 amendment and the CHANGELOG describe the rule.
- [x] Every mutant of the change is killed.
- [x] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*RouterTest*'
./gradlew :meshlink-testing:testAndroidHostTest --tests '*RoutingFeatureTest*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Outcome

Shipped in [#113](https://github.com/trancee/MeshLink/pull/113) (`46b6776`).

- **Code:** `Router.relay` checks the seen window before `routes.learn`, and forwards only while the hop limit is above 1. A copy stopped by its hop limit still teaches its route.
- **Tests:**
  - `RouterTest.aDuplicateCopyWithinTheSeenWindowTeachesNoReverseRoute`: red before the fix.
  - The flood test now uses hop limit 2, the lowest that is still forwarded, which kills the `> 2` mutant. The `>= 1` mutant is killed by the existing hop-limit-1 test.
  - `routing.feature`, "A reply reaches the sender when relays also hear each other's copies": in a diamond whose relays also link to each other, Alice's reply never reached Dave before the fix, even after 60 s of retries. It now passes on the Android host and the iOS simulator.
