---
name: android-ble-inspector-patterns
description: Android BLE inspector/central-and-peripheral implementation patterns from a real-device-tested Jetpack Compose BLE tool. Covers modeling permissions by workflow profile (Scanner/Central/Peripheral/All), wrapping BluetoothLeScanner in a callbackFlow, serializing GATT operations with a Mutex + CompletableDeferred and generous timeouts (reads can take 8+ seconds on real peripherals), modeling notify/indicate subscription as its own Starting/Active/Stopping state, advertising packet-size limits (31-byte primary packet, splitting service UUID vs local name), the temporary-adapter-name workaround for advertised name, parsing Heart Rate (2A37) and Blood Pressure (2A35) IEEE-11073 SFLOAT values, and which BLE logic is worth JVM unit testing. Use when designing a BLE scanner/inspector UI, modeling GATT operation queuing with coroutines, implementing notification subscription state, building a BLE peripheral/advertiser, parsing Bluetooth SIG health characteristics, or deciding what BLE code to unit test.
---

<essential_principles>

Source: https://gist.githubusercontent.com/roywatson/7ce389862e245a622dba2e34e28331e6/raw/506e408d1fa9f7616c4b4c00d7efb43ddd414cd8/android_ble_tools.md

Android BLE's API surface reads as a simple sequence of calls, but real peripherals expose timing, ordering, and state-transition behavior the happy-path description hides. Model those behaviors explicitly rather than assuming the API is synchronous or instantaneous.

### Model permissions by workflow, not by app

Request permissions per BLE **profile** the current screen actually needs, not everything at launch — it reads as more trustworthy and keeps the "why does this need location" question answerable:

```kotlin
enum class BlePermissionProfile { Scanner, Central, Peripheral, All }
```

Scanning needs `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`; connecting needs `BLUETOOTH_CONNECT`; advertising needs `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT`. Pre-Android-12 still needs `ACCESS_FINE_LOCATION` for scanning.

### Wrap the scanner in a callbackFlow

`BluetoothLeScanner.startScan(filters, settings, callback)` is callback-based; bridge it to a coroutine `Flow` so cancellation (leaving the screen, tapping Stop) stops the scan automatically via `awaitClose`:

```kotlin
override fun scan(filterServiceUuids: List<String>): Flow<BleDevice> = callbackFlow {
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) { trySend(result.toBleDevice()) }
        override fun onScanFailed(errorCode: Int) { close(IllegalStateException("BLE scan failed with error code $errorCode")) }
    }
    leScanner.startScan(filters, settings, callback)
    awaitClose { runCatching { leScanner.stopScan(callback) } }
}
```

`SCAN_MODE_LOW_LATENCY` fits an interactive foreground tool; it is the wrong default for background/long-running scanning — always pick a scan mode deliberately, not by default.

### Serialize GATT operations with a Mutex, and give them room to be slow

`discoverServices()`, `readCharacteristic()`, `writeCharacteristic()`, `writeDescriptor()` look independent but share one underlying connection — starting a second operation before the first's callback lands can fail immediately or collide with a retry, producing the classic "first tap does nothing, second tap works" flakiness. Serialize with a `Mutex` around a `CompletableDeferred`-based await:

```kotlin
private val operationMutex = Mutex()

override suspend fun readCharacteristic(characteristic: BleCharacteristic): ByteArray =
    operationMutex.withLock {
        val gattChar = findGattCharacteristic(characteristic.uuid) ?: error("not found")
        val deferred = CompletableDeferred<ByteArray>()
        pendingRead = deferred
        try {
            check(gatt?.readCharacteristic(gattChar) ?: error("Not connected")) { "Read could not be started" }
            awaitGattOperation(deferred, "Read timed out")
        } finally {
            if (pendingRead === deferred) pendingRead = null
        }
    }
```

Give the timeout real headroom — a real Polar H10 took 8+ seconds to return a Device Name read on first access. Retrying too early doesn't help; it collides with the still-running operation. If the operation still times out, close the GATT connection rather than continuing to reuse a connection the stack may still consider busy. Surface the wait in the UI (disable the action, show a spinner) — an operation that gives no feedback for 5-8 seconds looks broken even when it's working correctly.

### Model notification subscription as its own state machine

Subscribing to a notify/indicate characteristic requires both local routing (`setCharacteristicNotification`) *and* a remote CCCD (`2902`) descriptor write (`0x0001` notify / `0x0002` indicate) — "button tapped" is not "subscription active." Model the gap explicitly:

```kotlin
data class CharacteristicObservation(val values: Flow<ByteArray>, val subscribed: Deferred<Unit>)
enum class ObservationStatus { Starting, Active, Stopping }
```

`subscribed` completes only once the CCCD write callback succeeds; the UI shows `Starting` until then. Unsubscribing is a GATT operation too — write the CCCD disable value and await its callback before clearing local state, the same as any other serialized operation.

### Advertising packet-size limits

The primary advertising packet is capped at 31 bytes — a service UUID plus a device name together commonly trigger `ADVERTISE_FAILED_DATA_TOO_LARGE`. Split them: service UUID in the advertisement, name in the scan response.

```kotlin
val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid.fromString(config.serviceUuid)).setIncludeDeviceName(false).build()
val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
```

There is no API to set an arbitrary per-advertisement local name — `setIncludeDeviceName(true)` always advertises the adapter's current Bluetooth name. To advertise a custom name, temporarily rename the adapter before advertising and restore it on cleanup; guard this carefully, since a crash before cleanup leaves the adapter's name changed until the user resets it or toggles Bluetooth. Treat this as a demo-only workaround, not a production pattern.

### Parsing standard health characteristics

Heart Rate Measurement (`2A37`) flags byte bit 0 selects 8-bit vs 16-bit value:

```kotlin
fun parseHeartRate(bytes: ByteArray): Int? {
    if (bytes.size < 2) return null
    val flags = bytes[0].toInt() and 0xFF
    return if ((flags and 0x01) != 0) {
        if (bytes.size < 3) return null
        ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
    } else bytes[1].toInt() and 0xFF
}
```

Blood Pressure Measurement (`2A35`) encodes values as IEEE-11073 **SFLOAT**: 16 bits, 12-bit signed mantissa + 4-bit signed exponent:

```kotlin
internal fun parseSFloat(bytes: ByteArray, offset: Int): Float {
    val raw = ((bytes[offset + 1].toInt() and 0xFF) shl 8) or (bytes[offset].toInt() and 0xFF)
    val rawMantissa = raw and 0x0FFF
    val mantissa = if (rawMantissa >= 0x0800) rawMantissa - 0x1000 else rawMantissa
    val rawExp = (raw ushr 12) and 0x0F
    val exponent = if (rawExp >= 0x08) rawExp - 0x10 else rawExp
    return (mantissa * 10.0.pow(exponent.toDouble())).toFloat()
}
```

Always show raw hex/ASCII bytes alongside any decoded value — decoding is a convenience, the bytes are ground truth, and an inspector tool that hides them loses its diagnostic value.

### What to unit test vs. what needs real hardware

The Bluetooth stack itself resists unit testing — timing behavior differs per peripheral and only shows up on physical devices. Reserve JVM unit tests for deterministic, hardware-independent logic: UUID normalization/matching, heart-rate and blood-pressure parsing, SFLOAT decoding, characteristic-name lookup, raw hex/ASCII formatting. These are a guardrail against parsing regressions, not a substitute for testing against real peripherals — GATT timing, serialization behavior, and subscription setup can only be validated on hardware.

</essential_principles>
