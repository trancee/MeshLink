---
name: android-ble-gatt-status-133
description: Android BLE GATT connection-failure status codes reference (133 GATT_ERROR, 135, 147 GATT_CONNECTION_TIMEOUT). Explains that these are generic native-Bluetooth-stack connection failures surfaced in onConnectionStateChange during the CONNECTING phase, and covers causes and fixes — misuse of connectGatt's autoConnect parameter (false for direct connections vs true only for background reconnection), correct BluetoothGatt.close() timing (only after STATE_DISCONNECTED), avoiding concurrent scan/connect operations via an explicit state machine, keeping BluetoothGattCallback lean, exponential-backoff retries treating 133/147 as one retryable timeout family, 135 as a Resolvable Private Address expiry specific to background autoConnect, and diagnosing persistent stack-level failures via Bluetooth toggle/device restart. Use when debugging GATT status 133/135/147, connectGatt failures, BluetoothGatt.close() timing issues, or designing retry/reconnection logic for BLE central connections.
---

<essential_principles>

Source: https://dev.to/ble_advertiser/demystifying-android-ble-gatt-status-133-common-causes-and-robust-solutions-for-connection-32la

**GATT Status 133** (`BluetoothGatt.GATT_ERROR`, `0x85`) is a generic **connection failure** from the native Bluetooth stack (Bluedroid/Fluoride), not your app code — it surfaces in `onConnectionStateChange(gatt, status=133, newState=STATE_DISCONNECTED)` during the `CONNECTING` phase (or during a reconnection attempt from `CONNECTED`) and tells you only "the connection attempt failed," not why. It's rarely one root cause — treat it as a symptom of timing, resource-lifecycle, or concurrency bugs in your connection code, checked in this order.

### 1. Master `autoConnect` semantics

`device.connectGatt(context, autoConnect, callback)`'s second parameter is the most common source of `133`:

- **`autoConnect = false`** — direct, aggressive, time-bound connection attempt. Use for every user-initiated connection. If the device is unreachable it times out promptly and you get a fast, actionable `133`.
- **`autoConnect = true`** — opportunistic background reconnection; the system waits indefinitely for the device to reappear. Only use it *after* an initial successful `connectGatt(..., false, ...)` connection, for auto-reconnect when a bonded device drops out of range. Using it for an *initial* connection produces silent, unpredictable delays that eventually surface as `133`.

When the user explicitly disconnects, call `gatt.close()` immediately to break an `autoConnect = true` link, or the system keeps waiting.

### 2. `BluetoothGatt.close()` timing — only after STATE_DISCONNECTED

An unclosed or prematurely-closed `BluetoothGatt` is the second most common cause — it ties up a limited system resource and produces stale-state `133` errors on later connection attempts.

- Call `gatt.close()` **only** once `onConnectionStateChange` reports `STATE_DISCONNECTED` — regardless of the `status` code — or when explicitly done with the object.
- **Never** call `close()` while `STATE_CONNECTING` or `STATE_CONNECTED`; doing so races the native stack and can orphan internal resources.
- `disconnect()` should only call `gatt.disconnect()`; `close()` belongs inside the `STATE_DISCONNECTED` branch of the callback, not the `disconnect()` method itself.
- Nullify the `bluetoothGatt` reference right after `close()` to prevent reuse of a stale object.

```kotlin
override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
    when (newState) {
        BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
        BluetoothProfile.STATE_DISCONNECTED -> {
            if (status == 133) handleConnectionFailure(gatt, status) // transient — consider retry
            gatt.close()          // always close here, whatever the status
            bluetoothGatt = null
        }
    }
}
```

### 3. One BLE operation at a time — an explicit state machine

The Bluetooth stack is effectively single-threaded internally for critical operations. Concurrent scan+connect, connecting to multiple devices at once, or firing connection requests in rapid succession causes race conditions, dropped commands, and `133`.

- Model connection state explicitly: `IDLE`, `SCANNING`, `CONNECTING`, `CONNECTED`, `DISCONNECTING`.
- Always `stopScan()` before calling `connectGatt()`.
- Debounce/queue connection requests instead of issuing them back-to-back.
- Reject a new connect request if already `CONNECTING`/`CONNECTED` to the same device; disconnect first if targeting a different device.

### 4. Keep `BluetoothGattCallback` lean

Callback methods run on an internal binder thread. Blocking it with UI updates, DB writes, or heavy logic delays other BLE events and can trigger internal stack timeouts that look like `133`. Dispatch any real work (coroutines, `Handler`, `ExecutorService`) off the callback immediately; keep the callback itself to logging, state updates, and event dispatch.

### 5. Retry with exponential backoff

`133` is often transient. Wrap `connectGatt` in a bounded retry loop with exponential backoff (e.g. 2s, 4s, 8s...), tracking retry count per device and resetting it to zero on `STATE_CONNECTED`. Give up after a max-retries cap and surface a final failure to the caller rather than retrying forever. Guard against acting on a stale callback for a device you've already stopped tracking — compare the callback's device address against the currently-tracked one and `gatt.close()` any "rogue" object immediately.

### 6. Related status codes — 135 and 147

`133` is not the only connection-failure code worth special-casing; two others show up in the same `onConnectionStateChange(status=X, newState=STATE_DISCONNECTED)` shape and are best treated deliberately rather than left to fall through to a generic failure (cross-referenced from Nordic's `Kotlin-BLE-Library`, which distinguishes all three):

- **147** (`BluetoothGatt.GATT_CONNECTION_TIMEOUT`, added in API 35) is the platform's newer, more specific code for the same underlying condition `133` used to report on older Android versions — a direct (`autoConnect = false`) connection attempt that timed out (~30s internally) without ever reaching `STATE_CONNECTED`. Treat `133` and `147` as **one retryable family**: if your retry condition checks `status == 133`, add `147` alongside it rather than leaving devices on newer OS versions/API levels unretried.
- **135** shows up specifically during **`autoConnect = true`** background reconnection: a Resolvable Private Address (RPA) the system cached for background matching can rotate and "expire," so the background connection attempt fails with `133` or `135` even though the peripheral is reachable. The fix isn't a bounded retry of the same background attempt — it's falling back to a **direct** (`autoConnect = false`) connection, which resolves the current address instead of matching against the stale cached one. If every connection in your app is already direct (`autoConnect = false`), this specific case doesn't apply to you; it's only relevant to background/auto-connect flows.

### 7. When it's the stack, not your code

If `133` persists despite correct `autoConnect`, `close()` timing, serialized operations, and lean callbacks, suspect the native stack itself (rare but real): toggle `BluetoothAdapter.disable()`/`enable()` (production-unsafe — resets Bluetooth system-wide), restart the device, or check whether an unrelated app (e.g. nRF Connect) can connect — if nothing can connect, it's a system-level issue, not your code.

</essential_principles>
