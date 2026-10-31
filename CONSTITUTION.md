# Constitution

```text
R=MUST; X=MUST_NOT; D=SHOULD (deviation=>written task-specific reason); O=MAY; A=>B=if A then B
scope=code|tests|docs|config|automation|release|review
priority=CONSTITUTION>AGENTS>scoped_docs/ADRs>config/templates/comments
```

Lower priority may refine, X weaken; conflict=>higher+report, X invented exception.
Version-controlled project commands/platforms/budgets/artifacts refine policy.

## S: security/trust

- S1 X secrets/credentials/keys/tokens/session/derived-key material/PII in logs/errors/crashes/tests/fixtures/artifacts; R redacted IDs+non-sensitive diagnostics.
- S2 network/file/env/CLI/persistence/third-party response=untrusted unless proven; R boundary validation; invalid=>typed+redacted error/result; X uncontrolled throw/panic/crash/partial mutation.
- S3 Uncertain identity/authentication/authorization/integrity/replay/protocol/config/security-state=>fail closed at smallest safe work unit.
- S4 Failure X implicit trust/security downgrade/stale credential/partial state/corrupt-identity replacement; fallback R specified+security-preserving+observable+tested.
- S5 Every fail-closed branch R typed+redacted reason+automated test.
- S6 Crypto R approved provider/established library; new primitive R spec+expert review+conformance vectors.
- S7 Crypto/security protocol R recognized conformance/adversarial vectors when available.
- S8 Pre-merge secret scan R pass; found secret=>revoke+remove reachable history; current-line deletion insufficient.
- S9 Disclosed vulnerability R merge <=5 business days; CVSS>=9 equivalent <=48h.

## Q: quality

- Q1 Repo formatter+static analysis R pass pre-merge.
- Q2 X production formatter/compiler/static-analysis suppressions; test-only R local applicability reason.
- Q3 Names R full+descriptive; domain terms/acronyms O; X invented abbreviations/ambiguous contractions/unexplained single letters except trivial local indices.
- Q4 Public declarations R explicit visibility+types where supported.
- Q5 Public interfaces R repo compatibility tracking; every diff R release-impact explanation.
- Q6 Released public/serialized semver: breaking=>MINOR if major=0 else MAJOR; additive=>MINOR; compatible fix=>PATCH.
- Q7 Public API removal R documented deprecation >=1 MINOR; immediate only security incident.
- Q8 Merged code X placeholders/disabled implementations/`TODO`; future work=>issue tracker.
- Q9 Comments R intent/constraint/tradeoff only; names+structure explain behavior.
- Q10 Closed sets R exhaustive; X default hiding new state/variant/enum.
- Q11 Long async work R structured concurrency+cancellation; X unbounded polling.
- Q12 Preconditions D idiomatic validator; custom error/result only caller-visible semantic distinction.

## T: tests

- T1 Feature/fix R intended-behavior red->smallest complete implementation->green->refactor green.
- T2 Default CI R deterministic+isolated+repeatable+self-validating; X workstation/retained-process/hardware/manual-inspection dependency.
- T3 Maintained production R 100% line+branch CI coverage; generated/vendor/example/benchmark/internal-tool exclusions O only CI-named; all tests R pass.
- T4 Coverage != behavior proof; R observable behavior/boundaries/invariants/transitions/precedence/real-error tests.
- T5 One primary Act/test; visually distinct Arrange/Act/Assert; unrelated Acts=>split.
- T6 Assertions D failure context; structural equality only for tested structure.
- T7 R public/stable-internal contracts; X source text/incidental order/private detail/time/shared-state dependence unless tested behavior itself.
- T8 Multi-process/node/network/distributed tests D deterministic virtual environment in default suite.
- T9 Mock/simulator != hardware proof; hardware behavior R supported-hardware execution pre-release.
- T10 Security/protocol/parser/serializer R applicable malformed/truncated/boundary/replayed/incompatible/adversarial tests.
- T11 Performance-critical R retained representative benchmarks+comparison-environment metadata.

## C: public/platform contracts

- C1 Public behavior R same across supported platforms unless public spec documents difference.
- C2 Platform code R adapters/platform modules; shared business rules platform-independent where possible.
- C3 Config concepts/defaults/validation/diagnostics/transitions R cross-platform consistent; platform inputs D factory/adapter; X public-config branching.
- C4 X platform errors/types leaking shared API; R documented-error-model translation.
- C5 Diagnostic names/severity/payload shape R stable+documented; payload S1.
- C6 Persisted/network formats R explicit stable IDs; released field/message/enum IDs X reinterpret/reuse.
- C7 Breaking persisted/network format R MAJOR+migration+compatibility tests.
- C8 Public API change R same-change reference docs+examples for every affected platform.
- C9 API above platform minimum R runtime guard or supported fallback.

