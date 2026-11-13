# Keep the installation identity in the platform keystore

Status: ready-for-agent
Type: AFK
Blocked by: 12

## Behavior

A node's installation identity (one Ed25519 key pair, ticket 09) survives app restarts and process death on real phones: the next `start()` after a relaunch yields the same NodeId. The 32 B Ed25519 seed is protected at rest by the platform keystore behind the internal identity store port that ticket 09 introduced with an in-memory store:

- **Android:** the seed is encrypted with a non-exportable AES-256-GCM key held in Android Keystore, with no user authentication, in StrongBox when the device has it (preferred, not required). The ciphertext lives in no-backup app storage (`Context.getNoBackupFilesDir()`).
- **iOS:** the seed is a generic-password Keychain item with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable` false, so it is available to background BLE after first unlock and never leaves the device.
- **Backups:** both stores are excluded from backups, so a device restored from a backup gets a new identity, never a clone.
- **Failure:** a stored key that exists but cannot be read, decrypted or parsed is never replaced silently (Constitution S4). `start()` reports `MeshState.Blocked` with a new `IdentityUnavailable` requirement and leaves the stored item untouched. The app recovers only by calling the new explicit `resetIdentity()`, which creates a fresh identity; peers see a key change (ADR 0003).

These are owner decisions (2026-11-13). Android Keystore and the Secure Enclave cannot hold an Ed25519 key on every supported OS version, and libsodium needs the seed bytes to sign, so the keystore wraps the seed instead of holding the key.

## Spec and ADR references

- [ADR 0003](../../../docs/adr/0003-installation-identity-key-model.md) decision (one Ed25519 identity; identity changes only through an identity reset, which peers see as a key change; keys at rest protected by the platform keystore) and the wire-format amendment (NodeId). This ticket amends it with the storage design.
- [ADR 0001](../../../docs/adr/0001-threat-model-and-profile-guarantees.md) decision (identity reset, keys at rest in the platform keystore).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 1 (`platform` wraps a `Context` on Android), 2 (`MeshState`) and 3 (`Blocked` requirements; MeshLink leaves `Blocked` by itself once requirements are met). This ticket amends it with `IdentityUnavailable` and `resetIdentity()`.
- Spec: [NodeId](../../../docs/spec/identity.md#nodeid).
- [Constitution](../../../CONSTITUTION.md) S1, S2, S3, S4, S5, O3 and T10.

## Acceptance criteria

- [ ] A dated ADR 0003 amendment (version bump) records the storage design above: Android Keystore AES-256-GCM wrapping key (non-exportable, no user authentication, StrongBox when available), no-backup storage, the iOS Keychain item attributes, backup exclusion, and "unreadable means blocked, never replaced". It merges in this PR before or with the code (O3).
- [ ] A dated ADR 0007 amendment (version bump) adds the `IdentityUnavailable` requirement to decision 3 and the explicit `resetIdentity()` call: it is the only way out of `IdentityUnavailable` (the exception to "leaves `Blocked` by itself"), it deletes the stored item and creates a new identity, and it is allowed only while the node is stopped or blocked, with a declared `MeshLinkException` otherwise.
- [ ] `androidMain` and `iosMain` implementations of the identity store port follow the amendment; the seed and private key never appear in logs, exceptions or diagnostics (S1), and plaintext seed buffers are wiped after use where the platform allows it.
- [ ] Android stores the file under no-backup storage and the Keychain item is non-synchronizable with the `ThisDeviceOnly` accessibility class; tests assert the attributes passed to the platform calls.
- [ ] Tests: create, restart and reload yields the same NodeId; `resetIdentity()` yields a new NodeId.
- [ ] T10 cases, each giving `Blocked(IdentityUnavailable)`, the stored item unchanged, and no new identity until `resetIdentity()` (S4, S5): a corrupt or truncated ciphertext blob; a ciphertext whose Keystore key is missing (deleted or invalidated); a restore where the stored blob is present but its Keystore key is not (the backup case if the exclusion ever fails). On iOS: a Keychain item that cannot be parsed. A first start with no stored item at all creates an identity normally.
- [ ] Android tests run under Robolectric on SDK 26 and 37 with Kover 100%; iOS tests run on the simulator, with the thin Keychain calls listed in the CI-named file.
- [ ] `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `python3 tools/check_adrs.py` pass.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*IdentityStore*'
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
python3 tools/check_adrs.py
```

## Docs to update

- `docs/adr/0003-installation-identity-key-model.md`: dated amendment with the storage design (version bump).
- `docs/adr/0007-public-api-shape.md`: dated amendment with `IdentityUnavailable` and `resetIdentity()` (version bump).
- `PROJECT.md`: proof-matrix rows for both stores.
- `CHANGELOG.md`: persistent identity, `IdentityUnavailable` and `resetIdentity()` entries.

## Devices needed

None for this ticket. Restart survival on phones is checked in ticket 17.
