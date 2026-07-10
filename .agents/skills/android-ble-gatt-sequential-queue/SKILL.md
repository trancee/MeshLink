---
name: android-ble-gatt-sequential-queue
description: "Android BLE GATT race condition reference. Explains why calling BluetoothGatt methods (writeCharacteristic, readCharacteristic, setCharacteristicNotification, requestMtu) back-to-back without awaiting each BluetoothGattCallback causes dropped operations, corrupted stack state, or disconnects, and shows how to build a Kotlin Coroutines-based serialized operation queue (Channel + suspendCancellableCoroutine + CompletableDeferred + per-operation timeout) to force one GATT operation at a time. Use when debugging intermittent/non-deterministic BLE failures, GATT operations returning false or silently failing, BluetoothGatt disconnecting under load, or when designing a GattClient wrapper for sequential GATT access. Source: https://dev.to/ble_advertiser/solving-the-android-ble-gatt-race-condition-reliable-sequential-operations-with-kotlin-coroutines-k04"
---

<essential_principles>

**The GATT race**: every `BluetoothGatt` method (`writeCharacteristic`, `readCharacteristic`, `setCharacteristicNotification`, `requestMtu`, descriptor reads/writes) is **non-blocking** — it returns immediately and the real result arrives later via `BluetoothGattCallback`. `BluetoothGatt`'s internal state machine handles exactly one in-flight operation. Fire a second one before the first's callback lands and the stack can drop it, corrupt state for unrelated callbacks, return `false`, or disconnect outright — all silently, with no exception to catch.

**The fix**: force strict **one-at-a-time** execution through a serialized queue. Never call two `BluetoothGatt` methods without awaiting the first operation's callback in between.

### Building the queue

1. **`GattOperation` sealed class** — one discrete type per command (`ReadCharacteristic`, `WriteCharacteristic`, `SetCharacteristicNotification`, `RequestMtu`, ...). For any variant carrying a `ByteArray`, override `equals`/`hashCode` with `contentEquals`/`contentHashCode` — the default reference-based array equality silently breaks map lookups.
2. **`Channel<GattOperation>`** as the queue. A single coroutine consumes it in a `for (operation in channel)` loop, dispatches the matching `bluetoothGatt.xxx()` call, and blocks the loop (not the app) until that operation's callback completes before dequeuing the next.
3. **`CompletableDeferred<Unit>` per in-flight operation**, stored in a map keyed by the operation. The `BluetoothGattCallback` method looks up the deferred and completes it (`complete()` on `GATT_SUCCESS`, `completeExceptionally()` otherwise), which is what lets the queue loop — and the caller — resume.
4. **`suspendCancellableCoroutine`** in each public `suspend fun readCharacteristic(...)`/`writeCharacteristic(...)`/etc. bridges the callback API to a coroutine: create the deferred, send the operation to the channel, await the deferred, resume the continuation. Callers read like synchronous code even though every call is async underneath.
5. **A timeout on every operation** (`withTimeout`, ~5-15s) wrapping the deferred's `await()`. Without it, an unresponsive peripheral or a stack glitch that never fires a callback hangs the queue forever — one stuck operation blocks all future ones. On timeout, remove the entry from the completions map so a stray late callback doesn't touch a stale continuation.

### Gotchas

- **Callback thread**: `BluetoothGattCallback` runs on the main thread unless a `Handler` is passed to `connectGatt`. Never block it — dispatch heavy work to `Dispatchers.Default`/`Dispatchers.IO`.
- **Only queue when connected**: gate the queue processor on GATT being connected; operations submitted while disconnected should wait, be rejected, or trigger a reconnect — never silently execute against a stale `BluetoothGatt`.
- **Notifications are not queue operations**: `onCharacteristicChanged` is an *unsolicited* push from the peripheral, not a response to a command you issued — don't try to match it to a pending deferred or let it block the queue. Emit it on its own `SharedFlow`/`Channel` that callers collect independently. The *act* of enabling/disabling notifications (`setCharacteristicNotification` + writing the CCCD descriptor) *is* a queued operation, though.
- **Resource cleanup**: call `disconnect()` to end a session, then `close()` once `onConnectionStateChange` reports `STATE_DISCONNECTED` (or on connect failure) — leaking `BluetoothGatt` instances exhausts the platform's limited GATT client slots and causes later `connectGatt` calls to fail silently. Null out the reference after `close()`. When the connection drops, fail every pending deferred in the completions map — they can never complete otherwise.
- **Distinguish same-value/same-characteristic operations**: if the same `GattOperation` (same characteristic, same bytes) can be enqueued twice concurrently, the sealed-class equality can conflate them. Give each operation instance a unique id (UUID or atomic counter) and key the completions map by that id, not just the operation's structural equality — critical for writes on pre-API-33 devices where `onCharacteristicWrite` doesn't echo back the written value.
- **API 33+ callback overloads** (`onCharacteristicWrite(gatt, characteristic, value, status)`, `onCharacteristicChanged(gatt, characteristic, value, status)`) return the value directly — prefer them when minSdk allows, since older overloads require reading back from the characteristic object, which is less reliable under concurrent access.

</essential_principles>