## P: performance

- P1 Performance-sensitive R numeric resource budgets `{workload,environment,method,threshold}`.
- P2 Every budget R automated benchmark or reproducible measurement.
- P3 Budgeted-path change R current benchmark evidence.
- P4 Regression >10% vs committed baseline blocks merge unless approved budget+rationale update.
- P5 Hot paths R avoid unnecessary allocation/copy/parse/serialization/I/O/recompute; optimization R correctness+measurement.
- P6 Unmeasured performance claim=no evidence.

## D: design

- D1 R simplest approved-requirement design; X speculative extension/framework/config/retry/fallback.
- D2 Module/class/file D one reason to change.
- D3 Abstraction only demonstrated duplication|known hotspot|stable protected contract; one-use/future guess insufficient.
- D4 R low coupling+high cohesion+small stable contracts; X deep traversal/foreign internals.
- D5 D composition/factory/strategy over inheritance; inheritance R substitutable subtype.
- D6 R volatile details behind internal boundaries.
- D7 Command/query R distinct names+contracts; mutating return O if mutation explicit.
- D8 Touched code/tests/docs R no less clear; added complexity R reason.
- D9 Maintained source/test file D <=300 lines, R <=500; split responsibility/layer/platform; generated/vendor exempt.
- Review: God object=>split; shotgun surgery=>centralize boundary; feature envy=>move beside data; premature abstraction=>remove; copy/paste=>extract variance; magic value=>name/document; long method=>extract stages; excessive/restating/dead comments=>names+delete.

## O: documentation/completeness

- O1 Same change set R code/tests/public docs/specs/ADRs/examples/generated artifacts/release metadata agree.
- O2 Incomplete if affected caller/platform/test/compat record/API dump/example/doc retains old contract.
- O3 Security/auth/crypto/protocol/routing/persistence/schema/wire change R versioned ADR `{context,decision,alternatives,risks,migration}`.
- O4 Docs D separate tutorial/how-to/reference/explanation; each R one dominant purpose.
- O5 Checkable examples R compile/execute.
- O6 Docs-only R repo link/spelling/format/markup checks.
- O7 Day-to-day conventions R narrow scoped docs; X Constitution duplication.

## E: dependencies/tooling

- E1 Build/release R reproducible; dependency/tool versions R ecosystem-standard pin/lock.
- E2 Dependencies/tools R mutually compatible stable releases; hold R blocker+removal condition.
- E3 New runtime dependency R rationale `{need,maintenance,license,security,size,transitives,why_stdlib/existing_fails}`.
- E4 Production artifact X undeclared/unreviewed runtime dependencies.
- E5 Platform/test/benchmark/docs/build deps X production artifact unless runtime contract requires.
- E6 Generated files R owning-tool regeneration; X hand-edit.
- E7 CI=authoritative automated gates; local hooks != CI proof.

## G: merge/release

Pre-merge, all applicable:

- G1 R feature branch; X protected-default direct commit.
- G2 R clean-checkout authoritative CI pass including S/Q/T/C/P/D/O/E+repo validators.
- G3 Config/workflows R format+schema validation.
- G4 Public change R docs+examples+compat artifacts+semver impact.
- G5 PR R Constitution Check S-Q-T-C-P-D-O-E-G; violation=>approved amendment, X silent exception.
- G6 Commits R Conventional Commits or stricter repo format.

## V: governance

- V1 Constitution outranks repo policy.
- V2 Change R written `{rationale,impact,migration,approval}`.
- V3 Document semver: removed/incompatible principle=>MAJOR; new/material expansion=>MINOR; obligation-neutral clarification=>PATCH.
- V4 Policy change R same-change automation/templates/hooks/checklists.
- V5 Ownership D designated review for Constitution/security/release/CI/protocol/schema/persistence paths.
- V6 Lower convention R narrowest relevant doc; X duplication here.

`version=3.0.1; ratified=2026-04-30; amended=2026-10-05`
Amendment: rationale=AI context compression; impact=wording only, IDs/obligations unchanged; migration=none; approval=user "optimize all the files for AI usage...without losing context".
