# Engineering decisions

Short records of decisions that shape behavior. Each lists the alternatives considered.

### D1 — Java 21 target, verified on JDK 21 and newer
The project compiles with `--release 21` (`java.version=21`) and the Maven enforcer requires JDK 21+. Builds and tests
were run on Temurin 21.0.12. The development machine's default JDK is 26; see `VERIFICATION_REPORT.md` for what was
run on which JDK.

### D2 — Spring Boot 3.5.16, JavaParser 3.28.2, H2 2.3, Flyway 11, springdoc 2.8.17, Cytoscape.js 3.34.4
Latest releases compatible with Spring Boot 3.x at the time of writing. Boot 4.x was excluded because the
requirement is Spring Boot 3.x. Versions are pinned (parent BOM plus explicit versions).

### D3 — Custom type solver over the parsed sources instead of `JavaParserTypeSolver`
`JavaParserTypeSolver` re-reads files by directory/package convention, so files whose directory does not match their
package cannot be resolved, and every file is parsed twice. `SourceIndexTypeSolver` answers from the already parsed
compilation units and refuses names declared by several files. `ReflectionTypeSolver(jreOnly=true)` provides JDK
types only, so RippleMap's own classpath (Spring, Hibernate, …) can never be mistaken for the analyzed project's
libraries.

### D4 — Name-based pre-classification before the solver
A reference whose name cannot denote any declaration in the analyzed sources can never become an edge, so it is
counted as external and skips the expensive solver. This is sound because a declaration can only be referenced by its
own name. "External" includes members generated at compile time (Lombok and similar), which are invisible to source
analysis. Files importing known generators get a `GENERATED_CODE` diagnostic instead of a silent miscount.

### D5 — Conservative inference, always labeled
When the solver fails (commonly because a library type is unavailable), deterministic rules may still identify a
unique target. Such edges are `INFERRED`, carry the rule id, are drawn dashed, and can be excluded from impact
analysis. Method targets are never inferred from the name alone (receiver type and arity must match).

### D6 — Per-reference solver time budget
The solver can take seconds or loop very deeply on some code. Because it cannot be interrupted, RippleMap's type
solver checks a budget on every type lookup the solver performs and unwinds the solver with an exception. The fallback
rules then apply and a `RESOLUTION_TIMEOUT` diagnostic is recorded. Trade-off: an over-budget reference can be
classified differently on a heavily loaded machine; it is always reported.

### D7 — One analysis in the symbol solver at a time
JavaParser keeps process-wide static state (facade registry, some inference caches). `SymbolSolverGate` serializes the
resolution phase and clears the registry afterwards. Parsing and persistence of other analyses still run concurrently.

### D8 — Type-level self-references are not edges
A type referring to itself is not a dependency between components and would flood the cycle report. Recursion is kept
on the method layer, where it is meaningful.

### D9 — Snapshot rows via JDBC batches, aggregates via JPA
Snapshots contain up to hundreds of thousands of immutable rows. Writing them with JDBC batches inside the same
transaction as the JPA status update keeps writes fast and atomic. Projects, versions, analyses and settings use JPA.

### D10 — Store extracted Java sources, not the uploaded archive
Only `.java` files are needed for re-analysis; storing them reduces disk use and never keeps executable content.
Re-analysis of a ZIP project always uses a stored version; changed code requires uploading a new version.

### D11 — Local-directory scanning off by default
Scanning arbitrary server paths from a browser request is dangerous. It is enabled only for configured allowed
roots, checked against real paths (links resolved), and discovery never follows links.

### D12 — Custom CSRF header instead of Spring Security
The application has no users or sessions. A per-process random token embedded in pages and required as a custom
header on every state-changing request, plus Host, Origin and Fetch-Metadata checks, gives strong protection against
cross-site and DNS-rebinding attacks without the complexity of Spring Security.

### D13 — Per-type metrics precomputed, impact computed on demand
Stored transitive-dependent counts and scores let the dashboard rank types instantly. The impact page always
recomputes with the current options and thresholds, so it is never stale.

### D14 — Vanilla JavaScript, no build step
The UI consists of small page scripts and one shared core, with no framework or bundler. This keeps the application a
single deployable jar, works offline, and makes the strict Content-Security-Policy (`script-src 'self'`) easy to keep.
