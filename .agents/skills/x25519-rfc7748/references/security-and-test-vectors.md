# X25519/X448 RFC 7748 — Security, Side Channels & Test Vectors

<side_channels>
## Side-Channel Considerations (§5.1)

X25519 and X448 are designed so constant-time implementations are easier:

1. **Same sequence of field operations** for all scalar values — the Montgomery ladder processes every bit identically
2. **cswap must be constant-time** — implemented as bitwise mask:
   ```
   cswap(swap, x_2, x_3):
       dummy = mask(swap) AND (x_2 XOR x_3)
       x_2 = x_2 XOR dummy
       x_3 = x_3 XOR dummy
       return (x_2, x_3)

   mask(swap) = 0 − swap   // All-1 or all-0 word
   ```
3. **Memory access patterns** must not depend on scalar bits
4. **Jump patterns** must not depend on scalar bits
5. **Arithmetic** must not leak: b·c must be indistinguishable from c·c (no variable-time multiplication)
6. Even primitive instructions (e.g., single-word division) can have variable timing — avoid them

### What Clamping Achieves for Side Channels
- Setting the high bit ensures scalar multiplication always performs the same number of doublings
- Clearing low bits means the result is always in the prime-order subgroup, avoiding small-subgroup attacks without explicit checks
</side_channels>

<security>
## Security Considerations (§7)

### Security Levels
- Curve25519: slightly under 128-bit — acceptable because symmetric primitives drive the power-of-two convention; rigidly matching 128 bits would require compromises
- Curve448: ~224-bit — hedge against analytical advances. Both broken by large quantum computers.

### No Contributory Behaviour
Protocol designers MUST NOT assume both parties' private keys contribute to the shared secret. An input point of small order eliminates the other party's contribution entirely (due to cofactors 8 and 4). Detectable by checking for all-zero output. **Many existing implementations do NOT check.**

### Equivalent Public Keys
For each public key, several publicly computable equivalent keys exist (produce the same shared secrets). Using a public key as an identifier with shared-secret-as-proof-of-ownership (without including public keys in key derivation) can lead to vulnerabilities.

### Implementation Variance
Some implementations use generic EC libraries instead of the Montgomery ladder. These may:
- Reject points on the twist
- Reject non-canonical field elements (≥ p)

Such implementations interoperate but are **trivially distinguishable** from the Montgomery ladder spec. Sending non-canonical values or twist points may cause observable errors in non-conforming implementations while the spec-compliant ladder silently produces a valid shared key.
</security>

<test_vectors>
## Test Vectors (§5.2)

### X25519 Point-to-Point

**Vector 1:**
```
Scalar:       a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4
u-coordinate: e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c
Output:       c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552
```

**Vector 2:**
```
Scalar:       4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d
u-coordinate: e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493
Output:       95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957
```

### X25519 Iterated (start: k = u = 9)
```
After 1 iteration:       422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079
After 1,000 iterations:  684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51
After 1,000,000 iter:    7c3911e0ab2586fd864497297e575e6f3bc601c0883c30df5f4dd2d24f665424
```

### X448 Point-to-Point

**Vector 1:**
```
Scalar:       3d262fddf9ec8e88495266fea19a34d28882acef045104d0d1aae121700a779c984c24f8cdd78fbff44943eba368f54b29259a4f1c600ad3
u-coordinate: 06fce640fa3487bfda5f6cf2d5263f8aad88334cbd07437f020f08f9814dc031ddbdc38c19c6da2583fa5429db94ada18aa7a7fb4ef8a086
Output:       ce3e4ff95a60dc6697da1db1d85e6afbdf79b50a2412d7546d5f239fe14fbaadeb445fc66a01b0779d98223961111e21766282f73dd96b6f
```

**Vector 2:**
```
Scalar:       203d494428b8399352665ddca42f9de8fef600908e0d461cb021f8c538345dd77c3e4806e25f46d3315c44e0a5b4371282dd2c8d5be3095f
u-coordinate: 0fbcc2f993cd56d3305b0b7d9e55d4c1a8fb5dbb52f8e9a1e9b6201b165d015894e56c4d3570bee52fe205e28a78b91cdfbde71ce8d157db
Output:       884a02576239ff7a2f2f63b2db6a9ff37047ac13568e1e30fe63c4a7ad1b3ee3a5700df34321d62077e63633c575c1c954514e99da7c179d
```

### X448 Iterated (start: k = u = 5)
```
After 1 iteration:       3f482c8a9f19b01e6c46ee9711d9dc14fd4bf67af30765c2ae2b846a4d23a8cd0db897086239492caf350b51f833868b9bc2b3bca9cf4113
After 1,000 iterations:  aa3b4749d55b9daf1e5b00288826c467274ce3ebbdd5c17b975e09d4af6c67cf10d087202db88286e2b79fceea3ec353ef54faa26e219f38
After 1,000,000 iter:    077f453681caca3693198420bbe515cae0002472519b3e67661a7e89cab94695c8f4bcd66e61b9b9c946da8d524de3d69bd9d9d66b997e37
```
</test_vectors>

<curve_generation>
## Deterministic Curve Generation (Appendix A)

Curves are generated from the prime p via an objective, reproducible procedure:

### Security Requirements
1. **Trace of Frobenius** not in {0, 1} — rules out Smart/Satoh/Semaev attacks
2. **MOV embedding degree** > (order − 1)/100 — rules out reduction to finite field DLP
3. **CM discriminant** D > 2^100 — rules out complex multiplication attacks

### Generation Procedure
1. Montgomery form: v² = u³ + A·u² + u
2. Choose minimal positive A > 2 where (A−2) divisible by 4 (making a24 a small integer)
3. For p ≡ 1 mod 4 (Curve25519): cofactors {8, 4} (curve, twist). Twist cofactor smaller → no need to check for twist points.
4. For p ≡ 3 mod 4 (Curve448): cofactors {4, 4} (both minimal)
5. Base point: minimal positive u-coordinate in the correct prime-order subgroup

### Results
- Curve25519: A = 486662 (smallest qualifying value), base u = 9
- Curve448: A = 156326 (smallest qualifying value), base u = 5

This "nothing-up-my-sleeve" approach ensures the curves weren't backdoored.
</curve_generation>
