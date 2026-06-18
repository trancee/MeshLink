# Device test matrix reference

Last verified: 2026-06-18

This page tracks the Android devices currently attached to the MeshLink test
bench and the device facts that matter for validation.

Use it when you need to:

- choose a device for a regression or integration run
- compare OEM behavior across Android versions and SDK levels
- add a new device to the fleet using the same reporting shape

This matrix is rebuilt from live adb probes:

- `adb devices -l` for the current attached inventory
- `./tools/android device info --device <serial> --json` for model, Android,
  and display facts
- `adb shell getprop`, `cat /proc/meminfo`, `df -k /data`, and `dumpsys display`
  for chipset, RAM, storage, and build details
- `dumpsys package com.android.bluetooth` for the queried Bluetooth stack version
- an on-device Java probe for the available crypto primitives

USB-attached rows are the primary source of truth. Distinct wireless ADB
serials are included as separate rows when they are attached, and USB rows
sort ahead of wireless rows within the same SDK tier.

Memory and storage values are rounded from device-reported totals to the nearest
marketed tier used in the table. Bluetooth is the queried `com.android.bluetooth`
package versionName, and crypto lists include the primitives the runtime probe
could instantiate on that device.

## Current attached devices

| ADB serial | Transport | Device | Brand | Model | Android / API | RAM | Storage | Display | Bluetooth | Crypto primitives | Build | GSMArena |
|---|---|---|---|---|---|---:|---:|---|---|---|---|---|
| `1f1dad34` | usb | Nothing Phone (2) | Nothing | A065 | Android 16 / API 36 | 12 GB | 256 GB | 1080x2412 @ 420 dpi | 16 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | B4.1-260414-1749 | [GSMArena](https://www.gsmarena.com/nothing_phone_(2)-12386.php) |
| `adb-R5CT83ACSJX-N8hnkh (2)._adb-tls-connect._tcp` | wireless | SM-F721B | Samsung | SM-F721B | Android 16 / API 36 | 8 GB | 256 GB | 1080x2640 @ 480 dpi | 16 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | BP2A.250605.031.A3.F721BXXSCIZE1 | [GSMArena]() |
| `7XHEIBPBLRJJSKFU` | usb | Realme C55 | realme | RMX3710 | Android 15 / API 35 | 8 GB | 256 GB | 1080x2400 @ 480 dpi | 15.0.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | RMX3710_15.0.0.1410(EX01) | [GSMArena](https://www.gsmarena.com/realme_c55-12159.php) |
| `adb-P2126T004912-Na69Lt (2)._adb-tls-connect._tcp` | wireless | Nothing Phone (1) | Nothing | A063 | Android 15 / API 35 | 8 GB | 256 GB | 1080x2400 @ 420 dpi | 15 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | V3.2-260416-1140 | [GSMArena](https://www.gsmarena.com/nothing_phone_(1)-11636.php) |
| `ZY22GCD9ST` | usb | Motorola Edge 30 Fusion | Motorola | motorola edge 30 fusion | Android 14 / API 34 | 8 GB | 128 GB | 1080x2400 @ 400 dpi | 14 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | U1SJS34.2-92-10-9 | [GSMArena](https://www.gsmarena.com/motorola_edge_30_fusion-11851.php) |
| `EQUGS85LJNEIO7Z5` | usb | OPPO Reno8 5G | OPPO | CPH2359 | Android 14 / API 34 | 8 GB | 256 GB | 1080x2400 @ 480 dpi | 14 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | CPH2359_14.0.0.2901(EX01) | [GSMArena](https://www.gsmarena.com/oppo_reno8-11684.php) |
| `adb-AQKSLVH004M52800029-gRaTr5 (2)._adb-tls-connect._tcp` | wireless | Nokia X20 | Nokia | Nokia X20 | Android 14 / API 34 | 6 GB | 128 GB | 1080x2400 @ 400 dpi | 14 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | 00WW_4_250 | [GSMArena](https://www.gsmarena.com/nokia_x20-10838.php) |
| `adb-AQKSLVH004M52800029-gRaTr5._adb-tls-connect._tcp` | wireless | Nokia X20 | Nokia | Nokia X20 | Android 14 / API 34 | 6 GB | 128 GB | 1080x2400 @ 400 dpi | 14 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | 00WW_4_250 | [GSMArena](https://www.gsmarena.com/nokia_x20-10838.php) |
| `GX6CTR500184` | usb | E940-2849-00 | Gigaset | E940-2849-00 | Android 13 / API 33 | 6 GB | 128 GB | 1080x2412 @ 480 dpi | 13 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | E940-2849-00_13.0_V12_20250515 | [GSMArena]() |
| `ZHBQ95BIG6NVWSUK` | usb | OPPO Reno6 5G | OPPO | CPH2251 | Android 13 / API 33 | 8 GB | 128 GB | 1080x2400 @ 480 dpi | 13.0.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | CPH2251_13.1.0.615(EX01) | [GSMArena](https://www.gsmarena.com/oppo_reno6_5g-10932.php) |
| `adb-09071JEC215801-Zg3uR3 (2)._adb-tls-connect._tcp` | wireless | Google Pixel 4a | Google | Pixel 4a | Android 13 / API 33 | 6 GB | 128 GB | 1080x2340 @ 440 dpi | 13 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | TQ3A.230805.001.S2 | [GSMArena](https://www.gsmarena.com/google_pixel_4a-10123.php) |
| `adb-MZLJMJAIO7SKS8BI-iOFt5D (2)._adb-tls-connect._tcp` | wireless | OPPO A57s | OPPO | CPH2385 | Android 13 / API 33 | 4 GB | 128 GB | 720x1612 @ 320 dpi | 13.0.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | CPH2385_13.1.1.551(EX01) | [GSMArena](https://www.gsmarena.com/oppo_a57s-11835.php) |
| `adb-MJJ7ZDT455JBYTEA-0WCF8P (2)._adb-tls-connect._tcp` | wireless | OnePlus Nord 2 5G | OnePlus | DN2103 | Android 13 / API 33 | 12 GB | 256 GB | 1080x2400 @ 480 dpi | 13.0.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | DN2103_11_F.58 | [GSMArena](https://www.gsmarena.com/oneplus_nord_2_5g-10960.php) |
| `2ASVB21B09005117` | usb | Huawei Nova 9 | Huawei | NAM-LX9 | Android 12 / API 31 | 8 GB | 128 GB | 1080x2340 @ 480 dpi | 29.1.0.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | NAM-L29 13.0.0.252(C432E2R1P2) | [GSMArena](https://www.gsmarena.com/huawei_nova_9-11121.php) |
| `adb-bb722dcd-7SjtpI (2)._adb-tls-connect._tcp` | wireless | OnePlus 7T | OnePlus | HD1901 | Android 12 / API 31 | 8 GB | 64 GB | 1080x2400 @ 450 dpi | 12 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | HD1901_11_F.22 | [GSMArena](https://www.gsmarena.com/oneplus_7t-9816.php) |
| `adb-bb722dcd-7SjtpI._adb-tls-connect._tcp` | wireless | OnePlus 7T | OnePlus | HD1901 | Android 12 / API 31 | 8 GB | 64 GB | 1080x2400 @ 450 dpi | 12 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | HD1901_11_F.22 | [GSMArena](https://www.gsmarena.com/oneplus_7t-9816.php) |
| `adb-851ff431-WMnmYP (2)._adb-tls-connect._tcp` | wireless | OnePlus 6 | OnePlus | ONEPLUS A6003 | Android 11 / API 30 | 8 GB | 128 GB | 1080x2280 @ 450 dpi | 11 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA | ONEPLUS A6003_22_211125 | [GSMArena](https://www.gsmarena.com/oneplus_6-9109.php) |
| `e9097611` | usb | Xiaomi Pocophone F1 | Xiaomi | POCOPHONE F1 | Android 10 / API 29 | 6 GB | 64 GB | 1080x2246 @ 440 dpi | 10 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA | QKQ1.190828.002 test-keys | [GSMArena](https://www.gsmarena.com/xiaomi_pocophone_f1-9293.php) |
| `42004386e43c8589` | usb | SM-G390F | Samsung | SM-G390F | Android 9 / API 28 | 2 GB | 16 GB | 720x1280 @ 320 dpi | 9 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | PPR1.180610.011.G390FXXU6CTG3 | [GSMArena]() |
| `42c2cf` | usb | Xiaomi Mi Note 3 | Xiaomi | Mi Note 3 | Android 9 / API 28 | 6 GB | 128 GB | 1080x1920 @ 440 dpi | 9 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | PKQ1.181007.001 | [GSMArena](https://www.gsmarena.com/xiaomi_mi_note_3-8707.php) |
| `2XJ7N17323004090` | usb | Huawei P10 Lite | Huawei | WAS-LX1A | Android 8.0.0 / API 26 | 4 GB | 16 GB | 1080x1920 @ 480 dpi | 8.0.0 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | WAS-LX1A 8.0.0.394(C432) | [GSMArena](https://www.gsmarena.com/huawei_p10_lite-8598.php) |

## Notes

- USB and wireless endpoints are both listed when they are currently attached.
- If a wireless endpoint disconnects, it should disappear from the next refresh instead of remaining as stale data.
- Device-name and URL choices come from GSMArena; all other fields are adb-derived.
