# What public API shape makes MeshLink easiest to use?

Type: prototype
Status: open
Blocked by: 11

## Question

Prototype Kotlin and Swift API stubs for the five reference scenarios: lifecycle, configuration DSL and presets, peers, send/receive, receipts, errors, diagnostics. Judge ergonomics from both languages. Decide the API shape and error model.

## Scope additions (from decisions)

- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): the Swift side goes through SKIE 0.10.16 (suspend → async, Flow → AsyncSequence, sealed/enum → Swift enums; default arguments are off). The iOS simulator must report "Bluetooth unsupported" through the normal state API. The KGP `abiValidation` dump becomes the API baseline.
