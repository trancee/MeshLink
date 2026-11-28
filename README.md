# MeshLink

MeshLink is a Kotlin Multiplatform library for peer-to-peer mesh networks over Bluetooth Low Energy on Android and iOS. Phones find each other without any network infrastructure, exchange messages directly or through other phones in the mesh, and can confirm delivery and reading. Each mesh chooses its security profile: `Unsecured`, `Classical` encryption, or `PostQuantum` encryption.

## Status

MeshLink is being built toward its first release, 0.1.0. Until then, the API may change in any commit, and the artifacts below are not published yet. The [changelog](CHANGELOG.md) lists what exists today.

## Install

| Ecosystem | Coordinates |
|---|---|
| Kotlin (Maven Central) | `ch.trancee.meshlink:meshlink:0.1.0`; tests may add `ch.trancee.meshlink:meshlink-testing:0.1.0` |
| Swift (Swift Package Manager) | `https://github.com/trancee/meshlink-swift`, package `MeshLink`, products `MeshLink` and, for tests, `MeshLinkTesting` |

## Start here

- [Get started](docs/tutorials/get-started.md): build an app whose two phones exchange a message.
- [Documentation](docs/README.md): every guide, reference page and design record, by task.
- [API reference](https://trancee.github.io/MeshLink/api/): the Kotlin API of the latest release, from 0.1.0 on.

## Contribute and report

- [Security policy](SECURITY.md): how to report a vulnerability.
- [`AGENTS.md`](AGENTS.md), [`CONSTITUTION.md`](CONSTITUTION.md) and [`PROJECT.md`](PROJECT.md): the engineering rules, the build and the proof commands.

## License

MeshLink is released into the public domain under [The Unlicense](LICENSE).
