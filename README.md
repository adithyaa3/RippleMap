# RippleMap

RippleMap is a local web application that analyzes the source code of a Java project and answers one question:
**if I change this class or method, what else could be affected?**

It parses the project's `.java` files (it never compiles or runs them), builds dependency graphs at type and method
level, and shows them in an interactive explorer. It also computes an explainable impact score, finds circular
dependencies, keeps snapshots of every analysis, and compares versions. Reports can be exported as HTML, CSV or JSON.

Everything runs on your machine: one Spring Boot jar, an embedded H2 database, and a browser UI with no external
resources. It works offline.

## Features

* **Import** a project as a ZIP (Maven, Gradle or plain layouts), or register a local directory if enabled. Uploads
  are checked against Zip Slip, ZIP bombs, links, duplicate entries and size limits.
* **Analysis** with JavaParser and its symbol solver, against the project's sources and the JDK. It records 13
  relationship kinds (inheritance, fields, signatures, object creation, calls, field access, annotations, …). Each
  reference is classified as `RESOLVED`, `INFERRED` (documented fallback rules) or `UNRESOLVED`. Unresolved
  references become diagnostics, never guessed edges.
* **Dependency explorer**: search, focus, neighborhoods, dependencies and dependents, filters, layouts, path
  finding, and a details panel with the source location and the evidence for each edge.
* **Impact analysis**: direct and transitive dependents, explanation paths, and a transparent score
  `100 × (0.45·D/(N−1) + 0.40·T/(N−1) + 0.15·min(H,5)/5)`. Thresholds are configurable (defaults: LOW below 30,
  MEDIUM from 30, HIGH from 60).
* **Circular dependencies**: strongly connected components (Tarjan), each with a representative cycle.
* **History and comparison**: every analysis is an immutable snapshot. Compare any two snapshots by stable
  identifiers to see added and removed components and dependencies, and impact changes.
* **Reports**: analysis summary (HTML/JSON), components, dependencies, cycles, diagnostics, impact and comparison
  (CSV/HTML). CSV cells are protected against formula injection, and all HTML output is escaped.
* **Local-only security**: binds to `127.0.0.1`, uses a Host allowlist, a CSRF token header, Origin and
  Fetch-Metadata checks, and a strict Content-Security-Policy. The H2 console is disabled and Actuator exposes only
  `health` and `info`.

## Requirements

* **JDK 21 or newer** on the `PATH` or in `JAVA_HOME`. Builds and tests were verified on Temurin 21.0.12 and Oracle
  JDK 26.0.2.
* Nothing else. The Maven Wrapper downloads Maven 3.9.16, and the UI's JavaScript library (Cytoscape.js) is bundled.
  The browser tests are optional and need Node.js 20+ (see below).

## Quick start

**Windows (PowerShell)**

```powershell
.\mvnw.cmd spring-boot:run
```

**macOS / Linux**

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>.

To build and run the jar:

```bash
./mvnw package                      # builds and runs the test suite (2–3 minutes)
./mvnw package -DskipTests          # build only
java -jar target/ripplemap-1.0.0.jar
```

Data (the H2 database and imported sources) is stored in `~/.ripplemap` (`%USERPROFILE%\.ripplemap` on Windows).
Options:

| Setting | Command-line | Environment |
|---|---|---|
| data directory | `--ripplemap.data-dir=/path` | `RIPPLEMAP_DATA_DIR` |
| port (default 8080) | `--server.port=9090` | `RIPPLEMAP_PORT` |
| allow scanning local directories | `--ripplemap.local-scan.allowed-roots=/home/me/code` | |

All limits (upload size, analysis time, file counts, …) are in
[`src/main/resources/application.yml`](src/main/resources/application.yml) under `ripplemap.*`, and the Settings
page lists them.

> Behind an antivirus or proxy that intercepts HTTPS, Maven may fail with `PKIX path building failed`. See
> [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Try it with the sample project

[`sample-projects/`](sample-projects/README.md) contains two versions of a small order-management service.

1. Choose **Import Project** and upload `sample-projects/shop-v1.zip`.
2. Open the **Dependency Explorer**, then **Circular Dependencies**, which shows `InventoryService ↔ ReservationService`.
3. On the project overview, choose **Upload new version** and upload `shop-v2.zip`.
4. Open **Snapshot Comparison** to see what changed between the two versions.

## Tests

```bash
./mvnw verify                       # unit + integration tests, JaCoCo report in target/site/jacoco
./mvnw test -Pperformance           # timing runs on generated projects of 10/100/500/1000 classes
```

Browser end-to-end tests (Playwright) start the packaged jar on port 18181 with a temporary data directory:

```bash
./mvnw package -DskipTests
cd e2e
npm install
npx playwright install chromium     # or use an installed browser: RIPPLEMAP_E2E_CHANNEL=msedge (or chrome)
npx playwright test                 # workflows, screenshots of every page at 1440/768/390 px, accessibility audit
```

See [docs/TESTING.md](docs/TESTING.md) for the test inventory, and
[docs/VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md) for the results observed for this release.

## Documentation

| Document | Contents |
|---|---|
| [USER_GUIDE](docs/USER_GUIDE.md) | Using every page |
| [ANALYSIS_SEMANTICS](docs/ANALYSIS_SEMANTICS.md) | What an edge means, relationship kinds, identifiers, resolution rules, score formula |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | Packages, analysis pipeline, snapshot lifecycle, persistence |
| [API](docs/API.md) | REST endpoints (also at `/swagger-ui.html` while running) |
| [SECURITY](docs/SECURITY.md) | Threat model and protections |
| [DECISIONS](docs/DECISIONS.md) | Engineering decisions and trade-offs |
| [TESTING](docs/TESTING.md) | Test suites, fixtures, how to run them, performance measurements |
| [TROUBLESHOOTING](docs/TROUBLESHOOTING.md) | Common problems |
| [IMPLEMENTATION_STATUS](docs/IMPLEMENTATION_STATUS.md) | What is implemented, verified and limited |
| [VERIFICATION_REPORT](docs/VERIFICATION_REPORT.md) | Quality gates with commands and observed results |

## Important limitations

RippleMap performs **static** analysis of source code. Its results describe structural dependencies. They are not
proof that a change will or will not break something. In particular:

* Reflection, dependency injection, configuration files, code generated at compile time (for example by Lombok)
  and other non-Java resources are invisible to it.
* Calls through interfaces are attributed to the declared method, not to every implementation.
* Libraries are not on the analysis classpath, so references into them are classified as external.
* Very large codebases with deep class hierarchies can take minutes to analyze.

[IMPLEMENTATION_STATUS](docs/IMPLEMENTATION_STATUS.md) has the full list.

## Third-party components

RippleMap bundles these third-party components (versions in `pom.xml`; each dependency's POM has its license):

* Apache 2.0: Spring Boot 3.5, Flyway 11 and springdoc-openapi 2.8.
* LGPL 2.1: Hibernate ORM 6.6.
* MPL 2.0 / EPL 1.0: H2 2.3.
* Apache 2.0 or LGPL 3.0: JavaParser 3.28.
* MIT: Cytoscape.js ([license](src/main/resources/static/vendor/cytoscape/LICENSE.txt)).
* ISC: Lucide icons ([license](src/main/resources/static/assets/icons-LICENSE.txt)).
