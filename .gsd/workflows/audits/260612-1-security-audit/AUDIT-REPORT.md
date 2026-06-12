# Security Audit Report

**Workflow:** `security-audit`  
**Date:** 2026-06-12  
**Branch:** `gsd/security-audit/security-audit`

## Executive summary

I reviewed the MeshLink reference app for common mobile security issues, triaged the findings, and remediated the one practical high-severity issue discovered during scan.

### Outcome

- **High-severity issue fixed:** external automation input could influence internal file paths without normalization.
- **Low-severity item documented:** informational log statements remain in the direct-proof path; they do not expose secrets.
- **No hardcoded credentials, cleartext transport settings, or insecure backup settings were found.**

## Findings and disposition

### F-001 — High

**Issue:** Exported launcher activity accepted automation extras from its intent and passed `storageSubdirectory` into app-private file paths via string concatenation.

**Risk:** A malicious app could launch the activity with traversal payloads and redirect writes into unintended app-private directories.

**Fix applied:** Added `normalizeAutomationStorageSubdirectory(...)` and routed both automation storage roots through the normalized value before constructing file paths.

**Verification:**

- `:meshlink-reference:ktfmtCheckKmpAndroidMain`
- `:meshlink-reference:jvmTest --tests ch.trancee.meshlink.reference.platform.AutomationStorageSubdirectoryJvmTest`
- `:meshlink-reference:assemble`

### F-002 — Low

**Issue:** Informational `Log.i` statements report automation state and wake-lock lifecycle state.

**Risk:** Operational details may appear in logs on devices or environments that collect them.

**Disposition:** Documented for later review; no remediation required for this workflow.

## Re-scan results

A follow-up build and targeted security regression test passed after the fix.

- The security-focused regression test passed.
- The module assembled successfully.
- A broader `:meshlink-reference:check` run still reports two pre-existing `jvmTest` failures in `SessionExportTest`; those failures are unrelated to the security change and were not modified as part of this audit.

## Recommendations

1. Keep exported Android entry points narrow and validate any intent-derived values before using them in file-system paths.
2. Consider gating automation modes behind explicit debug or trusted-launch checks if these flows are only meant for internal testing.
3. Continue running a dependency scanner in CI if one is added later; this repository currently has no configured automated dependency-audit task.
4. Treat operational logging carefully in release builds, especially around device state and wake-lock handling.

## Skills used

- `security-review` — threat-model-driven review and remediation guidance
- `verify-before-complete` — verification before declaring the fix complete
