# Verification report

These are the quality gates for RippleMap 1.0.0, with the commands that were actually run and the results observed.
All runs took place on 22 Oct 2026, on Windows 11 Home (10.0.26200) with an Intel Core i9-13900H and 15.6 GB RAM.
JDK paths are abbreviated: "JDK 21" is Temurin 21.0.12 at `%USERPROFILE%\.jdks\jdk-21.0.12.1+1`, and "JDK 26" is the
machine default, Oracle 26.0.2. Maven ran with `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` because of
HTTPS interception on this machine.

Status values: **PASS** means the gate was verified and met. **PASS (limited)** means it was verified with the stated
limitation. **NOT VERIFIED** means it was not run.

---

### Gate 1 — Environment

| | |
|---|---|
| Objective | Java 21, Maven Wrapper, dependency resolution, workspace integrity |
| Procedure | `java -version` and `mvnw -v` (Git Bash) and `.\mvnw.cmd -v` (PowerShell) with each JDK; full builds on both JDKs; check of the workspace contents |
| Expected | the wrapper downloads and runs Maven 3.9.16; dependencies resolve; the enforcer accepts JDK ≥ 21 |
| Observed | Maven 3.9.16 runs from both wrappers with JDK 21.0.12.1 and JDK 26.0.2; all dependencies resolved. The workspace is not a git repository. The pre-existing `.vscode/settings.json` was preserved unchanged. Node 24.14 and Playwright 1.64.1 drive an installed Microsoft Edge |
| Status | **PASS** |
| Limitation | JDK 21 was not installed on the machine. A portable JDK was unpacked into the user profile; no system change was needed |

### Gate 2 — Compilation

| | |
|---|---|
| Objective | clean compilation without errors |
| Command | `./mvnw -B -ntp clean verify` (JDK 21), and the same with JDK 26 |
| Expected | `BUILD SUCCESS`; classes target Java 21 |
| Observed | `BUILD SUCCESS` on both JDKs. `javap` shows class version 65 (Java 21) for the JDK 26 build. No compiler warnings appear in the build log |
| Status | **PASS** |
| Limitation | none |

### Gate 3 — Unit tests

| | |
|---|---|
| Objective | all unit tests pass |
| Command | part of `./mvnw clean verify` |
| Expected | 0 failures, 0 errors, 0 skipped |
| Observed | 705 unit tests passed: analyzer 33, graph 19 + 600 randomized, impact 17, ZIP import 31, CSV/HTML writer 5 |
| Status | **PASS** |
| Limitation | none |

### Gate 4 — Integration tests

| | |
|---|---|
| Objective | backend integration tests (Spring context, MockMvc, H2 file database) pass |
| Command | part of `./mvnw clean verify` |
| Expected | 0 failures |
| Observed | 36 integration tests passed: projects 14, graph 7, local directory 5, restart 1, snapshot consistency 4, reports 3, report completeness 2. **Total: 741 tests, 0 failures, 0 errors, 0 skipped**, in 2 min 20 s on JDK 21 and 1 min 58 s on JDK 26 (final code) |
| Status | **PASS** |
| Limitation | One intermediate run failed with `NoClassDefFoundError: AnalysisRepository`: the IDE's background compiler had written into `target/classes` during an edit. `clean` fixed it; the final runs used `clean` |

JaCoCo coverage (final JDK 21 run, `target/site/jacoco`): lines **85.7 %**, branches **66.5 %**, instructions 87.1 %,
methods 91.7 %. Core packages:

| Package | Lines | Branches |
|---|---|---|
| graph.algorithms | 98.5 % | 94.1 % |
| impact | 97.8 % | 88.9 % |
| report | 97.6 % | 80.0 % |
| analysis (pipeline) | 92.2 % | 86.8 % |
| importing | 87.4 % | 82.4 % |
| analysis.extraction | 72.9 % | 56.4 % |
| analysis.resolution | 70.0 % | 51.4 % |

Most of the uncovered branches in extraction and resolution are defensive fallbacks for solver failures.

### Gate 5 — Application startup

| | |
|---|---|
| Objective | the real application starts and answers on localhost |
| Procedure | `java -jar target/ripplemap-1.0.0.jar` with a temporary data directory, on JDK 21 and on JDK 26; `./mvnw spring-boot:run` (JDK 21); then `GET /actuator/health` |
| Expected | `{"status":"UP"}` on 127.0.0.1 |
| Observed | `{"status":"UP"}` in all three cases. Startup took 17.0 s (JDK 26), 27.7 s (JDK 21) and 24.2 s (`spring-boot:run`). `/`, `/swagger-ui/index.html` and `/api-docs` returned 200. `/h2-console`, `/actuator/env` and `/actuator/beans` returned 404 |
| Status | **PASS** |
| Limitation | Startup is slow on this machine. Every startup phase was proportionally slow, which points to on-access antivirus scanning rather than to a single component |

