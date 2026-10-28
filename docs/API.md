# REST API

Base URL: `http://localhost:8080` (default). An OpenAPI description is served at `/api-docs` and Swagger UI at
`/swagger-ui.html`.

* JSON request and response bodies; times are ISO-8601 instants (UTC).
* **State-changing requests** (POST, PUT, PATCH, DELETE) must send the header `X-RippleMap-CSRF` with the token found
  in the `<meta name="ripplemap-csrf">` tag of any RippleMap page. Requests must use a local Host name.
* Node ids contain characters such as `#`, `$`, `(` and `,`; they are always passed as URL-encoded **query
  parameters** or in JSON bodies, never in path segments.
* `analysisId` is optional wherever it appears: when omitted, the latest COMPLETED or PARTIAL snapshot of the
  project is used. A requested snapshot is always honoured or the request fails (`404 ANALYSIS_NOT_FOUND`,
  `409 ANALYSIS_NOT_AVAILABLE`); the API never substitutes another snapshot.
* Layers: `TYPE` (default) and `METHOD`. Enum parameters are case-insensitive.

## Errors

Every error has the same shape and an appropriate status code:

```json
{ "status": 404, "error": "Not Found", "code": "PROJECT_NOT_FOUND",
  "message": "Project 42 was not found", "path": "/api/projects/42",
  "timestamp": "2026-10-22T10:00:00Z", "fieldErrors": [] }
```

| Status | Typical codes |
|---|---|
| 400 | `VALIDATION_FAILED` (with `fieldErrors`), `MISSING_PARAMETER`, `INVALID_PARAMETER`, `MALFORMED_REQUEST`, `LAYER_MISMATCH` |
| 403 | `CSRF_TOKEN_INVALID`, `ORIGIN_NOT_ALLOWED`, `CROSS_SITE_REQUEST`, `HOST_NOT_ALLOWED`, `LOCAL_SCAN_DISABLED`, `PATH_NOT_ALLOWED` |
| 404 | `PROJECT_NOT_FOUND`, `ANALYSIS_NOT_FOUND`, `NODE_NOT_FOUND`, `EDGE_NOT_FOUND`, `VERSION_NOT_FOUND`, `CYCLE_NOT_FOUND`, `NOT_FOUND` |
| 409 | `PROJECT_NAME_TAKEN`, `ANALYSIS_IN_PROGRESS`, `NO_COMPLETED_ANALYSIS`, `ANALYSIS_NOT_AVAILABLE`, `SNAPSHOT_LIMIT_REACHED`, `NOT_A_ZIP_PROJECT` |
| 413 | `UPLOAD_TOO_LARGE` |
| 422 | `ARCHIVE_REJECTED_<REASON>` (e.g. `…_PATH_TRAVERSAL`, `…_DUPLICATE_ENTRY`, `…_NO_JAVA_FILES`, `…_INVALID_ARCHIVE`) |
| 503 | `ANALYSIS_QUEUE_FULL` |
| 507 | `STORAGE_LIMIT_REACHED` |

## Projects

| Method | Path | Description |
|---|---|---|
| GET | `/api/projects` | projects (newest activity first) with latest analysis and default snapshot id |
| POST | `/api/projects/import` | multipart: `file` (ZIP), `name`, `description?`, `includeTests?` → **201** `{project, version, analysis}`; the analysis runs asynchronously |
| POST | `/api/projects/local` | JSON `{name, description?, path, includeTests}` → **201** (only when local scanning is enabled) |
| GET | `/api/projects/{projectId}` | `{project, versions[], analysisCount, analysisActive, maxSnapshots}` |
| PATCH | `/api/projects/{projectId}` | JSON `{name?, description?, includeTests?}` |
| DELETE | `/api/projects/{projectId}` | **204**; refused (409) while an analysis is active |
| POST | `/api/projects/{projectId}/versions` | multipart: `file`, `label?`, `includeTests?` → **201**; stores the next version and analyzes it |
| POST | `/api/projects/{projectId}/analyze` | JSON `{versionId?, includeTests?}` → **202** analysis summary (re-analysis of a stored version, or rescan of a local directory) |

## Snapshots

| Method | Path | Description |
|---|---|---|
| GET | `/api/projects/{projectId}/analyses` (alias `/history`) | all snapshots, newest first |
| GET | `/api/projects/{projectId}/analyses/{analysisId}` | status, stage while running, error, partial reasons, counts |
| POST | `/api/projects/{projectId}/analyses/{analysisId}/cancel` | cancel a pending analysis, or stop a running one at its next checkpoint |
| DELETE | `/api/projects/{projectId}/analyses/{analysisId}` | delete a finished snapshot (**204**) |
| GET | `/api/projects/{projectId}/analyses/{analysisId}/diagnostics?severity=&category=&page=0&size=50` | paginated diagnostics plus exact counts per category |
| GET | `/api/projects/{projectId}/analyses/{analysisId}/files` | source files with parse status, hash, lines, type count |

Analysis summary fields: `id, projectId, sequenceNumber, status (PENDING|RUNNING|COMPLETED|PARTIAL|FAILED), trigger,
includeTests, versionId, versionNumber, createdAt, startedAt, finishedAt, durationMs, stage, errorMessage,
partialReasons[], hasResults, languageLevel, sourceFingerprint, counts{…}`.

## Graph

