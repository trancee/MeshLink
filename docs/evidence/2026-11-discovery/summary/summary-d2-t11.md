# D2 summary: background visibility matrix

Runs: t11-20261102

Latency = first sighting − advertiser start (host clock). Recall = share of complete windows with ≥ 1 sighting,
from the first sighting to the cell end. Hint = share of sightings carrying the advertiser's metadata (layout E rotating UUID).

| Cell | Scanner | Scanner state | Latency (s) | Per min | Recall 10 s | 30 s | 60 s | Hint | First id |
|---|---|---|---|---|---|---|---|---|---|
| t11 | ipx | iOS bg mesh rescan | 0.11 | 12.8 | 1.0 | 1.0 | 1.0 | 1.0 | a:f29fb4e205b73e57 |
| t11 | se2016 | iOS bg mesh rescan | 1.06 | 8.4 | 1.0 | 1.0 | 1.0 | 1.0 | a:f29fb4e205b73e57 |
| t11 | ip12mini | iOS bg mesh rescan | 0.36 | 97.3 | 1.0 | 1.0 | 1.0 | 1.0 | a:f29fb4e205b73e57 |
| t11 | ip15 | iOS bg mesh rescan | 0.12 | 6.0 | 0.966 | 1.0 | 1.0 | 1.0 | a:f29fb4e205b73e57 |
| t11 | se2020 | iOS bg mesh | 0.59 | 8.8 | 1.0 | 1.0 | 1.0 | 1.0 | a:f29fb4e205b73e57 |
| t11 | nothing2 | Android fg none low_latency | -0.96 | 542.6 | 1.0 | 1.0 | 1.0 | 0.411 | apple-ovf |

## T1/T2: raw bytes as received by Android scanners (distinct per cell, scanner, payload)

- t11 → nothing2 (Android fg none low_latency), 41:c1:9e:ed:78:e6, id a:f29fb4e205b73e57: `02010205054853454d1107ffa8910031304c4d573eb705e2b49ff2`

## T11: background iOS re-report after scan restarts

| Scanner | State | Restarts | Restarts followed by a report of the stable peer | Report buckets |
|---|---|---|---|---|
| ipx | iOS bg mesh rescan | 30 | 30 | 64 |
| se2016 | iOS bg mesh rescan | 31 | 29 | 42 |
| ip12mini | iOS bg mesh rescan | 31 | 30 | 486 |
| ip15 | iOS bg mesh rescan | 30 | 29 | 30 |
| se2020 | iOS bg mesh | 0 | 0 | 44 |
