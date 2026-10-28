# User guide

## Start RippleMap

```bash
./mvnw spring-boot:run          # macOS / Linux
mvnw.cmd spring-boot:run        # Windows
# or: ./mvnw package -DskipTests && java -jar target/ripplemap-1.0.0.jar
```

Open <http://localhost:8080>. Data (database and imported sources) is stored in `~/.ripplemap`; use
`--ripplemap.data-dir=/some/dir` or the `RIPPLEMAP_DATA_DIR` environment variable to change it, and
`--server.port=9090` (or `RIPPLEMAP_PORT`) to use another port.

## 1. Import a project

1. Choose **Import Project**.
2. Enter a unique name (letters, digits, spaces and `. _ - ( ) [ ]`) and an optional description.
3. Drop or browse for a `.zip` of the project. Maven, Gradle and plain source layouts are supported; only `.java`
   files are extracted, and generated sources under `target/` or `build/` are skipped.
4. Tick **Include test sources** to analyze `src/test/java` and similar test directories too.
5. Select **Import and analyze**. The page shows upload progress, then the analysis stage, then a summary with links
   to the overview and explorer.

Try the bundled samples in `sample-projects/` (see its README): `shop-v1.zip` and `shop-v2.zip` are two versions of a
small order-management service. Version 1 has a circular dependency, and both versions reference libraries that are
not part of the upload, which RippleMap reports as external.

Rejected uploads explain why: not a ZIP, no Java files, unsafe paths (`../`, absolute paths), duplicate entries,
or size limits.

## 2. Project overview

Shows the project metadata, the selected snapshot's counts (files, types, methods, dependencies, cycles, unresolved
references), the types with the highest structural impact, how references were resolved, the diagnostics, and source
files that could not be analyzed.

Actions: **Edit details**, **Upload new version** (ZIP projects), **Re-analyze version** / **Rescan directory**, and
**Delete** (asks you to type the project name).

### Snapshots

Every analysis is an immutable snapshot. The **Snapshot** selector at the top of every project page chooses which
snapshot all data comes from; the choice is kept in the URL (`?analysisId=`), so links and bookmarks always show the
same data. A banner appears when you look at a historical or partial snapshot. If an analysis fails, earlier
snapshots stay available and remain the default.

**Partial** means some files could not be parsed (see the diagnostics). Their declarations are missing, so results
can be incomplete.

## 3. Dependency explorer

* **Arrows point from a component to what it depends on.** `OrderController → OrderService` means the controller
  depends on the service.
* **Types / Methods** switches between the type graph and the method/constructor call graph.
* **Search** finds components by name (arrow keys and Enter work). Selecting a result focuses it.
* Clicking a node highlights what it depends on (blue) and what depends on it (amber) and opens the details panel:
  identifier, file and line, metrics, direct dependencies and dependents (with relationship kinds), members and
  diagnostics. Clicking an edge lists every relationship kind and source reference behind it.
* **Scope**: whole graph, the neighborhood of the selection (depth 1–4), or the transitive dependents or dependencies
  of the selection. Large graphs are limited to the explorer node limit (Settings) and the page says so explicitly.
* **Filters** restrict node types and relationship kinds, and can hide inferred relationships (dashed edges).
* **Layout**: force-directed, hierarchy, concentric or grid; zoom, fit and reset buttons are in the lower right.
* Nodes with a red dashed border belong to a circular dependency.

## 4. Impact analysis

1. Choose **Types** or **Methods**.
2. Search for the component you plan to change and select it.
3. Select **Analyze impact**.

The result shows the score (0–100) and level (LOW / MEDIUM / HIGH), the direct and transitive dependents, the share of
the project they represent, the longest dependency chain, the affected files, how each factor contributed to the
score, the affected region as a graph, and for every affected component **why** it is affected: the shortest dependency
path to the changed component.

Changing the component, the level, the snapshot or the options clears the previous result, so a result is never
shown for a question you did not ask. Download the result as HTML, CSV or JSON.

> Potential impact does not necessarily mean the change will break the application. The score is a structural
> heuristic, not the probability of a defect. Unresolved references may hide dependents; they are listed with the
> result.

See `ANALYSIS_SEMANTICS.md` for the exact formula.

## 5. Circular dependencies

Lists strongly connected components (groups of components that all depend on each other), largest first, for the
type or method level. Selecting one shows a representative cycle with the relationship and source location of every
step, a graph of the component and its members (linked to the explorer). On the method level, recursion appears as
cycles.

## 6. History and comparison

**Analysis History** lists all snapshots with status, source version, scope, counts and actions (open, explore,
compare with the previous snapshot, delete, cancel a running analysis). Stored versions of ZIP projects are listed
below.

**Snapshot Comparison** compares any two snapshots of the project: added and removed types (or methods), added and
removed dependencies, relationship changes, metric and diagnostic deltas. Changes are matched by stable identifiers
(fully qualified names and signatures).

## 7. Reports

The **Reports** page offers, for the selected snapshot: an HTML analysis report, a full JSON export, and CSV files
for summary metrics, entities, dependencies, cycles and diagnostics. Impact and comparison reports are downloaded
from their pages. CSV files are safe to open in spreadsheets.

## 8. Settings

* Impact thresholds (LOW/MEDIUM and MEDIUM/HIGH boundaries, `0 < low < high ≤ 100`).
* Whether impact analysis includes inferred relationships by default.
* The explorer node limit (25–2000).
* Theme: dark (default), light or follow the system; stored in your browser.

The About panel shows versions, the data directory and all configured limits.

## Local directories (optional)

To analyze a directory on the RippleMap machine directly (and rescan it after edits), start RippleMap with allowed
roots, for example:

```bash
java -jar target/ripplemap-1.0.0.jar --ripplemap.local-scan.allowed-roots=/home/me/workspace
```

The **Local directory** tab of the import page then accepts absolute paths below those roots. **Rescan directory**
reads the current files into a new snapshot.
