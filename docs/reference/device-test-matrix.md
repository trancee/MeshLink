# Device test matrix reference

Last verified: 2026-06-18

This page tracks the Android devices currently attached to the MeshLink test
bench and the device facts that matter for validation.

Use it when you need to:

- choose a device for a regression or integration run
- compare OEM behavior across Android versions and SDK levels
- add a new device to the fleet using the same reporting shape

This matrix is rebuilt from live device probes:

- `adb devices -l` for the current USB-attached inventory
- `./tools/android device info --device <serial> --json` for model, Android,
  and display facts
- `adb shell getprop`, `cat /proc/meminfo`, `df -k /data`, and `dumpsys display`
  for chipset, RAM, storage, and build details

Memory and storage values are rounded from device-reported totals to the nearest
marketed tier used in the table. Display values are the logical resolution and
reported density from the device, not a website-derived diagonal size.

## Current USB-attached devices

| Device | Brand | Model | Android / API | RAM | Storage | Display | Chipset / board | Build | adb notes |
|---|---|---|---|---:|---:|---|---|---|---|
| Nothing A065 | Nothing | A065 | Android 16 / API 36 | 12 GB | 256 GB | 1080x2412 @ 420 dpi | SM8475 / taro | B4.1-260414-1749 | first API 33; security patch 2026-04-01 |
| realme RMX3710 | realme | RMX3710 | Android 15 / API 35 | 8 GB | 256 GB | 1080x2400 @ 480 dpi | MT6769H / mt6768 | RMX3710_15.0.0.1410(EX01) | first API 33; security patch 2025-12-01 |
| OPPO CPH2359 | OPPO | CPH2359 | Android 14 / API 34 | 8 GB | 128 GB | 1080x2400 @ 480 dpi | MT6893 / mt6893 | CPH2359_14.0.0.2901(EX01) | first API 31; security patch 2026-04-01 |
| Motorola edge 30 fusion | Motorola | motorola edge 30 fusion | Android 14 / API 34 | 8 GB | 128 GB | 1080x2400 @ 400 dpi | SM8350 / lahaina | U1SJS34.2-92-10-9 | first API 31; security patch 2025-08-01 |
| Gigaset E940-2849-00 | Gigaset | E940-2849-00 | Android 13 / API 33 | 6 GB | 128 GB | 1080x2412 @ 480 dpi | MT6877V/ZA / mt6877 | E940-2849-00_13.0_V12_20250515 | first API 31; security patch 2025-05-05 |
| OPPO CPH2251 | OPPO | CPH2251 | Android 13 / API 33 | 8 GB | 128 GB | 1080x2400 @ 480 dpi | MT6877 / mt6877 | CPH2251_13.1.0.615(EX01) | first API 30; security patch 2025-12-01 |
| HUAWEI NAM-LX9 | HUAWEI | NAM-LX9 | Android 12 / API 31 | 8 GB | 128 GB | 1080x2340 @ 480 dpi | SM7325 / lahaina | NAM-L29 13.0.0.252(C432E2R1P2) | first API 30; security patch 2021-12-05 |
| Xiaomi POCOPHONE F1 | Xiaomi | POCOPHONE F1 | Android 10 / API 29 | 6 GB | 64 GB | 1080x2246 @ 440 dpi | sdm845 | QKQ1.190828.002 test-keys | first API 27; security patch 2020-12-01 |
| Samsung SM-G390F | Samsung | SM-G390F | Android 9 / API 28 | 2 GB | 16 GB | 720x1280 @ 320 dpi | exynos5 | PPR1.180610.011.G390FXXU6CTG3 | first API 24; security patch 2020-07-01 |
| Xiaomi Mi Note 3 | Xiaomi | Mi Note 3 | Android 9 / API 28 | 6 GB | 128 GB | 1080x1920 @ 440 dpi | sdm660 | PKQ1.181007.001 | first API 25; security patch 2020-01-01 |
| HUAWEI WAS-LX1A | HUAWEI | WAS-LX1A | Android 8.0.0 / API 26 | 4 GB | 32 GB | 1080x1920 @ 480 dpi | hi6250 | WAS-LX1A 8.0.0.394(C432) | first API 24; security patch 2020-07-01 |

## Source of truth

- USB-attached devices only; wireless ADB aliases are excluded when the same
  hardware is present over USB.
- If a device disappears from `adb devices -l`, it should be removed from the
  current inventory on the next refresh instead of left in place with stale data.
- When a field is not reported by the device, prefer `—` over importing a web
  spec.
