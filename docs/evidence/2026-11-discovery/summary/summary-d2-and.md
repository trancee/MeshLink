# D2 summary: background visibility matrix

Runs: d2-and-20261102

Latency = first sighting − advertiser start (host clock). Recall = share of complete windows with ≥ 1 sighting,
from the first sighting to the cell end. Hint = share of sightings carrying the advertiser's metadata (layout E rotating UUID).

| Cell | Scanner | Scanner state | Latency (s) | Per min | Recall 10 s | 30 s | 60 s | Hint | First id |
|---|---|---|---|---|---|---|---|---|---|
| pixel6-off | xcover4 | Android screen-off FGS mesh balanced | 0.98 | 36.7 | 1.0 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | ipx | iOS bg mesh | 0.43 | 6.1 | 0.793 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | se2016 | iOS bg mesh | 0.63 | 3.3 | 0.483 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | ip15 | iOS fg mesh | 0.17 | 189.4 | 1.0 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | se2020 | iOS fg mesh | 0.12 | 177.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | moto30 | Android screen-off FGS mesh balanced | 0.41 | 48.9 | 1.0 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| pixel6-off | nothing2 | Android fg none low_latency | -0.01 | 213.1 | 1.0 | 1.0 | 1.0 | 1.0 | a:786b7d638796cbb1 |
| s10e-off | xcover4 | Android screen-off FGS mesh balanced | 0.61 | 29.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | ipx | iOS bg mesh | 1.37 | 6.3 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | se2016 | iOS bg mesh | 0.68 | 3.1 | 0.483 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | ip15 | iOS fg mesh | -0.01 | 151.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | se2020 | iOS fg mesh | -0.05 | 125.3 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | moto30 | Android screen-off FGS mesh balanced | 1.48 | 43.3 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |
| s10e-off | nothing2 | Android fg none low_latency | 0.35 | 162.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:a46d962faa07621d |

## T1/T2: raw bytes as received by Android scanners (distinct per cell, scanner, payload)

- pixel6-off → xcover4 (Android screen-off FGS mesh balanced), 63:c5:5d:63:ac:0a, id a:786b7d638796cbb1: `02010205054853454d1107e51c1a0031304c4db1cb9687637d6b78`
- pixel6-off → moto30 (Android screen-off FGS mesh balanced), 63:c5:5d:63:ac:0a, id a:786b7d638796cbb1: `02010205054853454d1107e51c1a0031304c4db1cb9687637d6b78`
- pixel6-off → nothing2 (Android fg none low_latency), 63:c5:5d:63:ac:0a, id a:786b7d638796cbb1: `02010205054853454d1107e51c1a0031304c4db1cb9687637d6b78`
- s10e-off → xcover4 (Android screen-off FGS mesh balanced), 64:e8:a6:44:33:09, id a:a46d962faa07621d: `02011a05054853454d1107ddb0170031304c4d1d6207aa2f966da4`
- s10e-off → moto30 (Android screen-off FGS mesh balanced), 64:e8:a6:44:33:09, id a:a46d962faa07621d: `02011a05054853454d1107ddb0170031304c4d1d6207aa2f966da4`
- s10e-off → nothing2 (Android fg none low_latency), 64:e8:a6:44:33:09, id a:a46d962faa07621d: `02011a05054853454d1107ddb0170031304c4d1d6207aa2f966da4`
