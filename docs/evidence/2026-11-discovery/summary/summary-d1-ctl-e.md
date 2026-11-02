# D1 summary: BLE address rotation and control

Runs: d1-ctl-e-20261102

## Idle rotation intervals (scanner clock)

Exact interval = between two changes that each happened while a scanner was receiving (old address seen
≤ 10 s before the new one). Longest lifetime = longest observed first-to-last span of one address (lower bound).

| Device | Model | OS | API | Addresses | Exact intervals (min) | Median (min) | Longest lifetime (min) |
|---|---|---|---|---|---|---|---|

## Restart control (address after restart vs before)

| Device | Model | OS | API / kind | Cycles | New | Same | Unobserved | Cycles with >1 address |
|---|---|---|---|---|---|---|---|---|
| nokiax20 | HMD Global Nokia X20 | Android 14 (API 34) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| minote3 | Xiaomi Mi Note 3 | Android 9 (API 28) | legacy / stop | 15 | 15 | 0 | 0 | 0 |
| blackview | wheatek A80 | Android 10 (API 29) | legacy / stop | 15 | 15 | 0 | 0 | 0 |
| ipx | Apple iPhone10,6 | iOS 16.7.16 | ios / same | 15 | 0 | 15 | 0 | 2 |
| se2016 | Apple iPhone8,4 | iOS 15.8.8 | ios / stop | 16 | 0 | 15 | 1 | 2 |
| op6 | OnePlus ONEPLUS A6003 | Android 11 (API 30) | set / enable | 15 | 0 | 15 | 0 | 2 |
| rmx3710 | realme RMX3710 | Android 15 (API 35) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| nord2 | OnePlus DN2103 | Android 13 (API 33) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| cph2135 | OPPO CPH2135 | Android 12 (API 31) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| nothing1 | Nothing A063 | Android 15 (API 35) | set / data | 15 | 0 | 15 | 0 | 2 |
| ip15 | Apple iPhone15,4 | iOS 17.6.1 | ios / stop | 16 | 0 | 15 | 1 | 2 |
| zflip4 | samsung SM-F721B | Android 16 (API 36) | set / new_set | 15 | 15 | 0 | 0 | 0 |
| se2020 | Apple iPhone12,8 | iOS 18.7.8 | ios / swap | 16 | 0 | 15 | 1 | 2 |
| pixel4a | Google Pixel 4a | Android 13 (API 33) | legacy / stop | 15 | 15 | 0 | 0 | 0 |

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
| nokiax20 | Android 14 (API 34) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.9 / 7.9 | 0 | 0 | — | 0.4 / 0.6 | 0 |
| minote3 | Android 9 (API 28) | legacy / stop | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.7 / 10.7 | 0 | 0 | — | 0.2 / 0.7 | 0 |
| blackview | Android 10 (API 29) | legacy / stop | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.7 / 10.0 | 0 | 0 | — | 0.2 / 2.4 | 0 |
| ipx | iOS 16.7.16 | ios / same | 15 | 0 | — | 0/0 | — | 0 | 15 | 318.9 / 928.9 | — / — | 0 |
| se2016 | iOS 15.8.8 | ios / stop | 15 | 0 | — | 0/0 | — | 0 | 15 | 317.6 / 927.6 | -0.0 / 0.1 | 0 |
| op6 | Android 11 (API 30) | set / enable | 15 | 0 | — | 0/0 | — | 0 | 15 | 398.5 / 794.4 | 0.6 / 1.7 | 0 |
| rmx3710 | Android 15 (API 35) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.7 / 9.1 | 0 | 0 | — | 0.2 / 0.4 | 0 |
| nord2 | Android 13 (API 33) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 4.3 / 11.3 | 0 | 0 | — | 1.5 / 1.8 | 0 |
| cph2135 | Android 12 (API 31) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 3.2 / 9.9 | 0 | 0 | — | 0.3 / 0.8 | 0 |
| nothing1 | Android 15 (API 35) | set / data | 15 | 0 | — | 0/0 | — | 0 | 15 | 320.1 / 761.2 | 0.6 / 1.3 | 0 |
| ip15 | iOS 17.6.1 | ios / stop | 15 | 0 | — | 0/0 | — | 0 | 15 | 322.5 / 932.5 | 0.0 / 0.2 | 0 |
| zflip4 | Android 16 (API 36) | set / new_set | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.5 / 7.9 | 0 | 0 | — | 0.2 / 1.0 | 0 |
| se2020 | iOS 18.7.8 | ios / swap | 15 | 0 | — | 0/0 | — | 0 | 15 | 390.8 / 963.2 | 0.1 / 0.3 | 0 |
| pixel4a | Android 13 (API 33) | legacy / stop | 15 | 15 | 0.0 / 0.0 | 15/15 | 2.9 / 8.8 | 0 | 0 | — | 0.3 / 2.8 | 0 |

## Own-address probes

