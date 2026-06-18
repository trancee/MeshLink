# Device test matrix reference

Last verified: 2026-06-18

This page tracks the Android devices currently attached to the MeshLink test
bench and the device facts that matter for validation.

Use it when you need to:

- choose a device for a regression or integration run
- compare OEM behavior across Android versions and SDK levels
- add a new device to the fleet using the same reporting shape

This matrix is rebuilt from live adb probes and the prior GSMArena-backed device register:

- `adb devices -l` for the current attached inventory
- `./tools/android device info --device <serial> --json` for model, Android,
  and display facts
- `adb shell getprop`, `cat /proc/meminfo`, `df -k /data`, and `dumpsys display`
  for runtime chipset, RAM, storage, and build details
- Bluetooth version, screen size, and chipset come from the GSMArena entry for each device

Memory and storage values are rounded from device-reported totals to the nearest
marketed tier used in the table.

Never remove a device row from this list. Update an existing row or add a new
row instead; if a device is no longer attached, keep it in the retained section
below instead of deleting it.

## Current attached devices

| Connection | Device | Brand | Model | Android / API | RAM | Storage | Screen size | Chipset | Bluetooth | Crypto primitives | Build | GSMArena |
|---|---|---|---|---|---:|---:|---|---|---|---|---|---|
| usb t8 | Nothing Phone (2) | Nothing | A065 | Android 16 / API 36 | 12 GB | 256 GB | 6.7" | Snapdragon 8+ Gen 1 | 5.3 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | B4.1-260414-1749 | [GSMArena](https://www.gsmarena.com/nothing_phone_(2)-12386.php) |
| usb t9 | Realme C55 | realme | RMX3710 | Android 15 / API 35 | 8 GB | 256 GB | 6.72" | Helio G88 | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | RMX3710_15.0.0.1410(EX01) | [GSMArena](https://www.gsmarena.com/realme_c55-12159.php) |
| usb t10 | Motorola Edge 30 Fusion | motorola | motorola edge 30 fusion | Android 14 / API 34 | 6 GB | 128 GB | 6.55" | Snapdragon 888+ 5G | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | U1SJS34.2-92-10-9 | [GSMArena](https://www.gsmarena.com/motorola_edge_30_fusion-11851.php) |
| usb t7 | OPPO Reno8 5G | OPPO | CPH2359 | Android 14 / API 34 | 6 GB | 256 GB | 6.43" | Dimensity 1300 | 5.3 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | CPH2359_14.0.0.2901(EX01) | [GSMArena](https://www.gsmarena.com/oppo_reno8-11684.php) |
| wireless t5625 | Nokia X20 | Nokia | Nokia X20 | Android 14 / API 34 | 4 GB | 128 GB | 6.67" | Snapdragon 480 5G | 5.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | 00WW_4_250 | [GSMArena](https://www.gsmarena.com/nokia_x20-10838.php) |
| usb t13 | Gigaset GX6 | Gigaset | E940-2849-00 | Android 13 / API 33 | 4 GB | 128 GB | 6.6" | Dimensity 900 | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | E940-2849-00_13.0_V12_20250515 | [GSMArena search](https://www.gsmarena.com/results.php3?sQuickSearch=yes&sName=Gigaset%20GX6) |
| usb t5950 | OPPO Reno6 5G | OPPO | CPH2251 | Android 13 / API 33 | 6 GB | 128 GB | 6.43" | Dimensity 900 | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH; Ed25519 | CPH2251_13.1.0.615(EX01) | [GSMArena](https://www.gsmarena.com/oppo_reno6_5g-10932.php) |
| usb t5 | Huawei Nova 9 | HUAWEI | NAM-LX9 | Android 12 / API 31 | 6 GB | 128 GB | 6.57" | Snapdragon 778G 4G | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | NAM-L29 13.0.0.252(C432E2R1P2) | [GSMArena](https://www.gsmarena.com/huawei_nova_9-11121.php) |
| wireless t5943 | OnePlus 7T | OnePlus | HD1901 | Android 12 / API 31 | 6 GB | 64 GB | 6.55" | Snapdragon 855+ | 5.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA; X25519/XDH | HD1901_11_F.22 | [GSMArena](https://www.gsmarena.com/oneplus_7t-9816.php) |
| usb t11 | Xiaomi Pocophone F1 | Xiaomi | POCOPHONE F1 | Android 10 / API 29 | 4 GB | 64 GB | 6.18" | Snapdragon 845 | 5.0 | AES-GCM; ChaCha20-Poly1305; SHA-256; HMAC-SHA256; RSA; ECDSA | QKQ1.190828.002 test-keys | [GSMArena](https://www.gsmarena.com/xiaomi_pocophone_f1-9293.php) |
| usb t62 | Samsung Galaxy XCover 4 | Samsung | SM-G390F | Android 9 / API 28 | 2 GB | 16 GB | 5.0" | Exynos 7570 Quad | 4.2 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | PPR1.180610.011.G390FXXU6CTG3 | [GSMArena](https://www.gsmarena.com/samsung_galaxy_xcover_4-8577.php) |
| usb t5907 | Xiaomi Mi Note 3 | Xiaomi | Mi Note 3 | Android 9 / API 28 | 6 GB | 128 GB | 5.5" | Snapdragon 660 | 5.0 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | PKQ1.181007.001 | [GSMArena](https://www.gsmarena.com/xiaomi_mi_note_3-8707.php) |
| usb t5926 | Huawei P10 Lite | Huawei | WAS-LX1A | Android 8.0.0 / API 26 | 4 GB | 16 GB | 5.2" | Kirin 658 | 4.2 | AES-GCM; SHA-256; HMAC-SHA256; RSA; ECDSA | WAS-LX1A 8.0.0.394(C432) | [GSMArena](https://www.gsmarena.com/huawei_p10_lite-8598.php) |

