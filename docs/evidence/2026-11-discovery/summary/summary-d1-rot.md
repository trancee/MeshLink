# D1 summary: BLE address rotation and control

Runs: d1-rot-20261102

## Idle rotation intervals (scanner clock)

Exact interval = between two changes that each happened while a scanner was receiving (old address seen
≤ 10 s before the new one). Longest lifetime = longest observed first-to-last span of one address (lower bound).

| Device | Model | OS | API | Addresses | Exact intervals (min) | Median (min) | Longest lifetime (min) |
|---|---|---|---|---|---|---|---|
| xcover4 | samsung SM-G390F | Android 9 (API 28) | legacy | 8 | 15.0, 15.0, 15.0 | 15.0 | 15.0 |
| ipx | Apple iPhone10,6 | iOS 16.7.16 | ios | 8 | 11.3, 19.3, 15.8, 18.0 | 16.9 | 19.3 |
| se2016 | Apple iPhone8,4 | iOS 15.8.8 | ios | 8 | 11.3, 19.3, 15.8, 18.0 | 16.9 | 19.3 |
| p10lite | HUAWEI WAS-LX1A | Android 8.0.0 (API 26) | legacy | 12 | 4.1, 10.9, 4.1, 10.9, 4.1, 10.9, 4.1 | 4.1 | 10.9 |
| ip12mini | Apple iPhone13,1 | iOS 27.0.1 | ios | 6 | 17.2, 18.3 | 17.8 | 18.3 |
| mi8-attempt2 | Xiaomi MI 8 | Android 10 (API 29) | legacy | 0 | — | — | — |
| pixel6 | Google Pixel 6 | Android 17 (API 37) | set | 10 | 7.3, 8.7, 8.7, 7.7, 7.1, 7.9 | 7.8 | 8.7 |
| ip15 | Apple iPhone15,4 | iOS 17.6.1 | ios | 8 | 11.3, 19.3, 15.8, 18.0 | 16.9 | 19.3 |
| s10e | samsung SM-G970U1 | Android 12 (API 31) | legacy | 7 | 14.1, 14.1, 14.1, 14.1 | 14.1 | 14.1 |
| mi8-attempt1 | Xiaomi MI 8 | Android 10 (API 29) | legacy | 0 | — | — | — |

## Restart control (address after restart vs before)

| Device | Model | OS | API / kind | Cycles | New | Same | Unobserved | Cycles with >1 address |
|---|---|---|---|---|---|---|---|---|
| nokiax20 | HMD Global Nokia X20 | Android 14 (API 34) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| minote3 | Xiaomi Mi Note 3 | Android 9 (API 28) | legacy / stop | 31 | 21 | 0 | 10 | 0 |
| blackview | wheatek A80 | Android 10 (API 29) | legacy / stop | 31 | 21 | 0 | 10 | 0 |
| op6 | OnePlus ONEPLUS A6003 | Android 11 (API 30) | set / enable | 31 | 0 | 21 | 10 | 6 |
| rmx3710 | realme RMX3710 | Android 15 (API 35) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| nord2 | OnePlus DN2103 | Android 13 (API 33) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| cph2135 | OPPO CPH2135 | Android 12 (API 31) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| nothing1 | Nothing A063 | Android 15 (API 35) | set / data | 31 | 0 | 21 | 10 | 6 |
| zflip4 | samsung SM-F721B | Android 16 (API 36) | set / new_set | 31 | 21 | 0 | 10 | 0 |
| se2020 | Apple iPhone12,8 | iOS 18.7.8 | ios / stop | 35 | 2 | 33 | 0 | 4 |
| pixel4a | Google Pixel 4a | Android 13 (API 33) | legacy / stop | 31 | 21 | 0 | 10 | 0 |

## Restart linkage gap (address change vs payload change)

Host clock, 1 s resolution (scanner buckets). Per restart: payload change = first sighting of the new payload
(or the restart call when the payload carries no per-cycle ID). If the address changed too, gap = first sighting
of the new address − payload change, and mixed = sightings pairing an old address with the new payload or the
reverse. If the address stayed, the linked window = payload change → first sighting of any later address
(the time a tracker can join the two payloads by address; lower bound if the run ended first).
Same packet = the first sighting of the new payload (ms clock) already carries the new address. Dark = that
sighting − the last sighting of the old address (the off time a tracker must bridge).

| Device | OS | API / kind | Restarts | Address changed | Gap median / max (s) | Same packet | Dark median / max (s) | Mixed sightings (changed only) | Address kept | Linked window median / max (s) | Restart → new payload, Android / iOS median (s) | iOS stale |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| nokiax20 | Android 14 (API 34) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.6 / 7.2 | 0 | 0 | — | 0.3 / — | 0 |
| minote3 | Android 9 (API 28) | legacy / stop | 21 | 21 | 0.0 / 0.0 | 21/21 | 2.8 / 8.3 | 0 | 0 | — | 0.3 / — | 0 |
| blackview | Android 10 (API 29) | legacy / stop | 21 | 21 | 0.0 / 0.0 | 21/21 | 2.8 / 3.7 | 0 | 0 | — | 0.2 / — | 0 |
| op6 | Android 11 (API 30) | set / enable | 21 | 0 | — | 0/0 | — | 0 | 21 | 292.1 / 1309.1 | 0.6 / — | 0 |
| rmx3710 | Android 15 (API 35) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.5 / 9.8 | 0 | 0 | — | 0.2 / — | 0 |
| nord2 | Android 13 (API 33) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 3.6 / 8.1 | 0 | 0 | — | 0.6 / — | 0 |
| cph2135 | Android 12 (API 31) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 3.0 / 8.4 | 0 | 0 | — | 0.4 / — | 0 |
| nothing1 | Android 15 (API 35) | set / data | 21 | 0 | — | 0/0 | — | 0 | 21 | 262.5 / 1312.4 | 0.2 / — | 0 |
| zflip4 | Android 16 (API 36) | set / new_set | 21 | 21 | 0.0 / 0.0 | 21/21 | 3.0 / 8.0 | 0 | 0 | — | 0.4 / — | 0 |
| se2020 | iOS 18.7.8 | ios / stop | 33 | 0 | — | 0/0 | — | 0 | 33 | 451.3 / 1364.1 | — / — | 0 |
| pixel4a | Android 13 (API 33) | legacy / stop | 21 | 21 | 0.0 / 0.0 | 21/21 | 2.9 / 8.0 | 0 | 0 | — | 0.2 / — | 0 |

## Own-address probes

- d1-rot-20261102: 96 own-address probes; outcomes: ['java.lang.NoSuchMethodException: android.bluetooth.le.AdvertisingSet.getOwnAddress []']; onOwnAddressRead fired 0 times
