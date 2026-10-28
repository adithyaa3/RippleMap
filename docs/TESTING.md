# Testing

RippleMap has three automated test layers plus a performance harness:

| Layer | Tool | Command | Tests |
|---|---|---|---|
| Unit and integration | JUnit 5, Spring Boot Test, MockMvc, Mockito | `./mvnw verify` | 741 |
| Browser end-to-end, visual, accessibility | Playwright + axe-core | `cd e2e && npx playwright test` | 14 |
| Performance | JUnit (`@Tag("performance")`) | `./mvnw test -Pperformance` | 4 generated sizes + 1 optional real project |

`./mvnw verify` excludes the performance tag and writes a JaCoCo coverage report to `target/site/jacoco/index.html`.
Results observed for this release are in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md).

## Golden fixtures and independent oracles

The fixtures are small Java projects in `src/test/resources/fixtures`. Their expected nodes, edges, scores and
diagnostics are written as literals in the tests. They were derived by hand from the fixture sources, never by
running the code under test.

| Spec fixture | Directory | Expected result (hand-derived) | Verified by |
|---|---|---|---|
| A, dependency chain | `chain` | `Controller → Service → Repository`. Changing `Repository`: D=1, T=2, H=2, score **68.5**, HIGH. Paths `Service → Repository` and `Controller → Service → Repository` | `JavaProjectAnalyzerGoldenTest` (`chain*`), `ImpactCalculatorTest.goldenChainScoreIs68Point5`, `GraphApiIntegrationTest.impactAnalysisMatchesGoldenValues`, browser test "impact analysis computes the golden score" |
| B, diamond | `diamond` | `A→B, A→C, B→D, C→D`. Changing `D`: D=2, T=3 (`A` counted once), H=2, score **76.0** | `diamondFixtureHasFourEdges`, `goldenDiamondScoreIs76AndCountsSharedDependentOnce`, `GraphApiIntegrationTest` |
| C, cycle | `cycle` | one cyclic component {A, B, C} and the representative cycle `A → B → C → A` | `cycleFixtureHasThreeEdgeCycle`, `cyclesEndpointReportsComponentsWithValidRepresentativeCycle`, browser cycles test |
| D, isolated class | `isolated` | the isolated type has 0 dependents and score 0 | `isolatedFixtureHasNoEdgesForIsolatedClass`, `isolatedNodeHasZeroImpact` |
| E, Java syntax | `syntax` | interface, implementation, inheritance, nested class, overloads, constructor, generics, calls, an external reference and an ambiguous call. The exact type- and method-level edge sets, and resolved, inferred and unresolved references kept separate | `syntaxFixture*` (4 tests) |
| F, malformed source | `malformed` | valid files are analyzed; the broken file yields `PARSE_ERROR` and no relationships; the snapshot is `PARTIAL` | `malformedFileIsReportedAndDoesNotProduceRelationships`, `partialSnapshotsExposeDiagnosticsAndFiles` |
| G, snapshot differences | `versions/v1`, `versions/v2` | exact added and removed types and dependencies between the two versions | `comparisonOfTwoVersionsMatchesTheExpectedChanges` |

Additional fixtures:

* `modern`: enums, records, sealed types, annotations and static imports.
* `samename`: equal simple names in different packages, plus test sources for the scan-scope tests.

## Test inventory (`src/test/java`)

| Class | Tests | What it covers |
|---|---|---|
| `analysis.JavaProjectAnalyzerGoldenTest` | 16 | fixtures A–F: exact edges and their direction, method overloads, evidence (file and line), stable IDs, the separation of resolved, inferred and unresolved references, test-source scope, determinism (identical input gives identical output) |
| `analysis.JavaProjectAnalyzerEdgeCaseTest` | 17 | empty files, several top-level types in one file, BOM and invalid UTF-8, generated build output, size and count limits, cancellation and deadline, solver-budget fallback, local, anonymous and lambda code, inherited calls, duplicate type names, package/directory mismatch, default package, varargs, recursion, on-demand imports, code generators (Lombok) |
| `graph.GraphAlgorithmsTest` | 19 | empty and single-node graphs, edge direction, chains, diamonds, self-loops, cycles, several cyclic regions, representative cycles, duplicate and dangling edges, hubs, shortest paths, depth limits, insertion-order independence, induced subgraphs, 200 000-node chain closed into a cycle without stack overflow |
| `graph.GraphInvariantRandomizedTest` | 600 | 5 invariants × 120 recorded seeds (1–120), on random graphs of 1–30 nodes. The oracle is a Floyd–Warshall all-pairs matrix computed from the generated edge list. Invariants: reachability in both directions, explanation paths made of real edges, SCC = mutual reachability, valid representative cycles, impact metrics and score |
| `impact.ImpactCalculatorTest` | 17 | golden scores, boundaries with exact arithmetic, custom and invalid thresholds, depth cap, cycles, disconnected graphs, half-up display rounding |
| `importing.SafeZipExtractorTest` | 31 | Zip Slip (`../`, inner `..`), absolute paths, drive letters and UNC paths, reserved Windows names, alternate data streams, control characters, duplicate entries, symlink entries, size checks that do not trust headers, compression-ratio bombs, entry, depth and length limits, file/directory conflicts (removal of rejected uploads is checked in `ProjectApiIntegrationTest`) |
| `report.CsvWriterTest` | 5 | RFC 4180 quoting, formula-injection neutralization, UTF-8, HTML escaping |
| `api.ProjectApiIntegrationTest` | 14 | real import and analysis over HTTP, validation, malicious archives, upload limits, CSRF, Origin and Host checks, security headers (including the Swagger UI policy), error format, edit, delete, re-analysis, concurrent-analysis refusal, snapshot deletion |
| `api.GraphApiIntegrationTest` | 7 | graph and directional queries, golden impact through the API, cycles, version comparison, isolation between projects, partial snapshots, dashboard and settings |
| `api.LocalDirectoryIntegrationTest` | 5 | allowed-root enforcement, rescans after edits, links and junctions that point outside, case-insensitive enum parameters |
| `api.PersistenceRestartTest` | 1 | two real application contexts on the same data directory: projects, snapshots, graphs and impact are identical after the restart |
| `api.SnapshotConsistencyTest` | 4 | injected write failure leaves no partial snapshot, missing stored sources, recovery of interrupted analyses at startup, immutability of finished snapshots |
| `api.ReportIntegrationTest` | 3 | every report for the selected snapshot, impact reports equal the impact endpoint, escaping and CSV neutralization of untrusted names |
| `api.ReportCompletenessTest` | 2 | with list caps set to 1, the impact and comparison endpoints truncate their lists (counts stay exact), while the CSV, HTML and JSON reports list every entry |

