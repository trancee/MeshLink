# Core Bluetooth Programming Guide

Source: Apple Documentation Archive — Core Bluetooth Programming Guide

<overview>
## Core Bluetooth Overview

Core Bluetooth is an abstraction of the Bluetooth 4.0 specification for BLE. Two key players: **central** (client — scans, connects, reads/writes) and **peripheral** (server — advertises, hosts services, responds to requests).

### GATT Data Hierarchy

A peripheral's data is organized as a tree:

```
Peripheral
├── Service (identified by CBUUID)
│   ├── Characteristic (identified by CBUUID)
│   │   ├── Value (Data)
│   │   └── Descriptor(s)
│   ├── Characteristic ...
│   └── Included Service (reference to another service)
└── Service ...
```

- **Services** = collections of data and behaviors for a feature
- **Characteristics** = atomic values within a service (e.g., heart rate measurement)
- **Descriptors** = metadata about a characteristic (e.g., human-readable description)

### Object Mapping

| BLE Concept | Central-Side Object | Peripheral-Side Object |
|-------------|--------------------|-----------------------|
| Local manager | `CBCentralManager` | `CBPeripheralManager` |
| Remote device | `CBPeripheral` | `CBCentral` |
| Service | `CBService` (read-only) | `CBMutableService` |
| Characteristic | `CBCharacteristic` (read-only) | `CBMutableCharacteristic` |

Central side uses immutable `CBService`/`CBCharacteristic` (discovered from remote).
Peripheral side uses mutable versions (created locally and published).
</overview>

<central_role_tasks>
## Performing Common Central Role Tasks

### Step 1: Start Up a Central Manager

```objc
myCentralManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil options:nil];
```

The central manager immediately calls `centralManagerDidUpdateState:`. **You must implement this** and check for `.poweredOn` before calling any other method.

### Step 2: Scan for Advertising Peripherals

```objc
// Scan for peripherals advertising specific services
[myCentralManager scanForPeripheralsWithServices:@[heartRateServiceUUID] options:nil];

// nil = discover ALL peripherals (not recommended for production)
[myCentralManager scanForPeripheralsWithServices:nil options:nil];
```

Each discovered peripheral fires the delegate callback:

```objc
- (void)centralManager:(CBCentralManager *)central
 didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary *)advertisementData
                  RSSI:(NSNumber *)RSSI {
    // MUST retain the peripheral — framework does not retain it for you
    self.discoveredPeripheral = peripheral;
}
```

**Stop scanning once you've found your target** — scanning uses the radio.

### Step 3: Connect

```objc
[myCentralManager connectPeripheral:peripheral options:nil];
```

Success callback:

```objc
- (void)centralManager:(CBCentralManager *)central
  didConnectPeripheral:(CBPeripheral *)peripheral {
    peripheral.delegate = self;  // set delegate BEFORE discovering services
    [peripheral discoverServices:@[heartRateServiceUUID]];
}
```

### Step 4: Discover Services

```objc
// Pass specific UUIDs — discovering all services wastes radio and battery
[peripheral discoverServices:@[firstServiceUUID, secondServiceUUID]];
```

Callback:

```objc
- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
    for (CBService *service in peripheral.services) {
        [peripheral discoverCharacteristics:@[charUUID] forService:service];
    }
}
```

### Step 5: Discover Characteristics

```objc
- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverCharacteristicsForService:(CBService *)service error:(NSError *)error {
    for (CBCharacteristic *characteristic in service.characteristics) {
        // Now read, write, or subscribe
    }
}
```

### Step 6: Read or Subscribe

**Polling (read once):**

```objc
[peripheral readValueForCharacteristic:characteristic];
// Result delivered to peripheral:didUpdateValueForCharacteristic:error:
```

**Subscribing (notifications — preferred for changing values):**

```objc
[peripheral setNotifyValue:YES forCharacteristic:characteristic];
// Same callback: peripheral:didUpdateValueForCharacteristic:error:
// Also fires: peripheral:didUpdateNotificationStateForCharacteristic:error:
```

Both read and notification deliver data via the same delegate method:

```objc
- (void)peripheral:(CBPeripheral *)peripheral
didUpdateValueForCharacteristic:(CBCharacteristic *)characteristic error:(NSError *)error {
    NSData *data = characteristic.value;
}
```

