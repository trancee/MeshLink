# T3 summary: iOS overflow-area discovery (advertiser in background)

Runs: t3-20261102

Latency = first discovery − scan start on the scanner clock.

| Case | Scanner | OS | Trials | Found | Median (s) | Max (s) |
|---|---|---|---|---|---|---|
| bg-uuid | ip12mini | iOS 27.0.1 | 7 | 4 | 0.185 | 0.28 |
| bg-uuid | ipx | iOS 16.7.16 | 7 | 6 | 0.1 | 0.38 |
| bg-uuid | se2020 | iOS 18.7.8 | 7 | 3 | 0.15 | 0.24 |
| fg-nil | ip12mini | iOS 27.0.1 | 7 | 0 | — | — |
| fg-nil | ipx | iOS 16.7.16 | 7 | 0 | — | — |
| fg-nil | se2020 | iOS 18.7.8 | 7 | 0 | — | — |
| fg-uuid | ip12mini | iOS 27.0.1 | 7 | 7 | 0.15 | 0.46 |
| fg-uuid | ipx | iOS 16.7.16 | 7 | 7 | 0.19 | 0.31 |
| fg-uuid | se2020 | iOS 18.7.8 | 7 | 7 | 0.42 | 1.22 |

| Case (pooled) | Trials | Found | Median (s) | p90 (s) |
|---|---|---|---|---|
| bg-uuid | 21 | 13 | 0.15 | 0.28 |
| fg-nil | 21 | 0 | — | — |
| fg-uuid | 21 | 21 | 0.17 | 0.56 |
