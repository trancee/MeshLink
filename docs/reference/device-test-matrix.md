# Device test matrix reference

Last verified: 2026-06-13

This page tracks the Android devices currently attached to the MeshLink test
bench and the device facts that matter for validation.

Use it when you need to:

- choose a device for a regression or integration run
- compare OEM behavior across Android versions and SDK levels
- add a new device to the fleet using the same reporting shape

Memory and storage values are rounded from the device-reported totals to the
nearest marketed tier.

## Source of truth

- `adb devices -l` for attached serials and transport details
- `adb shell getprop ...` for brand, manufacturer, model, Android version,
  SDK, and build details
- `adb shell cat /proc/meminfo` for RAM
- `adb shell df /storage/emulated/0` for internal storage size
- GSMArena or vendor spec pages for the human-readable device name, screen,
  chipset, and Bluetooth version

## Update rules

- Keep rows sorted by descending Android SDK, then by human-readable device
  name.
- Fill every column for every device. Use `unknown` only when a field cannot be
  verified.
- Fill memory and storage from the device-reported totals, then round to the
  marketed tier shown in the table.
- If GSMArena does not have a dedicated device page, link the most specific
  GSMArena search result or the vendor manual/spec page and note that in the
  quirks column.
- When you add a device, copy an existing row and update all fields in the same
  order so the table stays consistent.
- Re-run `adb devices -l`, `getprop`, `MemTotal`, and `df /storage/emulated/0`
  before replacing an existing row.

## Current devices

| Serial | Human-readable device | Brand | Model code | Android / SDK | Memory (RAM) | Storage | Bluetooth | Screen | Chipset | OEM skin | Supported crypto primitives | Known quirks / test notes | GSMArena |
|---|---|---|---|---:|---:|---:|---|---|---|---|---|---|---|
| `1f1dad34` | Nothing Phone (2) | Nothing | A065 | Android 16 / SDK 36 | 12 GB | 256 GB | 5.3 | 6.7" | Snapdragon 8+ Gen 1 | Nothing OS | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | Newest platform in the set; verify permission, background, and Bluetooth behavior on Android 16 specifically. | [GSMArena](https://www.gsmarena.com/nothing_phone_(2)-12386.php) |
| `adb-P2126T004912-Na69Lt._adb-tls-connect._tcp` | Nothing Phone (1) | Nothing | A063 | Android 15 / SDK 35 | 8 GB | 256 GB | 5.2 | 6.55" | Snapdragon 778G+ | Nothing OS | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | Same vendor family as Phone (2) but on Android 15; useful for comparing Nothing-specific behavior across major platform releases. | [GSMArena](https://www.gsmarena.com/nothing_phone_(1)-11636.php) |
| `7XHEIBPBLRJJSKFU` | realme C55 | realme | RMX3710 | Android 15 / SDK 35 | 8 GB | 256 GB | 5.2 | 6.72" | Helio G88 | realme UI | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | realme UI can be aggressive with background and battery limits; verify reconnects, foreground service survival, and notification delivery. | [GSMArena](https://www.gsmarena.com/realme_c55-12159.php) |
| `EQUGS85LJNEIO7Z5` | OPPO Reno8 5G | OPPO | CPH2359 | Android 14 / SDK 34 | 8 GB | 256 GB | 5.3 | 6.43" | Dimensity 1300 | ColorOS 14 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | ColorOS power management can affect background work; compare against Reno6 to spot OEM drift. | [GSMArena](https://www.gsmarena.com/oppo_reno8-11684.php) |
| `GX6CTR500184` | Gigaset GX6 | Gigaset | E940-2849-00 | Android 13 / SDK 33 | 6 GB | 128 GB | 5.2 | 6.6" | Dimensity 900 | Gigaset UI / near-stock Android | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | Rugged and uncommon OEM stack; verify pairing persistence, reconnect behavior, and idle wake-up. | [GSMArena search](https://www.gsmarena.com/results.php3?sQuickSearch=yes&sName=Gigaset%20GX6) |
| `ZHBQ95BIG6NVWSUK` | OPPO Reno6 5G | OPPO | CPH2251 | Android 13 / SDK 33 | 8 GB | 128 GB | 5.2 | 6.43" | Dimensity 900 | ColorOS | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | Current build is newer than the launch spec, so update-path behavior matters; compare against Reno8 for ColorOS differences. | [GSMArena](https://www.gsmarena.com/oppo_reno6_5g-10932.php) |
| `851ff431` | OnePlus 6 | OnePlus | ONEPLUS A6003 | Android 11 / SDK 30 | 8 GB | 128 GB | 5.0 | 6.28" | Snapdragon 845 | OxygenOS | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | Oldest API/device in the set and `nosdcard`; validate legacy storage, permission, and older Bluetooth-stack behavior. | [GSMArena](https://www.gsmarena.com/oneplus_6-9109.php) |

### Notes on the crypto column

The crypto column lists the baseline app-layer primitives that should be
validated on every device. It is not a guarantee of hardware-backed key
storage. Bluetooth LE link security also uses AES-CCM under the hood; hardware
acceleration and vendor keystore behavior can still vary by model and Android
build.

### How to add a new device

1. Connect the device and run `adb devices -l`.
2. Record the serial, brand, manufacturer, model, device codename, product
   name, Android release, SDK, RAM, and internal storage with `adb shell
   getprop`, `cat /proc/meminfo`, and `df /storage/emulated/0`.
3. Resolve the human-readable device name, Bluetooth version, and GSMArena link.
4. Copy one row in the table above and fill all columns in the same order.
5. Put the row in the correct sorted position: newest SDK first, then device
   name.
6. If a field cannot be verified, write `unknown` and add a note in the quirks
   column.
7. Re-run the lookup before replacing an existing row so the file stays current.
