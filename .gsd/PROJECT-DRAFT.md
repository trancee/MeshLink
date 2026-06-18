# MeshLink project draft

## Current state
- The X25519 fallback path uses the shared `PureX25519` implementation directly.
- The pure ladder has been optimized to reuse scratch buffers and avoid repeated allocation churn.
- X25519 keypair generation has a trusted no-copy path for already-clamped private keys.
- Fallback ChaCha20-Poly1305 now builds its Poly1305 transcript into an exact-sized buffer.
- The Android L2CAP reconnect warning was cleaned up.

## Verification state
- Full JVM and Android host unit suites were rerun successfully during the implementation pass.
- Android main compilation was rerun successfully after the warning cleanup.
- JVM benchmarks were rerun and the retained X25519 benchmark baselines were refreshed.

## Notes
- The branch work was merged and the local checkout is back on `main`.
- The JCA/provider-backed X25519 path remains the preferred fast path; the pure path is the compatibility fallback.