### Gate 6 — Real project import

| | |
|---|---|
| Objective | import a Java project through the real workflow and check the extraction |
| Procedure | (a) Python script (`live_workflow.py`, kept outside the repository) posting `sample-projects/shop-v1.zip` as multipart with the CSRF token to the running jar. (b) Browser test "import a project through the UI" |
| Expected | the analysis completes; file and type counts match the archive |
| Observed | (a) `COMPLETED`: 25 files parsed (the test file excluded by scope), 25 types, 40 methods, 10 constructors, 48 type dependencies. The source tree has 25 production `.java` files and 1 test file. (b) passed |
| Status | **PASS** |
| Limitation | none |

### Gate 7 — Graph correctness

| | |
|---|---|
| Objective | nodes and edges match independently derived expectations |
| Procedure | golden fixtures A–F with hand-written expected edge sets (`JavaProjectAnalyzerGoldenTest`); 600 randomized invariant checks against a Floyd–Warshall oracle (`GraphInvariantRandomizedTest`, seeds 1–120) |
| Expected | exact edge sets in "A depends on B" direction, no reversed edges, overloads distinguished, unresolved references kept separate |
| Observed | all passed |
| Status | **PASS** |
| Limitation | static-analysis limits as documented (polymorphic dispatch, reflection, generated code) |

### Gate 8 — Impact correctness

| | |
|---|---|
| Objective | reverse traversal, affected sets, explanation paths, scores and classification |
| Procedure | `ImpactCalculatorTest`, `GraphApiIntegrationTest.impactAnalysisMatchesGoldenValues`, the browser impact test, and hand checks of the live sample results |
| Expected | chain 68.5 HIGH (D=1, T=2, H=2); diamond 76.0 with `A` counted once; isolated 0; exact boundary classification |
| Observed | all passed. Live sample, v2 (N=26): `Order` D=8, T=8, H=1, score 30.2 = (45·8+40·8)/25+3·1; `Money` D=10, T=19, H=2, score 54.4 = (45·10+40·19)/25+3·2. Both MEDIUM, as computed by hand |
| Status | **PASS** |
| Limitation | the score is a structural heuristic (stated in the UI and reports) |

### Gate 9 — Persistence

| | |
|---|---|
| Objective | results survive a restart |
| Procedure | (a) `PersistenceRestartTest`: two real application contexts on the same data directory. (b) The live script imported v1 and v2, captured project, snapshots, the full graph and an impact result, **killed the process** (`TerminateProcess`), restarted the jar on the same data directory, and compared. (c) A browser probe after the restart opened the overview, impact and cycles pages |
| Expected | identical data after the restart; the UI shows the stored results |
| Observed | (a) passed. (b) project, snapshots, graph (25 nodes, 48 edges) and impact were equal (the parsed JSON responses were compared). (c) the snapshot selector listed #1 and #2, the overview showed 26 files and 51 type dependencies, impact showed 30.2, cycles showed "1 component(s) · 2 types involved", with no console errors |
| Status | **PASS** |
| Limitation | none |

### Gate 10 — History and comparison

| | |
|---|---|
| Objective | versioned analyses and exact changes |
| Procedure | fixture G (`comparisonOfTwoVersionsMatchesTheExpectedChanges`); the browser history and comparison test; the live comparison of shop v1 and v2; `ReportCompletenessTest` |
| Expected | exactly the added and removed entities and dependencies; snapshots immutable |
| Observed | all passed. Live: added `NoDiscount` and `ShippingService`, removed `SeasonalDiscount`, 7 added and 4 removed dependencies (including the edge that closed the cycle), cycles 1 → 0, consistent with the generator source. Immutability is covered by `finishedSnapshotsCannotBeModified` |
| Status | **PASS** |
| Limitation | none |

### Gate 11 — Reports

| | |
|---|---|
| Objective | download real reports and inspect their contents |
| Procedure | `ReportIntegrationTest`, `ReportCompletenessTest`, `CsvWriterTest`; the browser report test (reads the downloaded files); live downloads of 6 reports |
| Expected | 200, attachment headers, content for the selected snapshot, CSV injection neutralized, HTML escaped, complete lists |
| Observed | all passed. Live: 6 × 200 with `Content-Disposition: attachment; filename*=UTF-8''ripplemap-shop-live-check-s1-…`; `dependencies.csv` has 142 data rows and the documented header |
| Status | **PASS** |
| Limitation | none. A defect found during this gate was fixed: the impact and comparison reports had inherited the UI list caps, although the UI promised complete reports |

### Gate 12 — Security