| Method | Path | Description |
|---|---|---|
| GET | `/api/projects/{projectId}/graph` | `analysisId, layer, scope (ALL|NEIGHBORHOOD|DEPENDENTS|DEPENDENCIES|CYCLE), focus, depth (1–6), component (cycle index), limit, nodeKinds, relationshipKinds, includeInferred` |
| GET | `/api/projects/{projectId}/nodes?q=&layer=&kind=&limit=20` | search by name (exact, prefix, then substring; ties by impact score) |
| GET | `/api/projects/{projectId}/node?nodeId=` | entity details, stored metrics, direct dependencies and dependents with relationship kinds, members, diagnostics |
| GET | `/api/projects/{projectId}/edge?source=&target=&layer=` | relationship kinds and source references (file, line, detail, resolution, rule) behind one dependency |
| GET | `/api/projects/{projectId}/dependencies?nodeId=&transitive=false&includeInferred=` | what the node depends on, with distances and paths |
| GET | `/api/projects/{projectId}/dependents?nodeId=&transitive=false&includeInferred=` | what depends on the node |
| GET | `/api/projects/{projectId}/path?from=&to=` | shortest dependency path from → to |
| GET | `/api/projects/{projectId}/cycles?layer=&includeInferred=` | cyclic components with members and a representative cycle (with relationship evidence per step) |
| GET | `/api/projects/{projectId}/compare?base=&target=&layer=` | added/removed entities and dependencies, relationship counts, metric and diagnostic deltas |

Graph responses state `totalNodes`, `totalEdges`, `matchedNodes`, `truncated` and `truncationReason`. When more
nodes match than the limit (settings default 300, hard maximum `ripplemap.graph.max-response-nodes`), the response is
truncated explicitly: the most connected nodes (scope ALL) or the nearest nodes (focused scopes) are returned. Edges
are returned only between returned nodes. Each edge lists its relationship kinds, `resolution` (RESOLVED if any
underlying relationship was resolved) and `referenceCount`.

## Impact analysis

`POST /api/projects/{projectId}/impact-analysis`

```json
{ "analysisId": 12, "nodeId": "com.acme.chain.Repository", "layer": "TYPE", "includeInferred": true }
```

Response (abridged):

```json
{ "analysisId": 12, "analysisSequence": 1, "analysisStatus": "COMPLETED", "layer": "TYPE",
  "selected": {"id": "com.acme.chain.Repository", "name": "Repository", "kind": "CLASS", …},
  "includeInferred": true, "eligibleCount": 3, "directCount": 1, "transitiveCount": 2, "maxDistance": 2,
  "directSignal": 0.5, "reachSignal": 1.0, "depthSignal": 0.4,
  "score": 68.5, "scoreRounded": 68.5, "percentage": 100.0, "percentageRounded": 100.0, "level": "HIGH",
  "lowThreshold": 30.00, "highThreshold": 60.00,
  "affectedFileCount": 2, "affectedFiles": [...], "affectedTypeCount": 2,
  "affected": [ {"entity": {...Service}, "distance": 1, "direct": true,
                 "path": [{...Service}, {...Repository}]}, … ],
  "affectedTruncated": false,
  "factors": [ {"name": "Direct dependents", "formula": "D / (N − 1) = 1 / 2", "value": 0.5, "weight": 0.45, "contribution": 22.5}, … ],
  "formula": "Score = 100 × (…)", "limitations": [...], "relatedUnresolved": [...], "disclaimer": "…" }
```

Each `path` runs in dependency direction from the affected component to the selected one.

The `affected` list holds at most `ripplemap.graph.max-impact-entries` items (5 000), and `affectedFiles` at most
500 (`affectedTruncated` reports the first cap). The comparison endpoint caps each change list at
`ripplemap.graph.max-comparison-entries` items (500) and sets `truncated`. All counts are exact. The impact and
comparison **reports** always list every entry.

## Reports

| Path (GET) | Content |
|---|---|
| `/api/projects/{projectId}/reports?analysisId=` | catalog of available reports for the snapshot |
| `…/reports/analysis.html` | readable report: metadata, counts, highest-impact types, cycles, diagnostics, limitations |
| `…/reports/analysis.json` | full export: entities, relationships with samples, cycles, diagnostics |
| `…/reports/summary.csv` | `section,metric,value` rows |
| `…/reports/entities.csv` | types, methods and constructors with metrics |
| `…/reports/dependencies.csv` | all relationships with kind, resolution, counts and first reference |
| `…/reports/cycles.csv?layer=` | cyclic components |
| `…/reports/diagnostics.csv` | stored diagnostics |
| `…/reports/impact.{csv,html,json}?nodeId=&layer=&includeInferred=` | impact report (same computation as the endpoint, every affected component listed) |
| `…/reports/comparison.{csv,html}?base=&target=&layer=` | comparison report (every change listed) |

All accept `analysisId`. Downloads use `Content-Disposition: attachment; filename="ripplemap-<project>-s<n>-<kind>.<ext>"`
and `text/csv`, `text/html` or `application/json` with UTF-8.

## Dashboard, settings, system

| Method | Path | Description |
|---|---|---|
| GET | `/api/dashboard` | totals over each project's latest snapshot with results, recent analyses, highest-impact types, cycle and warning summaries |
| GET | `/api/settings` | `{lowThreshold, highThreshold, includeInferred, explorerNodeLimit, updatedAt}` |
| PUT | `/api/settings` | same fields; validated (`0 < low < high ≤ 100`, two decimals, node limit 25–2000) |
| GET | `/api/system` | versions, Java runtime, data directory, language level, local-scan status, configured limits |
| GET | `/actuator/health` | `{"status":"UP"}` |
