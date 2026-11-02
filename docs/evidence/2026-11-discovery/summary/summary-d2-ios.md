# D2 summary: background visibility matrix

Runs: d2-ios2-20261102

Latency = first sighting − advertiser start (host clock). Recall = share of complete windows with ≥ 1 sighting,
from the first sighting to the cell end. Hint = share of sightings carrying the advertiser's metadata (layout E rotating UUID).

| Cell | Scanner | Scanner state | Latency (s) | Per min | Recall 10 s | 30 s | 60 s | Hint | First id |
|---|---|---|---|---|---|---|---|---|---|
| ip15-fg | xcover4 | Android screen-off FGS apple balanced | 0.43 | 105.8 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-fg | ipx | iOS fg mesh | 0.06 | 684.8 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-fg | ip12mini | iOS bg mesh | 0.17 | 408.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-fg | se2020 | iOS bg mesh | 0.36 | 9.4 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-fg | moto30 | Android screen-off FGS mesh balanced | 0.69 | 169.2 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-fg | nothing2 | Android fg none low_latency | 0.19 | 661.6 | 1.0 | 1.0 | 1.0 | 1.0 | a:83218c2b1467d241 |
| ip15-bg | xcover4 | Android screen-off FGS apple balanced | 0.14 | 18.4 | 0.966 | 1.0 | 1.0 | 0.011 | a:f25fe6f106279307 |
| ip15-bg | ipx | iOS fg mesh | 0.04 | 138.1 | 1.0 | 1.0 | 1.0 | 0.028 | a:f25fe6f106279307 |
| ip15-bg | ip12mini | iOS bg mesh | 0.09 | 0.6 | 0.034 | 0.111 | 0.25 | 1.0 | a:f25fe6f106279307 |
| ip15-bg | se2020 | iOS bg mesh | 0.04 | 0.2 | 0.034 | 0.111 | 0.25 | 1.0 | a:f25fe6f106279307 |
| ip15-bg | moto30 | Android screen-off FGS mesh balanced | 0.14 | 0.4 | 0.034 | 0.111 | 0.25 | 1.0 | a:f25fe6f106279307 |
| ip15-bg | nothing2 | Android fg none low_latency | -0.57 | 127.7 | 1.0 | 1.0 | 1.0 | 0.0 | apple-ovf |
| ip15-lock | xcover4 | Android screen-off FGS apple balanced | 11.58 | 14.1 | 0.929 | 1.0 | 1.0 | 0.0 | apple-ovf |
| ip15-lock | ipx | iOS fg mesh | 0.42 | 118.7 | 1.0 | 1.0 | 1.0 | 0.0 | svc |
| ip15-lock | ip12mini | iOS bg mesh | not seen | 0.0 | None | None | None | None | None |
| ip15-lock | se2020 | iOS bg mesh | not seen | 0.0 | None | None | None | None | None |
| ip15-lock | moto30 | Android screen-off FGS mesh balanced | not seen | 0.0 | None | None | None | None | None |
| ip15-lock | nothing2 | Android fg none low_latency | -0.75 | 121.9 | 1.0 | 1.0 | 1.0 | 0.0 | apple-ovf |
| se2016-fg | xcover4 | Android screen-off FGS apple balanced | not seen | 0.0 | None | None | None | None | None |
| se2016-fg | ipx | iOS fg mesh | 0.0 | 667.9 | 1.0 | 1.0 | 1.0 | 1.0 | a:7ff7c50fb55e0885 |
| se2016-fg | ip12mini | iOS bg mesh | 0.13 | 434.0 | 1.0 | 1.0 | 1.0 | 1.0 | a:7ff7c50fb55e0885 |
| se2016-fg | se2020 | iOS bg mesh | 0.08 | 9.5 | 1.0 | 1.0 | 1.0 | 1.0 | a:7ff7c50fb55e0885 |
| se2016-fg | moto30 | Android screen-off FGS mesh balanced | 0.12 | 196.5 | 1.0 | 1.0 | 1.0 | 1.0 | a:7ff7c50fb55e0885 |
| se2016-fg | nothing2 | Android fg none low_latency | 0.05 | 832.7 | 1.0 | 1.0 | 1.0 | 1.0 | a:7ff7c50fb55e0885 |
| se2016-bg | xcover4 | Android screen-off FGS apple balanced | 1.46 | 19.2 | 0.933 | 1.0 | 1.0 | 0.0 | name:ML-se2016 |
| se2016-bg | ipx | iOS fg mesh | 0.1 | 2.0 | 0.033 | 0.1 | 0.2 | 1.0 | a:64d4134ab0580faa |
| se2016-bg | ip12mini | iOS bg mesh | 0.14 | 0.4 | 0.033 | 0.1 | 0.2 | 0.5 | a:64d4134ab0580faa |
| se2016-bg | se2020 | iOS bg mesh | 0.06 | 0.2 | 0.033 | 0.1 | 0.2 | 1.0 | a:64d4134ab0580faa |
| se2016-bg | moto30 | Android screen-off FGS mesh balanced | not seen | 0.0 | None | None | None | None | None |
| se2016-bg | nothing2 | Android fg none low_latency | 0.03 | 158.7 | 1.0 | 1.0 | 1.0 | 0.01 | a:64d4134ab0580faa |
| se2016-lock | xcover4 | Android screen-off FGS apple balanced | 0.09 | 10.9 | 0.8 | 1.0 | 1.0 | 0.0 | name:ML-se2016 |
| se2016-lock | ipx | iOS fg mesh | 1.24 | 1.2 | 0.033 | 0.1 | 0.2 | 0.0 | svc |
| se2016-lock | ip12mini | iOS bg mesh | not seen | 0.0 | None | None | None | None | None |
| se2016-lock | se2020 | iOS bg mesh | not seen | 0.0 | None | None | None | None | None |
| se2016-lock | moto30 | Android screen-off FGS mesh balanced | not seen | 0.0 | None | None | None | None | None |
| se2016-lock | nothing2 | Android fg none low_latency | 0.29 | 128.6 | 1.0 | 1.0 | 1.0 | 0.0 | name:ML-se2016 |

