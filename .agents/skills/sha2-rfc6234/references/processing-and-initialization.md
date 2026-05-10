# SHA-2 RFC 6234 — Processing and Initialization

<sha256_processing>
## SHA-256 Processing (§6.2)

For each 512-bit message block (sixteen 32-bit words M(i)₀..M(i)₁₅):

### Step 1: Prepare message schedule W (64 words)
```
For t = 0 to 15:
    Wt = M(i)t                                        // direct copy
For t = 16 to 63:
    Wt = SSIG1(W(t-2)) + W(t-7) + SSIG0(W(t-15)) + W(t-16)
```

### Step 2: Initialize working variables
```
a = H(i-1)₀    b = H(i-1)₁    c = H(i-1)₂    d = H(i-1)₃
e = H(i-1)₄    f = H(i-1)₅    g = H(i-1)₆    h = H(i-1)₇
```

### Step 3: 64-round compression
```
For t = 0 to 63:
    T1 = h + BSIG1(e) + CH(e,f,g) + Kt + Wt
    T2 = BSIG0(a) + MAJ(a,b,c)
    h = g
    g = f
    f = e
    e = d + T1
    d = c
    c = b
    b = a
    a = T1 + T2
```

### Step 4: Update hash value
```
H(i)₀ = a + H(i-1)₀    H(i)₄ = e + H(i-1)₄
H(i)₁ = b + H(i-1)₁    H(i)₅ = f + H(i-1)₅
H(i)₂ = c + H(i-1)₂    H(i)₆ = g + H(i-1)₆
H(i)₃ = d + H(i-1)₃    H(i)₇ = h + H(i-1)₇
```

All addition is **mod 2^32**.

### Final Output
- **SHA-256:** concatenate H(N)₀ through H(N)₇ (all 8 words → 256 bits)
- **SHA-224:** concatenate H(N)₀ through H(N)₆ (first 7 words → 224 bits)
</sha256_processing>

<sha512_processing>
## SHA-512 Processing (§6.4)

Same structure as SHA-256 but with 64-bit words and 80 rounds.

For each 1024-bit message block (sixteen 64-bit words M(i)₀..M(i)₁₅):

### Step 1: Prepare message schedule W (80 words)
```
For t = 0 to 15:
    Wt = M(i)t
For t = 16 to 79:
    Wt = SSIG1(W(t-2)) + W(t-7) + SSIG0(W(t-15)) + W(t-16)
```

### Steps 2-4: Identical structure to SHA-256
Same working variable initialization, same compression loop shape, same hash update — but:
- 80 rounds (not 64)
- 64-bit words and addition mod 2^64
- Different rotation constants in BSIG0/BSIG1/SSIG0/SSIG1

### Final Output
- **SHA-512:** concatenate H(N)₀ through H(N)₇ (all 8 words → 512 bits)
- **SHA-384:** concatenate H(N)₀ through H(N)₅ (first 6 words → 384 bits)
</sha512_processing>

<initialization>
## Initial Hash Values (§6.1, §6.3)

### SHA-256 H(0)
First 32 bits of fractional parts of square roots of first 8 primes (2,3,5,7,11,13,17,19):
```
H₀ = 6a09e667    H₄ = 510e527f
H₁ = bb67ae85    H₅ = 9b05688c
H₂ = 3c6ef372    H₆ = 1f83d9ab
H₃ = a54ff53a    H₇ = 5be0cd19
```

### SHA-224 H(0)
Second 32 bits of fractional parts of square roots of 9th through 16th primes (23,29,31,37,41,43,47,53):
```
H₀ = c1059ed8    H₄ = ffc00b31
H₁ = 367cd507    H₅ = 68581511
H₂ = 3070dd17    H₆ = 64f98fa7
H₃ = f70e5939    H₇ = befa4fa4
```

### SHA-512 H(0)
First 64 bits of fractional parts of square roots of first 8 primes:
```
H₀ = 6a09e667f3bcc908    H₄ = 510e527fade682d1
H₁ = bb67ae8584caa73b    H₅ = 9b05688c2b3e6c1f
H₂ = 3c6ef372fe94f82b    H₆ = 1f83d9abfb41bd6b
H₃ = a54ff53a5f1d36f1    H₇ = 5be0cd19137e2179
```

### SHA-384 H(0)
First 64 bits of fractional parts of square roots of 9th through 16th primes:
```
H₀ = cbbb9d5dc1059ed8    H₄ = 67332667ffc00b31
H₁ = 629a292a367cd507    H₅ = 8eb44a8768581511
H₂ = 9159015a3070dd17    H₆ = db0c2e0d64f98fa7
H₃ = 152fecd8f70e5939    H₇ = 47b5481dbefa4fa4
```

### Nothing-Up-My-Sleeve Derivation
- Round constants Kt: fractional parts of **cube roots** of primes
- Initial values H(0): fractional parts of **square roots** of primes
- SHA-224 uses primes 23-53 (9th-16th) to avoid any overlap with SHA-256
- SHA-384 uses primes 23-53 to avoid overlap with SHA-512
</initialization>

<hmac_and_hkdf>
## HMAC and HKDF (§7)

### SHA-Based HMAC (§7.1)
Keyed message authentication code per [RFC 2104]. The RFC provides C code supporting arbitrary bit-length input.

```
HMAC(K, text) = H((K' XOR opad) || H((K' XOR ipad) || text))
```
Where K' is K padded/hashed to block size, opad = 0x5c repeated, ipad = 0x36 repeated.

### HKDF (§7.2)
HMAC-based extract-and-expand key derivation function per [RFC 5869]. C code provided in §8.4.

Two phases:
1. **Extract:** `PRK = HMAC-Hash(salt, IKM)` — produces pseudorandom key
2. **Expand:** `OKM = T(1) || T(2) || ...` where `T(i) = HMAC-Hash(PRK, T(i-1) || info || i)`

### ASN.1 OIDs
```
id-sha224  = 2.16.840.1.101.3.4.2.4
id-sha256  = 2.16.840.1.101.3.4.2.1
id-sha384  = 2.16.840.1.101.3.4.2.2
id-sha512  = 2.16.840.1.101.3.4.2.3
```
</hmac_and_hkdf>
