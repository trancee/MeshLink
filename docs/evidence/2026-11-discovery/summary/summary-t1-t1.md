# T1 summary: iOS advertisement bytes as received by Android

Runs: t1-20261102

Raw = ADV (passive scanner) or ADV + scan response (active scanner), trailing zero bytes trimmed.

## se2016

- e → pixel6 (Android fg none low_latency passive) +1.7 s 59:a8:92:dc:ac:dc id a:8bc3a98058364f5e legacy=True conn=True: `02011a020a0c1107463cf80031304c4d5e4f365880a9c38b05054853454d`
- e-name8 → pixel6 (Android fg none low_latency passive) +1.7 s 59:a8:92:dc:ac:dc id a:d28b9e6035c807d8 legacy=True conn=True: `02011a020a0c110721af980031304c4dd807c835609e8bd205054853454d`
- e-name10 → pixel6 (Android fg none low_latency passive) +1.7 s 59:a8:92:dc:ac:dc id a:961740095a2e09c5 legacy=True conn=True: `02011a020a0c11076fda8a0031304c4dc5092e5a0940179605054853454d`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +1.7 s 59:a8:92:dc:ac:dc id a:4631e76d7a7119da legacy=True conn=True: `02011a020a0c110679ac6a0031304c4dda19717a6de7314605054853454d`
- e-bg → pixel6 (Android fg none low_latency passive) +2.4 s 59:a8:92:dc:ac:dc id a:75a122c50e191573 legacy=True conn=True: `02011a020a0c1107efe6d80031304c4d7315190ec522a17505054853454d`
- e-bg → pixel6 (Android fg none low_latency passive) +2.7 s 59:a8:92:dc:ac:dc id apple-ovf legacy=True conn=True: `02011a020a0c14ff4c000100000000000000000000000001000008`
- e-again → pixel6 (Android fg none low_latency passive) +1.6 s 59:a8:92:dc:ac:dc id a:0e9da3176d922ee7 legacy=True conn=True: `02011a020a0c1107dc023d0031304c4de72e926d17a39d0e05054853454d`
- e → nothing2 (Android fg none low_latency) +1.7 s 59:a8:92:dc:ac:dc id a:8bc3a98058364f5e legacy=True conn=True: `02011a020a0c1107463cf80031304c4d5e4f365880a9c38b05054853454d0a094d4c2d736532303136`
- e-name8 → nothing2 (Android fg none low_latency) +1.7 s 59:a8:92:dc:ac:dc id a:d28b9e6035c807d8 legacy=True conn=True: `02011a020a0c110721af980031304c4dd807c835609e8bd205054853454d09094142434445464748`
- e-name10 → nothing2 (Android fg none low_latency) +1.8 s 59:a8:92:dc:ac:dc id a:961740095a2e09c5 legacy=True conn=True: `02011a020a0c11076fda8a0031304c4dc5092e5a0940179605054853454d0b094142434445464748494a`
- e-uuid3 → nothing2 (Android fg none low_latency) +1.9 s 59:a8:92:dc:ac:dc id a:4631e76d7a7119da legacy=True conn=True: `02011a020a0c110679ac6a0031304c4dda19717a6de7314605054853454d14ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +2.4 s 59:a8:92:dc:ac:dc id a:75a122c50e191573 legacy=True conn=True: `02011a020a0c1107efe6d80031304c4d7315190ec522a17505054853454d14ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +2.7 s 59:a8:92:dc:ac:dc id apple-ovf legacy=True conn=True: `02011a020a0c14ff4c00010000000000000000000000000100000814ff4c00010000000004`
- e-again → nothing2 (Android fg none low_latency) +1.7 s 59:a8:92:dc:ac:dc id a:0e9da3176d922ee7 legacy=True conn=True: `02011a020a0c1107dc023d0031304c4de72e926d17a39d0e05054853454d14ff4c00010000000004`

## ipx

