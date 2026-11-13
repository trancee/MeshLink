# Does Kotlin 2.4.21 build with Xcode 27.2, and do its builds run on iOS 15 and 16 phones?

Type: task
Status: open

## Question

Build a throwaway Kotlin/Native framework (Kotlin 2.4.21, Gradle 9.7.1, SKIE 0.10.16, iOS 15.0 deployment target) with Xcode 27.2. Link it into a minimal host app, then install and launch that app on the lab's iOS 15 and iOS 16 iPhones (`ideviceinstaller`, since Xcode 27.2 debugs on devices only from iOS 17) and on an iOS 17 or later phone. Record the warnings, whether it runs, and every command used.

## Context

- Input to [ticket 03](03-toolchain-decision.md); findings in [ticket 01](01-toolchain-refresh.md).
- Prototype work on a `prototype/xcode27-smoke` branch, never merged. Read `docs/agents/lessons.md` ("Device lab") first.
