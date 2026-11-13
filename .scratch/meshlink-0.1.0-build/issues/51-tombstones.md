# Delete messages with tombstones

Status: ready-for-agent
Type: AFK
Blocked by: 49

## Behavior

Once a unicast message is delivered, its held copies disappear across the mesh. Only the destination can trigger this, because only it learns the secret `k`.

- **Commitment:** the origin draws 16 random bytes `k` per unicast message, puts `k` in the plaintext and `commitment = trunc_16(H("tombstone", k))` in the `MESSAGE` frame. Retries reuse both.
- **Tombstone:** `{message_id, k}`, carried in clear in the `DELIVERY_RECEIPT` (ticket 47's frame). Any node holding a `MESSAGE` with that `message_id` verifies `H(k)` against its commitment and deletes its copy. A tombstone that fails verification deletes nothing.
- **Storage:** a node stores a tombstone only if it verified a held copy or relayed the receipt that carried it. It ignores other tombstones from `CATCH_UP`. A tombstone from a receipt lives until that receipt's `expiry`; one that verified a held copy until that copy's `expiry`. At most 1,000 (`tombstone-store`), oldest dropped first.
- **Hand-off:** stored tombstones are offered in the `CATCH_UP` tombstone list, so a holder that missed the receipt drops its copy on the next encounter.
- **Broadcasts:** no tombstones.
- **Profiles:** in `Unsecured`, `k` is in plaintext, so anyone on the path can forge a tombstone. The guarantees page already says so; the code must not claim more.

## Spec and ADR references

- [end-to-end.md, Tombstones](../../../docs/spec/end-to-end.md#tombstones) and [Delivery receipts](../../../docs/spec/end-to-end.md#delivery-receipts).
- [link.md, Catch-up](../../../docs/spec/link.md#catch-up) (tombstone list); [routing.md, Holding](../../../docs/spec/routing.md#holding) and [MESSAGE](../../../docs/spec/routing.md#message) (commitment field).
- [D-10](../../../docs/spec/decisions.md#d-10-tombstone-commitment), [D-42](../../../docs/spec/decisions.md#d-42-tombstone-storage).
- [ADR 0014](../../../docs/adr/0014-store-and-forward.md) decisions 6, 7 and 8; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) (observer exposure of tombstones).
- Vectors: `derivations.json`, derivation `tombstone-commitment` ([vectors README](../../../docs/spec/vectors/README.md)).

## Acceptance criteria

- [ ] The `tombstone-commitment` vectors pass on Android and iOS through the vector runner.
- [ ] Seeded `VirtualAir` test, `Classical`, medium class: two relays hold a message for E; E receives it from one; the receipt's tombstone deletes the copy on the path, and the other holder deletes its copy at the next encounter through `CATCH_UP`.
- [ ] The same scenario passes in `Unsecured` and `PostQuantum`.
- [ ] Unit tests: a tombstone with a wrong `k` deletes nothing and is not stored; an unsolicited `CATCH_UP` tombstone is ignored; the 1,001st entry drops the oldest; entries expire at the right `expiry`.
- [ ] Retries of one message carry the same `k` and commitment.
- [ ] T10: a flood of forged or unmatched tombstones neither deletes held copies (in secured profiles) nor grows storage past 1,000 entries.
- [ ] [observer-exposure.md](../../../docs/reference/observer-exposure.md) "Tombstone (in a receipt)" row matches the code.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [docs/reference/observer-exposure.md](../../../docs/reference/observer-exposure.md) and [docs/reference/security-profile-guarantees.md](../../../docs/reference/security-profile-guarantees.md) only if the tombstone rows disagree with the code.

## Devices needed

None. Host and simulator only.