- e → pixel6 (Android fg none low_latency passive) +1.7 s 4a:6a:f7:d9:c9:8f id a:7003bc43f2b33746 legacy=True conn=True: `02011a020a0c1107f249a50031304c4d4637b3f243bc037005054853454d`
- e → pixel6 (Android fg none low_latency passive) +31.7 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e-name8 → pixel6 (Android fg none low_latency passive) +1.7 s 4a:6a:f7:d9:c9:8f id a:38066126f9af5682 legacy=True conn=True: `02011a020a0c110706a6050031304c4d8256aff92661063805054853454d`
- e-name8 → pixel6 (Android fg none low_latency passive) +31.7 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e-name10 → pixel6 (Android fg none low_latency passive) +1.7 s 4a:6a:f7:d9:c9:8f id a:7bce2f84bbe55edf legacy=True conn=True: `02011a020a0c1107f883da0031304c4ddf5ee5bb842fce7b05054853454d`
- e-name10 → pixel6 (Android fg none low_latency passive) +31.7 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +1.7 s 4a:6a:f7:d9:c9:8f id a:4adc27da633e1ab6 legacy=True conn=True: `02011a020a0c11062809460031304c4db61a3e63da27dc4a05054853454d`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +31.7 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e-bg → pixel6 (Android fg none low_latency passive) +2.4 s 4a:6a:f7:d9:c9:8f id a:b083f432a2b38950 legacy=True conn=True: `02011a020a0c1107d8a3ec0031304c4d5089b3a232f483b005054853454d`
- e-bg → pixel6 (Android fg none low_latency passive) +2.7 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e-again → pixel6 (Android fg none low_latency passive) +1.7 s 4a:6a:f7:d9:c9:8f id a:b76cc65bd41f1c19 legacy=True conn=True: `02011a020a0c11077c98720031304c4d191c1fd45bc66cb705054853454d`
- e-again → pixel6 (Android fg none low_latency passive) +31.8 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d`
- e → nothing2 (Android fg none low_latency) +1.7 s 4a:6a:f7:d9:c9:8f id a:7003bc43f2b33746 legacy=True conn=True: `02011a020a0c1107f249a50031304c4d4637b3f243bc037005054853454d14ff4c00010000000008`
- e → nothing2 (Android fg none low_latency) +31.7 s 4a:6a:f7:d9:c9:8f id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d14ff4c00010000000008`
- e-name8 → nothing2 (Android fg none low_latency) +1.7 s 4a:6a:f7:d9:c9:8f id a:38066126f9af5682 legacy=True conn=True: `02011a020a0c110706a6050031304c4d8256aff92661063805054853454d09094142434445464748`
- e-name8 → nothing2 (Android fg none low_latency) +32.5 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d09094142434445464748`
- e-name10 → nothing2 (Android fg none low_latency) +1.7 s 4a:6a:f7:d9:c9:8f id a:7bce2f84bbe55edf legacy=True conn=True: `02011a020a0c1107f883da0031304c4ddf5ee5bb842fce7b05054853454d0b094142434445464748494a`
- e-name10 → nothing2 (Android fg none low_latency) +34.2 s 4a:6a:f7:d9:c9:8f id None legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d0b094142434445464748494a`
- e-uuid3 → nothing2 (Android fg none low_latency) +1.8 s 4a:6a:f7:d9:c9:8f id a:4adc27da633e1ab6 legacy=True conn=True: `02011a020a0c11062809460031304c4db61a3e63da27dc4a05054853454d14ff4c00010000000004`
- e-uuid3 → nothing2 (Android fg none low_latency) +32.0 s 4a:6a:f7:d9:c9:8f id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d14ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +2.4 s 4a:6a:f7:d9:c9:8f id a:b083f432a2b38950 legacy=True conn=True: `02011a020a0c1107d8a3ec0031304c4d5089b3a232f483b005054853454d14ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +2.7 s 4a:6a:f7:d9:c9:8f id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d14ff4c000100000000000000000000000000100008`
- e-again → nothing2 (Android fg none low_latency) +1.7 s 4a:6a:f7:d9:c9:8f id a:b76cc65bd41f1c19 legacy=True conn=True: `02011a020a0c11077c98720031304c4d191c1fd45bc66cb705054853454d14ff4c000100000000000000000000000000100008`
- e-again → nothing2 (Android fg none low_latency) +31.8 s 4a:6a:f7:d9:c9:8f id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005431cde007d14ff4c000100000000000000000000000000100008`

## ip15

- e → pixel6 (Android fg none low_latency passive) +1.8 s 62:14:7c:36:0e:33 id a:936b7f1563a3b28a legacy=True conn=True: `02011a020a0c1107f688df0031304c4d8ab2a363157f6b9305054853454d`
- e → pixel6 (Android fg none low_latency passive) +31.8 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b095`
- e-name8 → pixel6 (Android fg none low_latency passive) +1.0 s 62:14:7c:36:0e:33 id a:c8a61d0be467f023 legacy=True conn=True: `02011a020a0c11075c1e530031304c4d23f067e40b1da6c805054853454d`
- e-name8 → pixel6 (Android fg none low_latency passive) +31.0 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b095`
- e-name10 → pixel6 (Android fg none low_latency passive) +1.0 s 62:14:7c:36:0e:33 id a:b1c81dcb6d93d7e7 legacy=True conn=True: `02011a020a0c110753688b0031304c4de7d7936dcb1dc8b105054853454d`
- e-name10 → pixel6 (Android fg none low_latency passive) +31.0 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b095`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +1.0 s 62:14:7c:36:0e:33 id a:4f48bca6f2204d99 legacy=True conn=True: `02011a020a0c1106e80d0b0031304c4d994d20f2a6bc484f05054853454d`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +31.0 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b095`
- e-again → pixel6 (Android fg none low_latency passive) +1.0 s 62:14:7c:36:0e:33 id a:f20830aa59545451 legacy=True conn=True: `02011a020a0c110748da3a0031304c4d51545459aa3008f205054853454d`
- e-again → pixel6 (Android fg none low_latency passive) +31.2 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b095`
- e → nothing2 (Android fg none low_latency) +1.8 s 62:14:7c:36:0e:33 id a:936b7f1563a3b28a legacy=True conn=True: `02011a020a0c1107f688df0031304c4d8ab2a363157f6b9305054853454d14ff4c000100000000000000000000000000080008`
- e → nothing2 (Android fg none low_latency) +31.8 s 62:14:7c:36:0e:33 id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005631c23b09514ff4c000100000000000000000000000000080008`
- e-name8 → nothing2 (Android fg none low_latency) +1.0 s 62:14:7c:36:0e:33 id a:c8a61d0be467f023 legacy=True conn=True: `02011a020a0c11075c1e530031304c4d23f067e40b1da6c805054853454d09094142434445464748`
- e-name8 → nothing2 (Android fg none low_latency) +31.0 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b09509094142434445464748`
- e-name10 → nothing2 (Android fg none low_latency) +1.0 s 62:14:7c:36:0e:33 id a:b1c81dcb6d93d7e7 legacy=True conn=True: `02011a020a0c110753688b0031304c4de7d7936dcb1dc8b105054853454d0b094142434445464748494a`
- e-name10 → nothing2 (Android fg none low_latency) +31.6 s 62:14:7c:36:0e:33 id None legacy=True conn=True: `02011a020a0c0aff4c001005631c23b0950b094142434445464748494a`
- e-uuid3 → nothing2 (Android fg none low_latency) +1.1 s 62:14:7c:36:0e:33 id a:4f48bca6f2204d99 legacy=True conn=True: `02011a020a0c1106e80d0b0031304c4d994d20f2a6bc484f05054853454d14ff4c00010000000004`
- e-uuid3 → nothing2 (Android fg none low_latency) +32.1 s 62:14:7c:36:0e:33 id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005631c23b09514ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +4.7 s 62:14:7c:36:0e:33 id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005631c23b09514ff4c000100000000000000000000000000008008`
- e-again → nothing2 (Android fg none low_latency) +1.0 s 62:14:7c:36:0e:33 id a:f20830aa59545451 legacy=True conn=True: `02011a020a0c110748da3a0031304c4d51545459aa3008f205054853454d14ff4c000100000000000000000000000000008008`
- e-again → nothing2 (Android fg none low_latency) +31.2 s 62:14:7c:36:0e:33 id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005631c23b09514ff4c000100000000000000000000000000008008`

