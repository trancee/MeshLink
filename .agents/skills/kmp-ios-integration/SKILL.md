---
name: kmp-ios-integration
description: Kotlin Multiplatform iOS integration reference covering all methods for connecting a KMP shared module to an iOS Xcode project. Includes direct integration (embedAndSignAppleFrameworkForXcode build phase script), SwiftPM export (XCFrameworks + Package.swift for remote distribution) and import (swiftPMDependencies DSL, Experimental), CocoaPods integration (plugin setup, pod dependencies from CocoaPods repo/local/Git/custom Podspec, cinterop options, DSL reference), local SwiftPM packages, iOS-specific dependencies (Apple SDK, cinterop .def files for Objective-C libraries and frameworks), and native binary configuration (framework/executable/sharedLib/staticLib, XCFrameworks, fat frameworks, dependency export, Info.plist customization). Use when setting up iOS integration for a KMP project, choosing between direct/SwiftPM/CocoaPods, configuring Xcode build phases, adding Pod dependencies, building XCFrameworks, exporting KMP modules for Swift consumption, importing Swift packages into Kotlin, or troubleshooting iOS build issues.
---

<essential_principles>

**KMP iOS Integration** connects a Kotlin Multiplatform shared module to an iOS Xcode project. Three main approaches exist — pick based on your setup:

### Decision Tree

| Situation | Method |
|-----------|--------|
| No CocoaPods deps, mono-repo | **Direct integration** (default) |
| Need local Swift packages in mono-repo | **SwiftPM local** (pre-action script) |
| Have CocoaPods deps in KMP project | **CocoaPods integration** |
| Separate codebases, distribute as package | **SwiftPM export** (XCFrameworks) |
| Want to import Swift packages into Kotlin | **SwiftPM import** (Experimental, Kotlin 2.4.0+) |

### Core Rules

**Direct integration** uses `embedAndSignAppleFrameworkForXcode` Gradle task in an Xcode Run Script build phase. Requires `binaries.framework` in Gradle config. Cannot coexist with CocoaPods plugin.

**CocoaPods** uses `kotlin("native.cocoapods")` plugin. Configure in `cocoapods {}` block with `version`, `summary`, `homepage`, `framework { baseName }`. Add Pod deps with `pod("Name") { version = "..." }`. Import as `cocoapods.<name>.*`.

**SwiftPM export** builds an XCFramework via `assembleXCFramework`, uploads ZIP, creates `Package.swift` with `binaryTarget`. Separate Git repo for the manifest is recommended.

**SwiftPM import** (Experimental) uses `swiftPMDependencies {}` block. APIs namespaced under `swiftPMImport.<group>.<project>.<Module>`. Only Objective-C and `@objc`-exported Swift — pure Swift not supported.

**iOS dependencies** — Apple SDK available automatically. Third-party Obj-C/Swift frameworks via cinterop (`.def` files with `headers`/`modules` + `package`) or CocoaPods.

**Native binaries** — `framework`, `executable`, `sharedLib`, `staticLib`. Only `api` dependencies can be exported. Use XCFrameworks over fat frameworks.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Direct integration (build phase script, embedAndSignAppleFrameworkForXcode, Xcode setup, sandboxing, custom build configs, migrating from CocoaPods, local Swift packages via pre-action) | `references/direct-integration.md` |
| SwiftPM (exporting XCFrameworks + Package.swift for remote distribution, importing Swift packages into KMP with swiftPMDependencies DSL, version specifiers, Clang module discovery, platform constraints, local packages, lock files, deployment targets) | `references/swiftpm.md` |
| CocoaPods (plugin setup, cocoapods {} config, pod dependencies from repo/local/Git/Podspec, cinterop options, packageName, linkOnly, useInteropBindingFrom, DSL reference, deployment targets, troubleshooting) | `references/cocoapods.md` |
| iOS dependencies (Apple SDK, cinterop for Obj-C libraries and frameworks, .def files), native binaries (framework/executable/sharedLib/staticLib, build types, accessing binaries, exporting dependencies, Info.plist), XCFrameworks (setup, build tasks, CocoaPods publishing), fat frameworks | `references/dependencies-and-binaries.md` |