## Retained devices

Rows here are historical entries that are no longer attached right now but are
kept to satisfy the no-removal policy.

| Connection | Device | Brand | Model | Android / API | RAM | Storage | Screen size | Chipset | Bluetooth | Crypto primitives | Build | GSMArena |
|---|---|---|---|---|---:|---:|---|---|---|---|---|---|
| retained | Samsung Galaxy Z Flip4 | Samsung | SM-F721B | Android 16 / SDK 36 | 8 GB | 256 GB | 6.7" | Snapdragon 8+ Gen 1 | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | — | [GSMArena](https://www.gsmarena.com/samsung_galaxy_z_flip4-11538.php) |
| retained | Nothing Phone (1) | Nothing | A063 | Android 15 / SDK 35 | 8 GB | 256 GB | 6.55" | Snapdragon 778G+ | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | — | [GSMArena](https://www.gsmarena.com/nothing_phone_(1)-11636.php) |
| retained | Google Pixel 4a | Google | sunfish | Android 13 / SDK 33 | 6 GB | 128 GB | 5.81" | Snapdragon 730G | 5.0 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519 | — | [GSMArena](https://www.gsmarena.com/google_pixel_4a-10123.php) |
| retained | OPPO A57s | OPPO | CPH2385 | Android 13 / SDK 33 | 4 GB | 128 GB | 6.56" | Helio G35 | 5.3 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | — | [GSMArena](https://www.gsmarena.com/oppo_a57s-11835.php) |
| retained | OnePlus Nord 2 5G | OnePlus | DN2103 | Android 13 / SDK 33 | 12 GB | 256 GB | 6.43" | Dimensity 1200 | 5.2 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256; X25519; Ed25519 | — | [GSMArena](https://www.gsmarena.com/oneplus_nord_2_5g-10960.php) |
| retained | OnePlus 6 | OnePlus | ONEPLUS A6003 | Android 11 / SDK 30 | 8 GB | 128 GB | 6.28" | Snapdragon 845 | 5.0 | AES-GCM; ChaCha20-Poly1305; SHA-256/HMAC-SHA256; RSA-2048; ECDSA P-256 | — | [GSMArena](https://www.gsmarena.com/oneplus_6-9109.php) |

## Notes

- USB rows stay ahead of wireless rows when the Android API level is the same.
- Wireless ADB endpoints are kept as separate rows when they are currently attached.
- No serial numbers are published in this document.
