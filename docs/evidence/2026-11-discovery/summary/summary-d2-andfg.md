# D2 summary: background visibility matrix

Runs: d2-and-fg-20261102

Latency = first sighting − advertiser start (host clock). Recall = share of complete windows with ≥ 1 sighting,
from the first sighting to the cell end. Hint = share of sightings carrying the advertiser's metadata (layout E rotating UUID).

| Cell | Scanner | Scanner state | Latency (s) | Per min | Recall 10 s | 30 s | 60 s | Hint | First id |
|---|---|---|---|---|---|---|---|---|---|
| pixel6-fg | xcover4 | Android screen-off FGS mesh balanced | 6.2 | 10.0 | 0.828 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | ipx | iOS bg mesh | 0.74 | 6.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | se2016 | iOS bg mesh | 0.65 | 3.0 | 0.517 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | ip15 | iOS fg mesh | 0.6 | 179.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | se2020 | iOS fg mesh | -0.05 | 186.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | moto30 | Android screen-off FGS mesh balanced | 2.6 | 27.5 | 1.0 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| pixel6-fg | nothing2 | Android fg none low_latency | -0.03 | 157.1 | 1.0 | 1.0 | 1.0 | 1.0 | a:70a3b75011e6433a |
| s10e-fg | xcover4 | Android screen-off FGS mesh balanced | 1.84 | 14.8 | 0.931 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | ipx | iOS bg mesh | 0.65 | 6.2 | 0.966 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | se2016 | iOS bg mesh | 0.96 | 3.2 | 0.483 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | ip15 | iOS fg mesh | 0.23 | 148.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | se2020 | iOS fg mesh | 0.47 | 115.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | moto30 | Android screen-off FGS mesh balanced | 3.1 | 30.8 | 1.0 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |
| s10e-fg | nothing2 | Android fg none low_latency | 0.37 | 136.4 | 1.0 | 1.0 | 1.0 | 1.0 | a:5baccdd8b5b99cd7 |

## T1/T2: raw bytes as received by Android scanners (distinct per cell, scanner, payload)

- pixel6-fg → xcover4 (Android screen-off FGS mesh balanced), 7b:b3:79:ff:0d:bd, id a:70a3b75011e6433a: `02010205054853454d1107e9eaa20031304c4d3a43e61150b7a370`
- pixel6-fg → moto30 (Android screen-off FGS mesh balanced), 7b:b3:79:ff:0d:bd, id a:70a3b75011e6433a: `02010205054853454d1107e9eaa20031304c4d3a43e61150b7a370`
- pixel6-fg → nothing2 (Android fg none low_latency), 7b:b3:79:ff:0d:bd, id a:70a3b75011e6433a: `02010205054853454d1107e9eaa20031304c4d3a43e61150b7a370`
- s10e-fg → xcover4 (Android screen-off FGS mesh balanced), 4f:bb:5e:22:0b:8b, id a:5baccdd8b5b99cd7: `02011a05054853454d1107ad86ec0031304c4dd79cb9b5d8cdac5b`
- s10e-fg → moto30 (Android screen-off FGS mesh balanced), 4f:bb:5e:22:0b:8b, id a:5baccdd8b5b99cd7: `02011a05054853454d1107ad86ec0031304c4dd79cb9b5d8cdac5b`
- s10e-fg → nothing2 (Android fg none low_latency), 4f:bb:5e:22:0b:8b, id a:5baccdd8b5b99cd7: `02011a05054853454d1107ad86ec0031304c4dd79cb9b5d8cdac5b`
