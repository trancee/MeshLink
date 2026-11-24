# Finding iPhones in the background

This page explains how an Android node finds an iPhone whose MeshLink app is in the background, why it works that way, and what it can and cannot tell. The decisions are in [ADR 0010](../adr/0010-discovery-and-advertising.md): decision 5, and the 2026-11-18 and 2026-11-23 amendments. The bytes a node reads once it has connected are in the [discovery spec](../spec/discovery.md). Terms follow [`GLOSSARY.md`](../../GLOSSARY.md).

## The problem: iOS hides our service UUIDs

In the foreground, an iPhone advertises like any other node: the MESH service UUID and a rotating 16-byte payload that carries its identifier. Once the app goes to the background, iOS stops sending both. Apple's documentation says only that "all service UUIDs are in the overflow area" and that they are "discoverable only by an iOS device explicitly scanning for them". Apple does not document the format.

So a background iPhone sends no MeshLink payload, no identifier and no readable service UUID. If Android could not read the overflow area, it could never find an iPhone whose user has switched apps or locked the screen, and a mixed mesh would split whenever the iPhones went quiet.

## What the overflow area is

The overflow area is part of Apple's manufacturer data (company `0x004C`). Apple manufacturer data is a list of sub-fields, each with a type, a length and data. The overflow sub-field has type `0x01`, no length byte, and a 16-byte bitmap: 128 bits for the whole phone.

Every service UUID that any app on the phone advertises in the background sets one bit. The bit is a hash of the UUID: CRC-8 with polynomial `0x09` over the 16 UUID bytes, keeping the low 7 bits. Nobody had published this hash. MeshLink's research derived it and confirmed it on devices.

- It reproduces David G. Young's empirical table (iOS 13), and MeshLink's own captures on iOS 15.8 to 27.0.
- On an iPhone 15 (iOS 17), seven chosen UUIDs set exactly the predicted bits. That run also settled how iOS widens short UUIDs. A 16-bit UUID is expanded with the Bluetooth Base UUID. A 32-bit UUID, like MESH (`4D455348`), is zero-padded on the right.
- MESH therefore lands on bit 123: the last bitmap byte, mask `0x08`.

The scanner computes bit 123 from the MESH UUID rather than storing a magic `0x08`, and a unit test pins the result.

## How Android reads it

Android cannot ask the radio for "Apple data with bit 123 set". A `ScanFilter` compares manufacturer data from its first byte, and Android 15 and later merge all of a company's manufacturer data into one array. A background iPhone sends Nearby Info (`10 05 …`) before the overflow area, so a filter that expects `0x01` at the start drops every background iPhone on current Android. Android 14 and earlier keep only the last entry instead, so the same filter happened to work on older phones.

MeshLink therefore filters on Apple's company ID alone, with empty data. It then reads the raw advertisement bytes itself:

1. Walk each AD structure.
2. In each Apple manufacturer-data structure, walk the sub-fields until type `0x01`.
3. Test bit 123 of the 16 bytes that follow.

Reading the raw bytes makes the result the same on every Android release, whichever way it merges manufacturer data.

The scanner finds the overflow even when another Apple sub-field comes before it in the same structure. Every capture so far had the overflow as the first sub-field, so this guards against a layout no one has seen yet. It costs a few bytes of parsing.

## A bit is a hint, not an identity

A set bit says "some app on this phone advertises a UUID that hashes to 123". It doesn't say which node the phone is, or even that it runs MeshLink.

- **Collisions:** the 128 bits are shared by every app and system service on the phone. Each other UUID present has a 1 in 128 chance of also landing on bit 123.
- **Stale bits:** an iPhone keeps the scan response of its last app advertisement on air for at least 26 minutes after the app stops, so bit 123 can outlive MeshLink on that phone.

So the core treats such a sighting as an identifier-less candidate. It connects over GATT, reads the `ADVERTISEMENT` entry of the capability record, and checks it like any received payload. If the check fails, the address is marked stale. Sightings of a stale address are ignored until it has gone unseen for 10 minutes. A wrong hint therefore costs one connection per Bluetooth address, not one per advertisement.

Scrubbing the stale bit from the iPhone side, by briefly advertising another UUID when the app stops, was considered and rejected (ADR 0010, Alternatives). It works only while the app still runs, and it adds a second UUID to the phone's footprint.

## What it costs

Filtering on Apple's company ID wakes the scanner for every nearby Apple device, not only MeshLink peers. On a Pixel 7 Pro running Android 16 with the screen off, that meant about 3,100 scan results per 300 s window instead of none. Power rails read through Perfetto showed no extra radio power and about 2 mW more CPU. Android 15 and Android 17 have not been measured (ADR 0010, risk "Android 17 scan limits").

## iPhone to iPhone

An iPhone that scans for MESH sees another iPhone's overflow bit, because iOS decodes the bitmap for the UUIDs a scanner asks for. iOS reports which of the scanner's own UUIDs matched, not the advertiser's real UUIDs. It works reliably only with the scanning iPhone in the foreground. In the lab, a foreground scanner found a background iPhone in 21 of 21 trials, and a background scanner in 13 of 21. Two background iPhones find each other only opportunistically. The same rule applies as on Android: the sighting carries no identifier, and the core vets it over GATT.

## The resulting picture

Think of the overflow bit as a doorbell, not a name tag. It tells an Android node that a phone nearby might be a MeshLink peer, cheaply and in a way that works on every Android release. Who the peer is comes only from the GATT connection that follows. Most of the design follows from that split: a broad filter, a parser that does not trust offsets, a bit computed from the UUID, and a stale-address rule that limits the cost of wrong guesses.

Open device questions are listed in the [research note](../research/2026-11-23-ios-overflow-area-android.md) §8 and §9: whether iOS ever places another Apple sub-field before the overflow, which app sets the foreign bit 52 seen on lab phones, and how Android 15's merge flag is rolled out.
