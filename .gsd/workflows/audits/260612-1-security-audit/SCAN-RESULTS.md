# Security Audit Scan Results

**Workflow:** `security-audit`  
**Phase:** scan / triage  
**Date:** 2026-06-12

## Scope and method

Reviewed the Gradle/Kotlin codebase with focused searches for:

- dependency and build configuration risks
- exported Android components and intent-driven entry points
- file-path handling for user-controlled inputs
- secrets, hardcoded credentials, and debug logging
- insecure storage and backup settings

## Findings

| ID | Location | Finding | Exploitability | Severity | Triage |
|---|---|---|---|---|---|
| F-001 | `meshlink-reference/android/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt:172-196`, `meshlink-reference/src/androidMain/kotlin/ch/trancee/meshlink/reference/platform/PlatformServices.kt:34-90` | Exported launcher activity accepts automation extras from its intent and passes `storageSubdirectory` into internal file paths via string concatenation without normalization. A malicious app can launch the activity with `EXTRA_UI_AUTOMATION=true` and a traversal payload to redirect writes into unintended app-private directories. | Practical from any app installed on the device; no permission gate. | High | Remediate now |
| F-002 | `meshlink-reference/android/src/main/kotlin/ch/trancee/meshlink/reference/MainActivity.kt:152-157`, `meshlink-reference/android/src/main/kotlin/ch/trancee/meshlink/reference/DirectProofPowerService.kt:24-45` | Informational `Log.i` statements report automation and wake-lock lifecycle state. No secrets were observed, but these logs may leak operational details on debuggable devices or in log collection workflows. | Low; requires log access and does not expose credentials. | Low | Document only |

## Triage summary

- **Remediate now:** F-001
- **Track for later:** F-002

## Notes

- `android:allowBackup="false"` is already set, reducing backup exposure.
- No hardcoded secrets, tokens, cleartext transport settings, or web-security headers were found in the audited mobile modules.
- No dependency-audit failure was surfaced by static review; a full dependency scanner is not configured in the repository.
