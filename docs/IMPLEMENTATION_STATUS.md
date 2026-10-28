# Implementation status

Status of RippleMap 1.0.0. "Verified" means an automated test or a recorded manual procedure checked the behavior; the
evidence is in [VERIFICATION_REPORT.md](VERIFICATION_REPORT.md) and [TESTING.md](TESTING.md).

## Features

| Area | Status | Evidence |
|---|---|---|
| ZIP import (Maven, Gradle, plain layouts; scope: production / with tests; build output excluded) | Implemented, verified | `ProjectApiIntegrationTest`, `JavaProjectAnalyzerEdgeCaseTest`, browser import test |
| Import security (Zip Slip, absolute and reserved names, links, duplicates, bombs, limits, cleanup) | Implemented, verified | `SafeZipExtractorTest` (31), `ProjectApiIntegrationTest` |
| Local-directory projects (allowed roots, rescans, links and junctions not followed) | Implemented, verified; **off by default** | `LocalDirectoryIntegrationTest` |
| Parsing and extraction: classes, interfaces, enums, records, annotations, nested types, methods, constructors, fields; 13 relationship kinds; stable IDs | Implemented, verified | golden fixtures A–F, `modern` and `samename` fixtures |
| Resolution: symbol solver over sources + JDK, documented inference rules, RESOLVED / INFERRED / UNRESOLVED, diagnostics | Implemented, verified | `syntaxFixtureSeparatesResolvedInferredAndUnresolved` and the edge-case tests |
| Graph engine: BFS reachability with explanation paths, shortest paths, Tarjan SCC, representative cycles | Implemented, verified | `GraphAlgorithmsTest`, 600 randomized invariant checks against an independent oracle |
| Impact score, thresholds (configurable, validated), exact classification, explanation paths | Implemented, verified | golden 68.5 / 76.0, boundary tests, API and browser tests |
| Snapshots: immutable, atomic writes, startup recovery, deletion, re-analysis of stored versions, history | Implemented, verified | `SnapshotConsistencyTest`, `ProjectApiIntegrationTest` |
| Persistence across restarts | Verified | `PersistenceRestartTest` (graceful), live check with process kill and restart (VERIFICATION_REPORT, gate 9) |
| Snapshot comparison (entities, dependencies, metrics, diagnostics; type and method layer) | Implemented, verified | fixture G, browser comparison test, live check with the shop sample |
| Reports: analysis HTML/JSON; components, dependencies, cycles, diagnostics and summary CSV; impact CSV/HTML/JSON; comparison CSV/HTML; complete even where the UI lists are capped | Implemented, verified | `ReportIntegrationTest`, `ReportCompletenessTest`, `CsvWriterTest`, browser report test (contents checked) |
| REST API with validation and uniform JSON errors; OpenAPI at `/api-docs`, Swagger UI at `/swagger-ui.html` | Implemented, verified | integration tests; Swagger UI rendered in Edge with 39 operations, no console errors |
| UI pages: Dashboard, Projects, Import, Overview, Dependency Explorer, Impact Analysis, Circular Dependencies, Analysis History, Snapshot Comparison, Reports, Settings & About, error page | Implemented, verified | 12 browser workflow tests; screenshots at 1440/768/390 px |
| Interactive graph (Cytoscape.js, bundled): search, focus, neighborhood, dependencies/dependents, depth, layouts, kind and relationship filters, inferred toggle, zoom, fit, reset, details panel | Implemented, verified | browser explorer test |
| Dark and light theme, responsive layout (desktop, tablet, phone) | Implemented, verified | visual test (overflow checks), screenshots inspected |
| Accessibility: landmarks, skip links, labels, keyboard-operable controls, focus management in dialogs, WCAG AA contrast | Implemented; automated audit passes | axe-core audit of all pages, popups and dialogs in both themes (0 violations); no screen-reader test |
| Local-only security: 127.0.0.1 binding, Host allowlist, CSRF header, Origin/Fetch-Metadata checks, CSP and security headers, limited Actuator, no H2 console | Implemented, verified | `ProjectApiIntegrationTest`, live probe of `/h2-console`, `/actuator/env` and `/actuator/beans` (all 404) |
| Offline operation: no CDN or external runtime requests | Verified | no external URL in templates or assets; CSP `default-src 'self'`; browser tests fail on any failed or unexpected request |
| Never executing imported code | By design; verified by review and tests | only `.java` files are extracted; nothing is compiled or loaded; the solver uses sources and the JDK only (`ReflectionTypeSolver(jreOnly=true)`) |

