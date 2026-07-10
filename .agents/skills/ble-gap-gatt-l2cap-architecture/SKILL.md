---
name: ble-gap-gatt-l2cap-architecture
description: "BLE protocol stack architecture reference covering GAP (device discovery via advertising, scanning, connection establishment, Central/Peripheral roles), GATT (the services/characteristics/descriptors attribute database, read/write/subscribe operations, the ATT protocol between GATT Client and Server), and L2CAP (the routing and fragmentation/reassembly layer that splits GATT/ATT data to fit the radio's negotiated payload and reassembles it transparently). Platform-agnostic — applies to Android, iOS, and embedded/Zephyr BLE stacks alike. Use when explaining how BLE devices find, connect to, and exchange data with each other, diagnosing BLE throughput/latency/reliability issues, designing a GATT service/characteristic hierarchy, understanding why GATT operations feel slow, or asked what GAP, GATT, ATT, or L2CAP actually do. Source: https://argenox.com/blog/understanding-ble-gap-gatt-and-l2cap"
---

<essential_principles>

BLE communication is four steps, each owned by a stack layer: devices **announce themselves** (GAP), **connect** (GAP), **exchange structured data** (GATT/ATT), and that data gets **split and transported** to fit the radio (L2CAP). Most BLE confusion comes from not knowing which layer owns which behavior.

### GAP — finding and connecting

GAP owns advertising, scanning, and connection management — everything before two devices can exchange data.

- **Advertising**: small broadcast packets (device name, supported services, manufacturer data, connectable flags) sent periodically (20ms to several seconds) on three dedicated channels (37/38/39). Legacy advertising payload caps at **31 bytes** — advertising design is a real engineering tradeoff between discoverability, payload, and power. Bad advertising design means devices aren't found reliably; this is a common mistake, not just a config detail.
- **Scanning**: the Central configures scan interval/duration via GAP to listen for advertisements from the Peripheral.
- **Roles**: **Peripheral** advertises and waits for connections (usually low-power — sensors, wearables). **Central** scans and initiates connections (usually higher-power — phones, PCs).
- **Connection establishment**: the Central issues a GAP connection request; the stack negotiates connection parameters (interval, etc.) and both sides then communicate on periodic connection events. Once connected, GAP's job is done and GATT takes over.

### GATT — structuring and accessing data

GATT is the attribute **database** that defines what data exists on a device and how to read/write it — if GAP finds devices, GATT is what you talk to them about, and it defines your entire application interface for BLE work. A **GATT Client** (usually the phone) talks to the **GATT Server** (usually the peripheral) using the **Attribute Protocol (ATT)**.

Data is hierarchical:

```
Service: Device Information (0x180A)
   ├── Characteristic: Manufacturer Name
   └── Characteristic: Model Number
Service: Heart Rate (0x180D)
   ├── Characteristic: Heart Rate Measurement [Notify]
   └── Characteristic: Body Sensor Location
Service: Battery Service (0x180F)
   └── Characteristic: Battery Level [Read, Notify]
```

- **Service** — groups related characteristics (Heart Rate, Battery, Device Information, ...).
- **Characteristic** — the actual value exchanged (Heart Rate Value, Battery Level).
- **Descriptor** (optional) — metadata about a characteristic: units, configuration, etc.

Once connected, a Central can **Read**, **Write**, or **Subscribe** (notifications — the Peripheral pushes updates whenever the underlying value changes) to a characteristic. GATT's structure is fixed (services/characteristics/descriptors) but the *data itself* is often left generic — many designs use a generic service/characteristic pair as a raw pipe, leaving the actual payload format up to the application layer.

### L2CAP — moving the data

GATT doesn't talk to the radio and doesn't know how data actually gets transported — that's L2CAP's (Logical Link Control and Adaptation Protocol) job. Data flows `GATT → ATT → L2CAP → Link Layer → Radio`.

L2CAP does two things:

1. **Routes** packets to the right destination — GATT isn't the only consumer of BLE packets (the Security Manager is another), so L2CAP delivers ATT packets to the GATT Service/Client specifically.
2. **Fragments and reassembles** — the radio's negotiated payload (as little as 27 bytes originally) is usually smaller than a GATT write/notification value. L2CAP splits larger data into radio-sized packets on the way out and reassembles them on the way in, **transparently to GATT** — GATT never knows fragmentation happened.

You don't directly configure L2CAP, but it drives throughput, latency, and reliability because every fragment carries routing/reassembly overhead, which eats into usable payload per packet. Symptoms of L2CAP/radio-layer trouble: slow transfers (small packets or poor timing), dropped data (fragmentation problems), or "weird" behavior (MTU/buffer mismatches).

### Common mistakes

- **Blaming GATT for performance problems** — GATT rarely controls latency/throughput directly; the real levers are L2CAP fragmentation overhead, MTU size, and connection interval.
- **Treating advertising as an afterthought** — poor advertising design (payload choices, interval) is why devices aren't discovered reliably; it deserves the same design attention as the GATT table.
- **Overcomplicating the GATT table** — too many services, or a data layout that forces unnecessary fragmentation, both hurt maintainability and performance. Design the service/characteristic hierarchy deliberately, sized to fit within as few L2CAP fragments as practical.

</essential_principles>