| | |
|---|---|
| Objective | unsafe imports are rejected; imported code is not executed; the local-only protections work |
| Procedure | `SafeZipExtractorTest` (31); `ProjectApiIntegrationTest` (malicious archives, upload limit, CSRF, Origin, Host, headers); `LocalDirectoryIntegrationTest` (allowed roots, links, junctions); code review of the extraction and resolution paths |
| Expected | every attack rejected with a clear error and nothing left on disk; only `.java` files are written; nothing is compiled or loaded |
| Observed | all passed. Review: the extractor writes only `.java` entries with `CREATE_NEW`; the analyzer only parses; the type solver uses the parsed sources and `ReflectionTypeSolver(jreOnly=true)` |
| Status | **PASS (limited)** |
| Limitation | non-execution is a design property checked by review and by what is written to disk; there is no runtime sandbox test. The link tests used Windows junctions (no symbolic-link privilege) |

### Gate 13 — Browser behavior

| | |
|---|---|
| Objective | key user workflows in a real browser |
| Command | `cd e2e && RIPPLEMAP_E2E_CHANNEL=msedge npx playwright test` |
| Expected | all workflow tests pass; no unexpected console errors, failed requests or HTTP errors |
| Observed | 14 passed (12 workflows, 1 visual, 1 accessibility) in 4.7 min against the final jar |
| Status | **PASS (limited)** |
| Limitation | only Microsoft Edge (Chromium) was tested; Playwright's own browsers were not downloaded |

### Gate 14 — UI visual quality

| | |
|---|---|
| Objective | inspect desktop and smaller-screen views and fix material defects |
| Procedure | `visual.spec.mjs` captured 13 pages at 1440, 768 and 390 px plus three light-theme pages, with automatic overflow checks; the screenshots were inspected manually. `accessibility.spec.mjs` ran axe-core WCAG 2.1 A/AA on all pages, popups and dialogs in both themes |
| Expected | no horizontal overflow, readable layouts, 0 axe violations |
| Observed | no overflow. Defects found and fixed during this phase: the score-explanation table on phones had misaligned columns (header hidden, cells not); card headers did not wrap; the formula overflowed; the affected-components table was too wide on phones; cycle member names were truncated before file names; color contrast was insufficient on all 13 pages in both themes (faint text, primary and danger buttons, status badges); the cycle list had an invalid listbox structure; the hidden file input had no label; scrollable tables could not be focused. After the fixes, all 34 axe audits report 0 violations |
| Status | **PASS (limited)** |
| Limitation | automated rules cover only part of accessibility; no screen-reader test |

### Gate 15 — Runtime offline readiness

| | |
|---|---|
| Objective | no runtime CDN dependencies |
| Procedure | searched templates, CSS and JS for external URLs; checked the CSP; the browser tests fail on any failed request |
| Expected | all assets served locally |
| Observed | the only URLs are XML namespace declarations (`xmlns:th`) and license comments in `cytoscape.min.js`, which are never fetched. No `@import` or `url()` in CSS. Cytoscape.js, icons and fonts (system stack) are local. The CSP `default-src 'self'` would block any external load, and that would fail the browser tests |
| Status | **PASS (limited)** |
| Limitation | the network was not physically disconnected during a test run |

### Gate 16 — Final regression

| | |
|---|---|
| Objective | the full build and all automated tests after the last change |
| Commands | `./mvnw -B -ntp clean verify` on JDK 21, then on JDK 26; `./mvnw clean package -DskipTests` (JDK 21) and `npx playwright test` |
| Observed | JDK 21: 741 tests, 0 failures, `BUILD SUCCESS`. JDK 26: 741 tests, 0 failures. Browser: 14 passed. The last code change (cycle member layout, CSS and JS only) came after the JDK runs; it touches no Java code, and the browser suite ran again afterwards. Performance profile on the final code: 4/4 passed (numbers in TESTING.md) |
| Status | **PASS** |
| Limitation | none |

### Gate 17 — Documentation consistency

| | |
|---|---|
| Objective | README commands, routes, descriptions and feature claims match the implementation |
| Procedure | ran the README commands (`./mvnw verify`, `package -DskipTests`, `java -jar`, `spring-boot:run`, `mvnw.cmd`, the Playwright commands, `-Pperformance`); compared every method and path in `API.md` with the live `/api-docs` (39 operations); checked sample-project numbers against a live run; checked the documented limits against `application.yml` |
| Expected | no command, route or number in the docs is wrong |
| Observed | all documented endpoints exist (only `/actuator/health` is outside the OpenAPI document, by design). Corrections made during the check: wrong claim of "deliberate unresolved library references" in the user guide; "external with certainty" in the semantics and decisions docs (now explains generated code); Node.js version (20+); test counts and fixture sizes in TESTING.md; the README third-party license list (Hibernate is LGPL 2.1); the SECURITY.md CSP description (Swagger UI exception) |
| Status | **PASS (limited)** |
| Limitation | `npx playwright install chromium` (the README alternative to an installed browser) was not executed. Linux and macOS commands were not run |
