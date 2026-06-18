# Triage: Android fleet matrix run

## Expected behavior

For the first run, the Android fleet sweep should:

- enumerate the available devices before any pair starts so the run records the exact fleet in scope
- iterate through all available directed device pairs
- stop immediately on the first pair that fails
- write a separate artifact for each pair using a Mermaid sequence diagram
- include step timing, transport choice, and device quirks in the per-pair artifact
- finish with a summary of results, issues, and next steps

## Actual behavior

The current matrix runner:

- enumerates directed Android pairs in `meshlink-reference/scripts/run_headless_reference_android_direct_matrix.py`
- writes aggregate artifacts only: `progress.json`, `matrix-results.json`, `state.json`, and `matrix-report.md`
- stores per-pair run output under each pair's `*_initial` / `*_final` directories, but does not emit a dedicated Mermaid sequence-diagram file per pair
- does not fail fast; the loop continues after a failed pair because there is no stop-on-first-failure branch in the main loop

## Root cause

The matrix runner is missing the orchestration and artifact layer needed by the requested workflow.

Concretely:

1. `run_headless_reference_android_direct_matrix.py` only records aggregate JSON/report artifacts and the underlying pair summaries.
2. There is no function that renders a Mermaid sequence diagram from the pair run outcome or step timings.
3. The `for` loop in `main()` appends results and continues; it never exits early when a pair fails.
4. The underlying direct-proof runner already records summary/timing data, but the matrix runner does not reshape that data into the requested per-pair artifact.

## Reproduction

1. Inspect `meshlink-reference/scripts/run_headless_reference_android_direct_matrix.py`.
2. Note that the only writes in the main loop are to `progress.json`, `matrix-results.json`, `state.json`, and the final `matrix-report.md`.
3. Note that the loop at the end of `main()` has no `break` / `raise` on failure.
4. Run the existing matrix tests in `meshlink-reference/scripts/tests/test_reference_android_direct_matrix.py`; they cover resume and transport selection, but not fail-fast behavior or Mermaid artifact generation.

## Affected files / functions

- `meshlink-reference/scripts/run_headless_reference_android_direct_matrix.py`
  - `run_pair()`
  - `main()`
  - `render_compact_report()`
- likely `meshlink-reference/scripts/run_headless_reference_android_direct_proof.py` if we need richer step timing for the Mermaid diagram
- likely `meshlink-reference/scripts/tests/test_reference_android_direct_matrix.py` for regression coverage

## Proposed fix

- Add a fleet enumeration step before the matrix begins and persist the exact device list in the run artifacts.
- Add a per-pair artifact writer that renders a Mermaid sequence diagram from the pair's run data and stores it in that pair's run directory.
- Include transport selection, device quirks, and step timing in that artifact.
- Add fail-fast behavior to the matrix loop for the first run, with an explicit stop-on-failure path.
- Add tests proving:
  - the fleet enumeration artifact is written before any pair starts
  - a pair artifact is written for each completed pair
  - the Mermaid output includes timing and transport metadata
  - the matrix run stops after the first failure when fail-fast is enabled

## Follow-up triage: docs-path failures

The unrelated docs-path failures are not caused by the matrix runner change. They are cwd-sensitive test failures:

- `test_docs_index_links` passes when run from the repository root, but fails when the suite is launched from `meshlink-reference/scripts` because `Path("docs/README.md")` resolves relative to the wrong working directory.
- `test_reference_fleet` still fails under the scripts-directory invocation because it imports `support` as a top-level module and depends on the scripts directory being on `sys.path`.

That means the docs failures are harness/layout problems, not missing docs content in the repository.

## Notes

This is an orchestration/reporting gap rather than a transport-protocol failure. The pair runner is already capable of producing summary data; the missing piece is the matrix-level artifact and stop-on-failure behavior the workflow now requires.