## Known limitations

### Analysis semantics (inherent to static source analysis)

* **Polymorphic dispatch.** A call through an interface is linked to the interface method only. Changing an
  implementation does not mark the interface's callers as affected on the method layer.
* **Invisible wiring.** Reflection, dependency injection, configuration files, generated code and annotation
  processing are not visible. Calls to Lombok-generated members, for example, add no method-level edge. Files that
  import Lombok, AutoValue, Immutables or MapStruct get a `GENERATED_CODE` diagnostic.
* **No library classpath.** Library sources and binaries are never loaded or downloaded, so references into libraries
  are external. Calls on values whose type comes from a library cannot always be resolved.
* **Java only.** Only `.java` files are analyzed. Kotlin, Groovy, Scala and other JVM languages in the same project
  are ignored, and their dependencies are missing.
* **Language level.** The default is Java 21 syntax (`ripplemap.analysis.language-level`). Newer syntax produces parse
  errors until the setting is raised.

### Performance and determinism

* Analysis time grows with hierarchy depth and generic complexity more than with file count. The generated
  1000-class project took 3.8–6.4 s. The javaparser-core sources (760 files, deep hierarchies) took 332 s, within the
  default 10-minute limit.
* A reference that exceeds the per-reference solver budget (2 s) falls back to the inference rules and is reported
  as `RESOLUTION_TIMEOUT`. On such codebases the timing, and therefore the classification of those few references,
  can differ between runs on a loaded machine. On the JavaParser sources, 78 of about 60 000 references were affected.
* Symbol resolution runs for one analysis at a time (JavaParser has process-wide state). A second analysis waits,
  while parsing and persistence still run in parallel.
* The explorer draws at most 2 000 nodes per request (default 300). Larger graphs must be narrowed by search, focus
  or filters. The impact and comparison pages list at most 5 000 and 500 entries. Their counts are exact and their
  reports list everything.
* At most 5 000 diagnostics are stored per snapshot (`ripplemap.analysis.max-diagnostics`). Counts per category
  stay exact, and the snapshot records that the list was truncated.

### Product scope

* Single user, local only: there is no authentication. Do not expose the server to a network
  (see [SECURITY.md](SECURITY.md)).
* The CSRF token changes at each start, so open pages must be reloaded after a restart.
* One application instance per data directory (H2 file database).
* Swagger UI is for reading the API. Its "try it out" mode is disabled, and calls that change state would need the
  CSRF header.

### Verification gaps

* Browser tests ran in Microsoft Edge (Chromium) only. Firefox and Safari were not tested.
* No screen-reader test was performed. Accessibility was checked with axe-core rules and keyboard-based workflows.
* All runs were on Windows 11. Linux and macOS were not executed. The POSIX `mvnw` script ran under Git Bash, and
  the Windows wrapper `mvnw.cmd` was also checked.
* The link-escape tests used Windows directory junctions, because creating symbolic links needs a privilege this
  account lacks. The symbolic-link branch of the test runs on systems where links can be created.

## Environment notes for this machine

* The machine's default JDK is Oracle 26.0.2. A portable Temurin 21.0.12 was downloaded to
  `%USERPROFILE%\.jdks\jdk-21.0.12.1+1` (no installer, no system changes) to verify the Java 21 target. The full
  build and tests passed on both JDKs.
* An antivirus product intercepts HTTPS. Maven therefore needs `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`
  and npm needs `NODE_OPTIONS=--use-system-ca` (see [TROUBLESHOOTING.md](TROUBLESHOOTING.md)).
* Application startup took 17–30 s here. Every startup phase was proportionally slow, which points to the environment
  (on-access scanning of the 70 MB jar) rather than to a single component.
