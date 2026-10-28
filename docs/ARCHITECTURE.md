# Architecture

RippleMap is a single Spring Boot 3.5 application (Java 21) that serves both the REST API and the browser UI.
Everything runs locally: an embedded file-based H2 database, server-rendered Thymeleaf page shells, vanilla
JavaScript and a locally bundled Cytoscape.js. There is no separate frontend server and no runtime CDN.

```
Browser ──HTTP──▶ LocalRequestGuardFilter (Host check, CSRF, security headers)
                    │
        ┌───────────┴──────────────┐
   PageController              REST controllers (/api/**)
   (Thymeleaf shells)                │ DTOs only
                                     ▼
                              Services (transactions, orchestration)
               ┌──────────────┬──────┴─────────┬──────────────┬──────────────┐
          ImportService  AnalysisService  GraphQueryService ImpactService  ReportService …
               │              │ worker pool        │                │
       SafeZipExtractor  AnalysisRunner ──▶ JavaProjectAnalyzer (pure Java, no Spring)
               │              │                    │ JavaParser + symbol solver
               ▼              ▼                    ▼
          data/imports   SnapshotWriter ──▶ SnapshotStore (JDBC batches) ─┐
                              │                                          ▼
                         JPA repositories ────────────────────────▶  H2 (file)
```

## Packages

| Package | Responsibility | Depends on |
|---|---|---|
| `analysis` | discovery, parsing, declaration index, resolution, relationship extraction | JavaParser only |
| `analysis.model`, `analysis.diagnostics` | immutable analysis results and diagnostics | — |
| `graph` | immutable dependency graph, BFS traversal, shortest paths, Tarjan SCC, cycles, summaries | `analysis.model` enums |
| `impact` | impact score, classification, per-node metrics | `graph` |
| `importing` | safe ZIP extraction, local-directory authorization | — |
| `entity`, `repository` | JPA aggregates (project, version, analysis, settings) and JDBC snapshot rows | Spring Data |
| `service` | use cases, transactions, snapshot cache, query services | all of the above |
| `report` | CSV / HTML / JSON report generation | `service` |
| `controller`, `dto` | HTTP mapping and API contracts | `service` |
| `security`, `exception`, `config` | request guard, error handling, configuration | Spring |

The analysis engine, graph algorithms and impact calculation have no Spring or persistence dependencies and are
tested directly (`JavaProjectAnalyzerGoldenTest`, `GraphAlgorithmsTest`, `ImpactCalculatorTest`, randomized
invariant tests).

## Analysis pipeline

1. **Import** (`ImportService`): the upload is streamed to `data/tmp` with a size limit and hashed; `SafeZipExtractor`
   validates every entry and extracts only `.java` files into a new `data/imports/<uuid>` directory. The project and
   version rows are committed, then an analysis is queued. Every failure removes the temporary file and directory.
2. **Scheduling** (`AnalysisService`): a bounded worker pool (`concurrency`, `queue-capacity`) with large-stack
   threads. At most one analysis per project is pending or running. A full queue fails fast with HTTP 503.
3. **Execution** (`AnalysisRunner`): `PENDING → RUNNING` (one transaction), then `JavaProjectAnalyzer` runs outside any
   transaction: discovery → parsing → declaration index → hierarchy → relationship extraction. Symbol resolution runs
   under `SymbolSolverGate` (one analysis at a time, because JavaParser keeps static caches) with a per-reference
   time budget. `SnapshotMetrics` computes cycles and per-node metrics.
4. **Persistence** (`SnapshotWriter`): files, entities, relationships, diagnostics and the final status are written in
   **one transaction** (JDBC batch inserts plus the JPA status update). A failure rolls everything back and the
   analysis is marked FAILED in a separate transaction; earlier snapshots are untouched.
5. **Queries**: `SnapshotService` resolves the requested snapshot (or the latest COMPLETED/PARTIAL one when none is
   requested), loads it into an immutable `SnapshotData` and caches it (LRU). Graphs per filter are built lazily and
   cached inside the snapshot, which is safe because snapshots never change.

## Snapshot lifecycle

```
PENDING ──▶ RUNNING ──▶ COMPLETED | PARTIAL   (rows + status in one commit)
   │           │
   └───────────┴──────▶ FAILED                (no rows; error message stored)
```

* Finished snapshots are immutable; the entity refuses further transitions.
* On startup, before the web server accepts requests, analyses left PENDING or RUNNING by a previous process are
  marked FAILED (their results were never committed) and orphaned import directories are removed.
* A failed analysis never replaces results: queries default to the latest snapshot **with results**.
* Snapshots are deleted only explicitly (by the user, or with their project). When a project reaches
  `max-snapshots-per-project`, new analyses are refused with an explanation instead of silently deleting history.

## Persistence model

Flyway (`db/migration/V1__initial_schema.sql`) owns the schema; Hibernate only validates it.

* `project` 1—n `source_import` (stored ZIP versions) and 1—n `analysis` (snapshots).
* `analysis` 1—n `code_entity`, `dependency_edge` (aggregated relationships with JSON reference samples),
  `diagnostic`, `diagnostic_count` (exact counts per category) and `source_file`.
* All child tables use `ON DELETE CASCADE`, so deleting a project or snapshot is a single statement.
* `app_settings` is a single row of user settings.

## Frontend

Thymeleaf renders page shells (layout, navigation, CSRF meta tag); each page's script loads data from the REST API.
`core.js` provides the API client (adds the CSRF header, maps error bodies), a DOM builder that only inserts text
(never HTML) for server data, toasts, accessible dialogs, formatters and the snapshot selector shared by all project
pages. `graph.js` wraps Cytoscape.js (styles derived from CSS variables, layouts, highlighting). The selected
snapshot is part of the URL (`?analysisId=`) and every project page loads data for exactly that snapshot.