Regression tests that guard a specific defect were run once with the fix removed, to show that they fail without it:

* `linksInsideAnAllowedProjectAreNotFollowed` (Windows junctions): without the fix, `expected: 3 but was: 7`.
* `filesUsingCodeGeneratorsAreReportedBecauseGeneratedMembersAreInvisible`: without the fix, `expected: 1L but was: 0L`.

## Browser tests (`e2e/`)

Requirements: Node.js 20+ (verified with 24.14), plus either `npx playwright install chromium` or an installed Edge or Chrome
(`RIPPLEMAP_E2E_CHANNEL=msedge` or `chrome`). The suite starts `target/ripplemap-1.0.0.jar` itself on port 18181
(`RIPPLEMAP_E2E_PORT`) with a new temporary data directory. Java is taken from `RIPPLEMAP_JAVA`, else `JAVA_HOME`,
else the `PATH`.

```bash
./mvnw package -DskipTests
cd e2e && npm install
RIPPLEMAP_E2E_CHANNEL=msedge npx playwright test        # everything
npx playwright test --grep @screenshots                  # visual pass only
npx playwright test --grep @a11y                         # accessibility audit only
```

| Spec | Tests | Covers |
|---|---|---|
| `workflows.spec.mjs` | 12 | navigation and empty states; import validation and invalid archives; importing through the UI; explorer search, selection, details, filters and graph controls; golden impact score and protection against stale results; cycles; new version, history and comparison; report downloads (content checked); settings validation, save and theme; recovery from API errors; mobile drawer and overflow; delete confirmation by typing the name. Every test also fails on any console error, page error, failed request or HTTP status ≥ 400 that it did not expect |
| `visual.spec.mjs` | 1 | imports `sample-projects/shop-v1.zip` and `shop-v2.zip`, then captures all 13 pages at 1440, 768 and 390 px wide plus three light-theme pages to `e2e/screenshots/`. Fails on horizontal overflow and console errors |
| `accessibility.spec.mjs` | 1 | axe-core (`@axe-core/playwright` 4.13.0) WCAG 2.0/2.1 A and AA rules on all 13 pages, the open search popups, and the edit and delete dialogs, in dark and light themes (34 audits). Requires zero violations |

Automated accessibility rules catch only part of all problems. Keyboard operation is exercised by the workflow tests.
The application was not tested with a screen reader.

## Performance

`AnalysisPerformanceTest` generates projects of 10, 100, 500 and 1000 classes. Each class has two fields of other
classes, calls methods on them and creates objects, and about one class in 25 forms a two-type cycle. The test
prints `PERF` lines with the times for analysis, metrics, impact and cycle detection. A real source tree can be
measured with `-Dripplemap.perf.dir=/path/to/sources` (results go to
`target/surefire-reports/com.ripplemap.performance.AnalysisPerformanceTest-output.txt`).

Measured on an Intel Core i9-13900H (20 threads), 15.6 GB RAM, Windows 11, Temurin 21.0.12, with antivirus active:

Ranges cover two runs of the generated projects. The first project of a run also pays for JVM warm-up.

| Input | Files | Types | Type deps | Method deps | Cycles | Analysis | Impact (avg of up to 100) | Cycle detection |
|---|---|---|---|---|---|---|---|---|
| generated-10 | 10 | 10 | 14 | 14 | 0 | 1.0–2.5 s | 0.5–1.7 ms | < 1 ms |
| generated-100 | 100 | 100 | 192 | 192 | 2 | 1.0 s | 0.17–0.23 ms | ≤ 1 ms |
| generated-500 | 500 | 500 | 990 | 990 | 19 | 2.6–3.8 s | 0.19–0.23 ms | 1 ms |
| generated-1000 | 1000 | 1000 | 1989 | 1989 | 39 | 3.8–6.4 s | 0.21–0.27 ms | 4–13 ms |
| javaparser-core 3.28.2 sources (one run) | 760 | 839 | 9 850 | 23 155 | 9 | 332 s | 1.20 ms | 1 ms |

The generated projects resolve every reference (no unresolved, no budget overruns). The JavaParser sources are
large, with deep class hierarchies and heavy generics, and are the worst case seen during development:

* 59 913 references resolved, 247 inferred, 195 unresolved.
* 997 solver failures.
* 78 references exceeded the 2 s per-reference budget.

Almost all of the time is spent in the symbol solver. Earlier runs of the same input took 210–283 s. With budget
overruns, timing and the classification of the over-budget references depend on machine load (see `DECISIONS.md`
D6).