### Step 7: Write

```objc
// With response (acknowledged)
[peripheral writeValue:data forCharacteristic:characteristic
                  type:CBCharacteristicWriteWithResponse];
// Callback: peripheral:didWriteValueForCharacteristic:error:

// Without response (fire-and-forget — faster)
[peripheral writeValue:data forCharacteristic:characteristic
                  type:CBCharacteristicWriteWithoutResponse];
// No callback, but check: characteristic.properties & CBCharacteristicPropertyWriteWithoutResponse
```

Always check the characteristic's `properties` before reading/writing/subscribing — the characteristic must support the operation.
</central_role_tasks>

<peripheral_role_tasks>
## Performing Common Peripheral Role Tasks

### Step 1: Start Up a Peripheral Manager

```objc
myPeripheralManager = [[CBPeripheralManager alloc] initWithDelegate:self queue:nil options:nil];
```

Wait for `peripheralManagerDidUpdateState:` with `.poweredOn`.

### Step 2: Build the Service/Characteristic Tree

**UUIDs:** Bluetooth SIG defines 16-bit shortened UUIDs for standard services (e.g., `180D` = Heart Rate). For custom services, generate 128-bit UUIDs with `uuidgen`:

```bash
$ uuidgen
71DA3FD1-7E10-41C1-B16F-4430B506CDE7
```

```objc
CBUUID *charUUID = [CBUUID UUIDWithString:@"71DA3FD1-7E10-41C1-B16F-4430B506CDE7"];

// Create a characteristic
CBMutableCharacteristic *myChar = [[CBMutableCharacteristic alloc]
    initWithType:charUUID
    properties:CBCharacteristicPropertyRead | CBCharacteristicPropertyNotify
    value:nil              // nil = DYNAMIC (delegate callbacks handle reads)
    permissions:CBAttributePermissionsReadable];

// If value is non-nil → STATIC: cached by framework, no delegate callbacks,
// cannot be written by centrals, cannot be updated after service is published

// Create a service and attach characteristics
CBMutableService *myService = [[CBMutableService alloc] initWithType:serviceUUID primary:YES];
myService.characteristics = @[myChar];
```

**Critical rule:** Set `value` to `nil` for any characteristic whose value is dynamic (changes over time, needs delegate callbacks for reads). A non-nil value is cached permanently and cannot be changed.

### Step 3: Publish Services

```objc
[myPeripheralManager addService:myService];
// Wait for peripheralManager:didAddService:error: before adding more
```

Only add the next service after `didAddService:` confirms the previous one succeeded.

### Step 4: Advertise

```objc
[myPeripheralManager startAdvertising:@{
    CBAdvertisementDataLocalNameKey: @"MyDevice",
    CBAdvertisementDataServiceUUIDsKey: @[myService.UUID]
}];
// Only these two keys are honored for BLE advertising
```

### Step 5: Respond to Read Requests

```objc
- (void)peripheralManager:(CBPeripheralManager *)peripheral
    didReceiveReadRequest:(CBATTRequest *)request {
    if ([request.characteristic.UUID isEqual:myCharUUID]) {
        // Handle offset for long values
        if (request.offset > myData.length) {
            [peripheral respondToRequest:request withResult:CBATTErrorInvalidOffset];
            return;
        }
        request.value = [myData subdataWithRange:NSMakeRange(request.offset, myData.length - request.offset)];
        [peripheral respondToRequest:request withResult:CBATTErrorSuccess];
    }
}
```

### Step 6: Respond to Write Requests

```objc
- (void)peripheralManager:(CBPeripheralManager *)peripheral
  didReceiveWriteRequests:(NSArray<CBATTRequest *> *)requests {
    for (CBATTRequest *request in requests) {
        // Process each request.value
    }
    // Respond to the FIRST request only — this determines the result for ALL
    [peripheral respondToRequest:requests[0] withResult:CBATTErrorSuccess];
}
```

### Step 7: Send Notifications to Subscribed Centrals

Track subscriptions:

```objc
- (void)peripheralManager:(CBPeripheralManager *)peripheral
                  central:(CBCentral *)central
didSubscribeToCharacteristic:(CBCharacteristic *)characteristic {
    // Start sending updates to this central
}
```

Send updates:

```objc
BOOL didSend = [myPeripheralManager updateValue:data
    forCharacteristic:myChar onSubscribedCentrals:nil]; // nil = all subscribers

if (!didSend) {
    // Queue is full — stop sending and wait for:
    // peripheralManagerIsReadyToUpdateSubscribers: callback
}
```
</peripheral_role_tasks>

<background_processing>
## Background Processing for iOS Apps

### Foreground-Only Apps (Default)

- **Central side**: cannot scan or discover peripherals in background
- **Peripheral side**: advertising disabled, dynamic characteristics return error
- All BLE events are **queued** and delivered when app returns to foreground
- Existing connections remain but no new events are processed

### Peripheral Connection Options (Foreground-Only Workaround)

Use these when connecting to get system alerts even while suspended:

```objc
[myCentralManager connectPeripheral:peripheral options:@{
    CBConnectPeripheralOptionNotifyOnConnectionKey: @YES,       // alert on connect
    CBConnectPeripheralOptionNotifyOnDisconnectionKey: @YES,    // alert on disconnect
    CBConnectPeripheralOptionNotifyOnNotificationKey: @YES      // alert on notifications
}];
```

### Background Execution Modes

Declare in Info.plist `UIBackgroundModes` array:

| Mode | Key | What It Enables |
|------|-----|----------------|
| Central | `bluetooth-central` | Scan for peripherals, process discovery/connect/disconnect events, read/write/subscribe |
| Peripheral | `bluetooth-peripheral` | Advertise (limited), respond to requests, send notifications |

**Behavioral differences in background:**

| Feature | Foreground | Background |
|---------|-----------|------------|
| `AllowDuplicatesKey` | Honored | **Ignored** (always coalesced) |
| Scan without service UUIDs | Allowed | **Not allowed** |
| Scan discovery events | Immediate | **Batched/coalesced** |
| Advertising data | Local name + service UUIDs (28 bytes + 10 scan response) | **Service UUIDs only in overflow area** |
| Connection events | Immediate | Wakes app to handle |

### State Preservation and Restoration (iOS 7+)

Allows system to relaunch a terminated app and restore BLE state. **Four steps:**

#### 1. Opt In — Provide Restore Identifier

```objc
// Central
myCentralManager = [[CBCentralManager alloc] initWithDelegate:self queue:nil options:@{
    CBCentralManagerOptionRestoreIdentifierKey: @"myCentralManagerIdentifier"
}];

// Peripheral
myPeripheralManager = [[CBPeripheralManager alloc] initWithDelegate:self queue:nil options:@{
    CBPeripheralManagerOptionRestoreIdentifierKey: @"myPeripheralManagerIdentifier"
}];
```

**Must use the same restore identifier** every time you create the manager.

#### 2. Reinstantiate Managers in App Delegate

When system relaunches your app, the launch options contain the restore identifiers:

```objc
- (BOOL)application:(UIApplication *)application
didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    NSArray *centralManagerIdentifiers = launchOptions[UIApplicationLaunchOptionsBluetoothCentralsKey];
    NSArray *peripheralManagerIdentifiers = launchOptions[UIApplicationLaunchOptionsBluetoothPeripheralsKey];
    // Reinstantiate each manager with the SAME identifier it was created with
}
```

#### 3. Implement willRestoreState Delegate Method

```objc
// Central
- (void)centralManager:(CBCentralManager *)central willRestoreState:(NSDictionary *)state {
    NSArray *peripherals = state[CBCentralManagerRestoredStatePeripheralsKey];
    // Reassign delegates to restored peripherals
    for (CBPeripheral *peripheral in peripherals) {
        peripheral.delegate = self;
    }
}

// Peripheral
- (void)peripheralManager:(CBPeripheralManager *)peripheral willRestoreState:(NSDictionary *)state {
    NSArray *services = state[CBPeripheralManagerRestoredStateServicesKey];
    // Services are restored — no need to re-add them
}
```

**Central restore dict keys:** `CBCentralManagerRestoredStatePeripheralsKey`, `CBCentralManagerRestoredStateScanServicesKey`, `CBCentralManagerRestoredStateScanOptionsKey`

**Peripheral restore dict keys:** `CBPeripheralManagerRestoredStateServicesKey`, `CBPeripheralManagerRestoredStateAdvertisementDataKey`