## T1/T2: raw bytes as received by Android scanners (distinct per cell, scanner, payload)

- ip15-fg → xcover4 (Android screen-off FGS apple balanced), 45:94:9d:28:ce:d1, id a:83218c2b1467d241: `02011a020a0c1107d13bdc0031304c4d41d267142b8c218305054853454d14ff4c00010000000008`
- ip15-fg → moto30 (Android screen-off FGS mesh balanced), 45:94:9d:28:ce:d1, id a:83218c2b1467d241: `02011a020a0c1107d13bdc0031304c4d41d267142b8c218305054853454d14ff4c00010000000008`
- ip15-fg → nothing2 (Android fg none low_latency), 45:94:9d:28:ce:d1, id a:83218c2b1467d241: `02011a020a0c1107d13bdc0031304c4d41d267142b8c218305054853454d14ff4c00010000000008`
- ip15-bg → xcover4 (Android screen-off FGS apple balanced), 4c:87:75:96:93:85, id a:f25fe6f106279307: `02011a020a0c11075f97de0031304c4d07932706f1e65ff205054853454d14ff4c00010000000008`
- ip15-bg → xcover4 (Android screen-off FGS apple balanced), 4c:87:75:96:93:85, id apple-ovf: `02011a020a0c0aff4c001005661c27dcc014ff4c000100000000000800000000000000000008`
- ip15-bg → moto30 (Android screen-off FGS mesh balanced), 4c:87:75:96:93:85, id a:f25fe6f106279307: `02011a020a0c11075f97de0031304c4d07932706f1e65ff205054853454d14ff4c00010000000008`
- ip15-bg → nothing2 (Android fg none low_latency), 4c:87:75:96:93:85, id a:f25fe6f106279307: `02011a020a0c11075f97de0031304c4d07932706f1e65ff205054853454d14ff4c00010000000008`
- ip15-bg → nothing2 (Android fg none low_latency), 4c:87:75:96:93:85, id apple-ovf: `02011a020a0c0aff4c001005661c27dcc014ff4c000100000000000800000000000000000008`
- ip15-lock → xcover4 (Android screen-off FGS apple balanced), 4c:87:75:96:93:85, id apple-ovf: `02011a020a0c0aff4c001005661c27dcc014ff4c000100000000000000000100000000000008`
- ip15-lock → nothing2 (Android fg none low_latency), 4c:87:75:96:93:85, id apple-ovf: `02011a020a0c0aff4c001005661c27dcc014ff4c000100000000000000000100000000000008`
- se2016-fg → moto30 (Android screen-off FGS mesh balanced), 47:57:ea:64:0b:84, id a:7ff7c50fb55e0885: `02011a020a0c11078907180031304c4d85085eb50fc5f77f05054853454d0a094d4c2d736532303136`
- se2016-fg → nothing2 (Android fg none low_latency), 47:57:ea:64:0b:84, id a:7ff7c50fb55e0885: `02011a020a0c11078907180031304c4d85085eb50fc5f77f05054853454d0a094d4c2d736532303136`
- se2016-bg → xcover4 (Android screen-off FGS apple balanced), 7e:93:12:4d:55:0b, id name:ML-se2016: `02011a020a0c14ff4c0001000000000000000000000000010000080a094d4c2d736532303136`
- se2016-bg → nothing2 (Android fg none low_latency), 7e:93:12:4d:55:0b, id a:64d4134ab0580faa: `02011a020a0c11078cecc20031304c4daa0f58b04a13d46405054853454d0a094d4c2d736532303136`
- se2016-bg → nothing2 (Android fg none low_latency), 7e:93:12:4d:55:0b, id name:ML-se2016: `02011a020a0c14ff4c0001000000000000000000000000010000080a094d4c2d736532303136`
- se2016-lock → xcover4 (Android screen-off FGS apple balanced), 7e:93:12:4d:55:0b, id name:ML-se2016: `02011a020a0c14ff4c0001000000000080000000000000000000080a094d4c2d736532303136`
- se2016-lock → nothing2 (Android fg none low_latency), 7e:93:12:4d:55:0b, id name:ML-se2016: `02011a020a0c14ff4c0001000000000080000000000000000000080a094d4c2d736532303136`
