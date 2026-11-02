# T4 summary: Live Activity and Core Bluetooth background privileges

Runs: t4-20261102

## Scanner cells (iPhone scans; Pixel 6 advertises layout E)

| Cell | Live Activity | App state at scan start | Callbacks/min (all) | Peers (MESH / other) | Pixel 6 sightings/min | Pixel 6 recall 10 s |
|---|---|---|---|---|---|---|
| s-nil-fg | — | inactive | 3968.7 | 1 / 45 | 270.0 | 1.0 |
| s-nil-bg | — | background | 16.3 | 1 / 21 | 2.0 | 0.056 |
| s-nil-bg-live | started id=E7EE47FD-227C-4B7E-BA9F-7C1270D00D05 enabled=true state=active | background | 3958.7 | 2 / 47 | 260.7 | 1.0 |
| s-mesh-bg | — | background | 93.3 | 1 / 0 | 93.3 | 1.0 |
| s-mesh-bg-live | started id=C9EDA329-10AD-4174-9C72-DAA93C6A19A7 enabled=true state=active | background | 250.7 | 1 / 0 | 250.7 | 1.0 |

## Advertiser cells (iPhone advertises layout E; Android scanners, unfiltered)

Times are seconds after the iPhone logged `background` (app state background). The iPhone's background task
expires about 28–29 s after that; the app is suspended soon after.

| Cell | Live Activity | Scanner | 1 s buckets with the rotating UUID | UUID seen from … to … s | Other buckets from that address | Sample raw (first UUID sighting) |
|---|---|---|---|---|---|---|
| a-bg | — | pixel6 (Android fg none low_latency) | 1 | 0.1 … 0.1 | 119 | `02011a020a0c1107bbf4b30031304c4d4dcd9c5ea476d15f05054853454d` |
| a-bg | — | nothing2 (Android fg none low_latency) | 0 | — | 110 | `02011a020a0c1107bbf4b30031304c4d4dcd9c5ea476d15f05054853454d14ff4c000100000010000000000000000000000008` |
| a-bg | — | pixel6 (Android fg none low_latency) | 0 | — | 120 | `02011a020a0c1107afe0cd0031304c4d9586636af969f38a05054853454d` |
| a-bg | — | nothing2 (Android fg none low_latency) | 1 | 0.0 … 0.0 | 98 | `02011a020a0c1107afe0cd0031304c4d9586636af969f38a05054853454d14ff4c000100000000000000000000000000080008` |
| a-bg-live | started id=8E86AD26-F1C0-400B-A634-1BF582974E1E enabled=true state=active | pixel6 (Android fg none low_latency) | 34 | 0.0 … 32.7 | 79 | `02011a020a0c110714e6540031304c4d6ce18a50af458fe405054853454d` |
| a-bg-live | started id=8E86AD26-F1C0-400B-A634-1BF582974E1E enabled=true state=active | nothing2 (Android fg none low_latency) | 34 | 0.0 … 33.1 | 71 | `02011a020a0c110714e6540031304c4d6ce18a50af458fe405054853454d14ff4c000100000000000000000000000000020008` |
| a-bg-live | started id=697F0608-B18F-4528-B528-A787EFE3959F enabled=true state=active | pixel6 (Android fg none low_latency) | 27 | 0.8 … 32.7 | 86 | `02011a020a0c1107a8f5a00031304c4d12019613049b45b005054853454d` |
| a-bg-live | started id=697F0608-B18F-4528-B528-A787EFE3959F enabled=true state=active | nothing2 (Android fg none low_latency) | 28 | 0.2 … 33.1 | 74 | `02011a020a0c1107a8f5a00031304c4d12019613049b45b005054853454d14ff4c000100000000002000000000000000000008` |