#### 4. Update Initialization Process

After `willRestoreState`, the system calls `centralManagerDidUpdateState:` / `peripheralManagerDidUpdateState:`. In that callback, check whether the restored state already covers what you need (e.g., already scanning, already connected) before duplicating work.
</background_processing>

<best_practices_central>
## Best Practices — Central Role

### Minimize Radio Usage

1. **Stop scanning as soon as you find your target** — `stopScan` immediately after discovery
2. **Never use `AllowDuplicatesKey` unless you specifically need per-packet RSSI** — it drastically increases radio usage and is ignored in background anyway
3. **Discover only the services you need** — pass specific UUIDs to `discoverServices:`, never `nil` in production
4. **Discover only the characteristics you need** — pass specific UUIDs to `discoverCharacteristics:forService:`
5. **Subscribe instead of polling** — `setNotifyValue:YES` is far more efficient than repeated `readValue` for data that changes often
6. **Disconnect when done** — call `cancelPeripheralConnection:` as soon as you have all the data you need

### Reconnecting to Known Peripherals

Three strategies for reconnecting, in order of preference:

#### Strategy 1: Retrieve Known Peripherals by Identifier

```objc
// If you saved the peripheral's identifier from a previous session
NSArray *peripherals = [myCentralManager retrievePeripheralsWithIdentifiers:@[savedUUID]];
if (peripherals.count > 0) {
    CBPeripheral *peripheral = peripherals[0];
    // Skip scanning — connect directly
    [myCentralManager connectPeripheral:peripheral options:nil];
}
```

Fastest method — no scanning required. Works if the peripheral was previously discovered.

#### Strategy 2: Retrieve Connected Peripherals by Service

```objc
// Check if any peripheral advertising the service is already connected to the system
NSArray *connected = [myCentralManager retrieveConnectedPeripheralsWithServices:@[serviceUUID]];
```

Returns peripherals connected to the system by other apps or the system itself. No scanning needed.

#### Strategy 3: Scan Again

If the above two methods don't return the peripheral, fall back to scanning:

```objc
[myCentralManager scanForPeripheralsWithServices:@[serviceUUID] options:nil];
```
</best_practices_central>

<best_practices_peripheral>
## Best Practices — Peripheral Role

### Advertising Limits

**Only two keys are honored** when advertising:
- `CBAdvertisementDataLocalNameKey` — device name
- `CBAdvertisementDataServiceUUIDsKey` — service UUIDs

**Foreground size budget:**
- 28 bytes for initial advertising data (name + service UUIDs combined)
- 10 additional bytes in scan response — local name only
- Each data type has 2 bytes of header overhead
- Service UUIDs that don't fit go to an **overflow area** — only discoverable by iOS devices explicitly scanning for those UUIDs

**Background advertising:**
- Local name is **not advertised**
- All service UUIDs placed in overflow area
- Advertising packet rate reduced by system

**Guidance:** Limit advertised UUIDs to primary services only. Stop advertising once a connection is established — connected centrals can discover all services directly.

### Let the User Control Advertising

Advertising drains battery. Provide UI controls for the user to start/stop advertising rather than advertising automatically.

### Characteristic Configuration

**Always support notifications** for characteristics that change:

```objc
CBMutableCharacteristic *myChar = [[CBMutableCharacteristic alloc]
    initWithType:charUUID
    properties:CBCharacteristicPropertyRead | CBCharacteristicPropertyNotify
    value:nil
    permissions:CBAttributePermissionsReadable];
```

This is both better for power (peripheral pushes changes vs central polling) and better UX (central receives updates immediately).

### Protect Sensitive Data with Encryption

For characteristics containing sensitive data, require a paired (encrypted) connection:

```objc
CBMutableCharacteristic *secureChar = [[CBMutableCharacteristic alloc]
    initWithType:charUUID
    properties:CBCharacteristicPropertyRead | CBCharacteristicPropertyNotify
    value:nil
    permissions:CBAttributePermissionsReadEncryptionRequired];  // requires pairing
```

When a central attempts to access a characteristic with `readEncryptionRequired` or `writeEncryptionRequired`, Core Bluetooth automatically initiates pairing if the devices aren't already paired. The read/write completes only after successful pairing.
</best_practices_peripheral>