## se2020

- e → pixel6 (Android fg none low_latency passive) +1.4 s 43:31:c7:8e:17:7d id a:e40aafbf2de26385 legacy=True conn=True: `02011a020a0b11075383470031304c4d8563e22dbfaf0ae405054853454d`
- e → pixel6 (Android fg none low_latency passive) +31.4 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e-name8 → pixel6 (Android fg none low_latency passive) +1.3 s 43:31:c7:8e:17:7d id a:98fa14e58ea856c0 legacy=True conn=True: `02011a020a0b11073429c90031304c4dc056a88ee514fa9805054853454d`
- e-name8 → pixel6 (Android fg none low_latency passive) +31.3 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e-name10 → pixel6 (Android fg none low_latency passive) +1.2 s 43:31:c7:8e:17:7d id a:6be3e6a75b87bffb legacy=True conn=True: `02011a020a0b11078585370031304c4dfbbf875ba7e6e36b05054853454d`
- e-name10 → pixel6 (Android fg none low_latency passive) +31.3 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +1.2 s 43:31:c7:8e:17:7d id a:13af37f85168f1cd legacy=True conn=True: `02011a020a0b1106c0cd8d0031304c4dcdf16851f837af1305054853454d`
- e-uuid3 → pixel6 (Android fg none low_latency passive) +31.5 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e-bg → pixel6 (Android fg none low_latency passive) +1.8 s 43:31:c7:8e:17:7d id a:d1308b672e07fa23 legacy=True conn=True: `02011a020a0b1107a9d0dd0031304c4d23fa072e678b30d105054853454d`
- e-bg → pixel6 (Android fg none low_latency passive) +2.4 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e-again → pixel6 (Android fg none low_latency passive) +1.2 s 43:31:c7:8e:17:7d id a:fa75057f7f92e5ae legacy=True conn=True: `02011a020a0b110763853e0031304c4daee5927f7f0575fa05054853454d`
- e-again → pixel6 (Android fg none low_latency passive) +31.2 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce71287`
- e → nothing2 (Android fg none low_latency) +1.4 s 43:31:c7:8e:17:7d id a:e40aafbf2de26385 legacy=True conn=True: `02011a020a0b11075383470031304c4d8563e22dbfaf0ae405054853454d14ff4c00010000000008`
- e → nothing2 (Android fg none low_latency) +32.5 s 43:31:c7:8e:17:7d id apple-ovf legacy=True conn=True: `02011a020a0b0aff4c001005581ce7128714ff4c00010000000008`
- e-name8 → nothing2 (Android fg none low_latency) +1.3 s 43:31:c7:8e:17:7d id a:98fa14e58ea856c0 legacy=True conn=True: `02011a020a0b11073429c90031304c4dc056a88ee514fa9805054853454d09094142434445464748`
- e-name8 → nothing2 (Android fg none low_latency) +18.4 s 7f:23:ee:59:ec:54 id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c001005461c7734ca14ff4c000100000000000000000000000000100008`
- e-name8 → nothing2 (Android fg none low_latency) +20.9 s 49:a7:87:3c:f8:8c id apple-ovf legacy=True conn=True: `02011a020a0c0aff4c0010056f1c03004614ff4c000100000000000000000000000000008008`
- e-name8 → nothing2 (Android fg none low_latency) +31.9 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce7128709094142434445464748`
- e-name10 → nothing2 (Android fg none low_latency) +1.3 s 43:31:c7:8e:17:7d id a:6be3e6a75b87bffb legacy=True conn=True: `02011a020a0b11078585370031304c4dfbbf875ba7e6e36b05054853454d0b094142434445464748494a`
- e-name10 → nothing2 (Android fg none low_latency) +31.3 s 43:31:c7:8e:17:7d id None legacy=True conn=True: `02011a020a0b0aff4c001005581ce712870b094142434445464748494a`
- e-uuid3 → nothing2 (Android fg none low_latency) +1.2 s 43:31:c7:8e:17:7d id a:13af37f85168f1cd legacy=True conn=True: `02011a020a0b1106c0cd8d0031304c4dcdf16851f837af1305054853454d14ff4c00010000000004`
- e-uuid3 → nothing2 (Android fg none low_latency) +31.8 s 43:31:c7:8e:17:7d id apple-ovf legacy=True conn=True: `02011a020a0b0aff4c001005581ce7128714ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +1.8 s 43:31:c7:8e:17:7d id a:d1308b672e07fa23 legacy=True conn=True: `02011a020a0b1107a9d0dd0031304c4d23fa072e678b30d105054853454d14ff4c00010000000004`
- e-bg → nothing2 (Android fg none low_latency) +2.4 s 43:31:c7:8e:17:7d id apple-ovf legacy=True conn=True: `02011a020a0b0aff4c001005581ce7128714ff4c000100800000000000000000000000000008`
- e-again → nothing2 (Android fg none low_latency) +1.2 s 43:31:c7:8e:17:7d id a:fa75057f7f92e5ae legacy=True conn=True: `02011a020a0b110763853e0031304c4daee5927f7f0575fa05054853454d14ff4c000100800000000000000000000000000008`
- e-again → nothing2 (Android fg none low_latency) +31.2 s 43:31:c7:8e:17:7d id apple-ovf legacy=True conn=True: `02011a020a0b0aff4c001005581ce7128714ff4c000100800000000000000000000000000008`