</routing>

<reference_index>

**direct-integration.md** — when to use (no CocoaPods deps, mono-repo, IDE plugin default), binaries.framework declaration, Xcode Run Script phase setup (embedAndSignAppleFrameworkForXcode task), OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED guard, script placement before Compile Sources, disable "Based on dependency analysis", disable "User Script Sandboxing", ./gradlew --stop for sandboxed daemons, KOTLIN_FRAMEWORK_BUILD_TYPE user-defined setting for custom Xcode configs, how the task works (build/copy/sign), migrating from CocoaPods (pod deintegrate, remove cocoapods{} block, delete .podspec+Podfile), local SwiftPM packages via scheme pre-action (Edit Scheme → Build → Pre-actions), advantage of pre-action approach, Kotlin 2.0.0+ requirement, static linking requirement

**swiftpm.md** — XCFramework export setup (XCFramework() in Gradle, assembleXCFramework task, binaryOption bundleId), ZIP+checksum (swift package compute-checksum), Package.swift manifest (binaryTarget with url+checksum), repository layout options (separate repo recommended vs same repo vs consumer repo), validation (swift package show-dependencies), adding package in Xcode, exporting multiple modules (umbrella module with export+api deps, empty .kt workaround), SwiftPM import (Experimental, Kotlin 2.4.0-Beta2+), swiftPMDependencies{} block, swiftPackage() with url/version, version specifiers (from/exact/branch/revision), namespaced imports (swiftPMImport.group.project.Module), discoverClangModulesImplicitly, importedClangModules, platform constraints (iOS()), localSwiftPackage(), integrateLinkagePackage task, Package.resolved lock files in .swiftpm-locks/, iosMinimumDeploymentTarget, limitations (pure Swift not supported, export not supported)

**cocoapods.md** — when to use (CocoaPods deps, mono-repo with Pods), install via RVM/rbenv (avoid Homebrew), kotlin("native.cocoapods") plugin, cocoapods{} block (version/summary/homepage/name/framework{baseName,isStatic}), xcodeConfigurationToNativeBuildType mapping, Podfile setup (pod with :path), pod install → .xcworkspace, pod() function for dependencies (from CocoaPods repo with version, local path with source=path(), Git with source=git()+tag/branch/commit, custom Podspec with specRepos{url()}), custom cinterop (packageName, extraOpts, -fmodules for @import directives), linkOnly option, useInteropBindingFrom() for dependent Pods, full DSL reference table (cocoapods{} properties, framework{} properties, pod() parameters), deployment targets (ios/osx/tvos/watchos), troubleshooting (CocoaPods path in local.properties, module not found → moduleName/headers, rsync error → disable sandboxing)

**dependencies-and-binaries.md** — Apple SDK available automatically, Obj-C interop rules (pure Swift not supported), cinterop for libraries (.def file with language/headers/package, includeDirs, linkerOpts), cinterop for frameworks (.def with modules instead of headers, compilerOpts/linkerOpts -framework -F), native binary types (executable/test/sharedLib/staticLib/framework), build types (DEBUG/RELEASE), binaries{} declaration, accessing binaries by name pattern (<prefix><BuildType><Kind>), typed getters (getFramework/getExecutable/findFramework), exporting dependencies (only api deps, export() in binaries block, transitiveExport not recommended), Info.plist customization (bundleId/bundleVersion/bundleShortVersionString via binaryOption), XCFramework setup (XCFramework() + xcf.add(this)), build tasks (assembleXCFramework, assemble<Name>Debug/ReleaseXCFramework), CocoaPods XCFramework publishing (podPublishReleaseXCFramework/podPublishDebugXCFramework/podPublishXCFramework), fat frameworks (FatFrameworkTask, baseName must match, prefer XCFrameworks)

</reference_index>
