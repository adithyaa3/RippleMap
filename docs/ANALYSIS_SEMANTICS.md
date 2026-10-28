# Analysis semantics

This document is the contract for what RippleMap extracts from Java source code and how the dependency graph,
impact analysis and cycle detection interpret it. The golden tests in `src/test/java/com/ripplemap/analysis`,
`.../graph` and `.../impact` encode these rules with hand-calculated expectations.

RippleMap treats source code as data. Nothing is compiled, loaded or executed; build files are ignored.

## 1. Edge direction

**`A → B` means "A depends on B".**

| Question | Traversal |
|---|---|
| What does A depend on? | outgoing edges of A |
| What depends on A? | incoming edges of A |
| What may be affected if A changes? | reverse traversal (incoming edges), transitively |

Example: `OrderController → OrderService → OrderRepository`. Changing `OrderRepository` may affect `OrderService`
(directly) and `OrderController` (transitively). Changing `OrderController` affects nothing. Storage, queries,
impact, visualization (arrows point at the dependency) and reports all use this convention; tests such as
`chainFixtureEdgesAreNeverReversed` and `singleEdgeDirectionIsDependsOn` guard it.

## 2. Source discovery and scope

* Every regular `.java` file below the analyzed root is discovered. Symbolic links are never followed (they are
  reported as `SKIPPED_LINK`); hidden directories (`.git`, `.idea`, …) and `node_modules` are not visited.
* **Generated build output** is excluded: a `target`, `build` or `out` directory directly containing
  `generated-sources`, `generated-test-sources`, `generated`, `classes`, `test-classes`, `tmp` or `production`.
* **Test sources** are files below `src/test`, `src/it`, `src/tests` or a test source set such as `src/testFixtures`,
  `src/integrationTest`, `src/functional-test`, or below a top-level `test`/`tests` directory (a single wrapper
  directory, as produced by most ZIP exports, is skipped when looking for the top level). They are analyzed only
  when *Include test sources* is selected.
* Files are read as UTF-8 (a byte-order mark is removed). Invalid byte sequences are replaced and reported as
  `ENCODING_REPLACED`; parsing continues.
* Files larger than `ripplemap.analysis.max-file-size` (2 MB) are not parsed (`FILE_TOO_LARGE`).
* Files are processed in path order, so identical input always yields identical output.

## 3. Parsing and partial results

JavaParser 3.28 parses with language level `JAVA_21` (configurable through `ripplemap.analysis.language-level`).
A file with any parse problem is **not** used at all: no declarations and no relationships are extracted from an
error-recovered syntax tree, so nothing is fabricated. Up to three problems per file are stored as `PARSE_ERROR`.

An analysis is **PARTIAL** when at least one in-scope file could not be read, was too large, or failed to parse;
otherwise it is **COMPLETED**. Unresolved references do not make an analysis partial (almost every real project
references libraries that are not available to the analyzer); they are counted and reported separately.

The base name of a file that failed to parse is remembered: references to that name are reported as
`UNRESOLVED_TYPE` (a project dependency may be missing) instead of being treated as external.

## 4. Entities and stable identifiers

| Kind | Graph layer | Identifier |
|---|---|---|
| class, interface, enum, record, annotation type | type | `com.shop.Order`, nested: `com.shop.Order$Line` |
| method | method | `com.shop.Order#total(int,java.util.List)` |
| constructor | method | `com.shop.Order#<init>(String)` |
| field, enum constant, record component | — (metadata) | `com.shop.Order#items` |

* Packages use `.`; member types use `$`, so `com.a.Outer$Inner` never collides with a class `Inner` in a
  package `com.a.Outer`. Classes with the same simple name in different packages are distinct.
* Parameter types are the types **as written in the declaration with generic arguments removed**
  (`List<Order>` → `List`, `String...` stays `String...`, arrays keep `[]`). Overloads therefore have distinct ids,
  and the identifier does not depend on whether the solver could resolve a parameter type.
* Only top-level and member types are nodes. Local, anonymous and enum-constant-body classes are not; their code is
  attributed to the enclosing declaration (section 6).
* If several files declare the same canonical type name (for example two modules of a multi-module project), every
  such type gets the suffix `[relative/path/File.java]` and a `DUPLICATE_TYPE` warning; references to that name are
  reported as `AMBIGUOUS_TYPE` and do not become edges. Duplicate member signatures (possible in code that parses
  but would not compile) get an `@L<line>` suffix and a `DUPLICATE_MEMBER` warning.
* Identifiers are derived from source only. Database ids never influence identity, so snapshots can be compared.

Each entity records its kind, display name, canonical name, package, declaring type, signature (callables: written
parameter types and return type; fields: declared type), file, declaration line (the line of the name), end line,
visibility (interface and annotation members default to `public`) and non-access modifiers.

## 5. Relationship kinds

| Kind | Layer | Produced by |
|---|---|---|
| `EXTENDS` | type | `class A extends B`, `interface A extends B` |
| `IMPLEMENTS` | type | `implements` of classes, enums and records |
| `FIELD_TYPE` | type | declared types of fields and record components, including generic arguments (`List<Order>`) |
| `METHOD_PARAMETER_TYPE` | type | parameter types of methods |
| `METHOD_RETURN_TYPE` | type | return types of methods |
| `CONSTRUCTOR_PARAMETER_TYPE` | type | parameter types of constructors |
| `OBJECT_CREATION` | type, method | `new B(...)` (type layer: the created type; method layer: the constructor) |
| `METHOD_CALL` | type, method | method calls (type layer: caller's type → declaring type of the target) |
| `METHOD_REFERENCE` | type, method | `B::m`, `obj::m` |
| `CONSTRUCTOR_CALL` | type, method | explicit `this(...)` / `super(...)` |
| `FIELD_ACCESS` | type | `B.CONSTANT`, `obj.field`, enum constants `Status.ACTIVE`, statically imported fields |
| `ANNOTATION_USAGE` | type | `@B` where `B` is a project annotation type |
| `TYPE_REFERENCE` | type | any other type use: local variables, casts, `instanceof` and patterns, class literals, `throws`, catch parameters, type bounds, generic arguments of supertypes or created objects, `permits`, array creation, method-reference qualifiers, and supertypes/fields/signatures of local or anonymous classes |

Imports are **not** relationships: an unused import never creates a dependency. Imports are only used as
evidence for name resolution.

**Self-references are not edges on the type layer.** A type that mentions itself (recursive structures,
fluent `return this` APIs, its own constants) does not depend on another component; these references are counted
as *internal*. On the method layer a recursive call **is** an edge (a self-loop), and recursion is reported as a
cycle of the method graph.

Every occurrence keeps its file, line, a short description (for example `field repository`, `call save(1 arg)`)
and its resolution. Occurrences of the same `(layer, source, target, kind)` are aggregated into one relationship
with exact counts (`referenceCount`, `resolvedCount`, `inferredCount`) and up to
`ripplemap.analysis.max-reference-samples` (20) sample locations, chosen deterministically.

Three counts are reported and never mixed: **dependencies** are distinct source→target pairs of a layer,
**relationships** are distinct pairs per kind, and **references** are individual source occurrences.

## 6. Attribution

* The **type-level source** of a reference is the nearest enclosing top-level or member type.
* The **method-level source** is the nearest enclosing method or constructor of such a type. Code in lambdas,
  anonymous classes and local classes belongs to the enclosing method. Code in field initializers, initializer
  blocks and enum constant arguments has no method-level source and contributes to the type layer only.
* Implicitly declared members have no method-level node: calls to an enum's `values()`/`valueOf()` or to a record's
  generated accessors create a type-level edge to the enum/record only. Calls to implicit default constructors create
  no method-level edge (the type-level `OBJECT_CREATION` edge exists).

## 7. Resolution

Every reference that **could** denote a project declaration is resolved with the JavaParser symbol solver, using a
type solver that knows exactly the parsed project sources plus JDK (`java.*`, `javax.*`) types. Classes on
RippleMap's own classpath are never consulted, and analyzed code is never loaded.

A reference is classified before resolution when its name cannot possibly denote a project declaration: a type name
whose last segment is not the simple name of any project type, a call or method reference whose name is not declared
by any project type (including implicit enum/record members), or a call on a variable whose declared type is not a
project type (and not a type parameter), such as `list.add(x)`. Such a reference cannot target any declaration in the
analyzed sources, so it can never become an edge; it is counted as **external** and the solver is not invoked.
"External" therefore means *not declared in the analyzed sources*. That is usually the JDK or a library, but it also
covers members generated at compile time, such as Lombok getters, which source analysis cannot see. Files that import
a known code generator (`lombok`, `com.google.auto.value`, `org.immutables`, `org.mapstruct`) are reported with a
`GENERATED_CODE` diagnostic so this is visible.

| Classification | Meaning | Becomes an edge |
|---|---|---|
| `RESOLVED` | the symbol solver identified a project declaration | yes |
| `INFERRED` | the solver failed; a documented rule below identified a unique target | yes (shown dashed) |
| `UNRESOLVED` | no reliable target; a diagnostic is recorded | no |

### Inference rules (used only when the solver fails)

All inferred occurrences record their rule id.

**Type names** — JLS-ordered lookup over the project index (`NameLookup`):

1. type parameters and local classes in scope → not a dependency;
2. a lexically enclosing project type or one of its member types, including member types inherited from project
   supertypes (`ENCLOSING_TYPE`, `MEMBER_TYPE`, `INHERITED_MEMBER_TYPE`);
3. a single-type import (`SINGLE_TYPE_IMPORT`; an import of a non-project type means *external*);
4. a top-level type of the same package (`SAME_PACKAGE`);
5. exactly one project type provided by on-demand imports (`ON_DEMAND_IMPORT`; several → `AMBIGUOUS_TYPE`);
6. otherwise: a JDK type from `java.lang` or an imported JDK package → external; an on-demand import of a
   non-project package could provide it → `LIKELY_EXTERNAL`; else `UNRESOLVED_TYPE`.

Qualified names are matched exactly against canonical names (`QUALIFIED_NAME`) or resolved head-first
(`Outer.Inner`); names qualified by a package outside the project are external.

**Method calls** — the receiver type is determined (solver for the receiver expression; else the declared type of
a variable, parameter, pattern binding or field found by lexical scoping; else a type name for static calls; else
the return type of a project method for call chains). With a project receiver type `T`:

* candidates are the methods named `m` declared in `T` and its project supertypes whose arity accepts the call
  (varargs-aware);
* exactly one candidate → that method (`RECEIVER_TYPE_UNIQUE_ARITY`);
* several candidates with the same written signature (overrides) → the most derived one
  (`…_MOST_DERIVED_OVERRIDE`);
* several overloads of one declaring type → a type-level edge to that type only, plus `AMBIGUOUS_CALL` for the method
  layer (`…_OVERLOAD_OWNER`);
* candidates in different types → `AMBIGUOUS_CALL`, no edge;
* no candidate: external if `T` has a library supertype or the name is a `java.lang.Object` method, else
  `UNRESOLVED_CALL`.

Unqualified calls search the enclosing types from the innermost outward (`ENCLOSING_SCOPE_…`), then static imports
(`STATIC_IMPORT_UNIQUE_ARITY`). Inferred method-call targets never rely on the method name alone: the receiver type
and the arity must both match.

Constructors without solver support use a unique arity match among the declared constructors
(`CONSTRUCTOR_UNIQUE_ARITY`, `THIS_CONSTRUCTOR_…`, `SUPER_CONSTRUCTOR_…`). Field accesses use the receiver type and
the nearest declaring type of that field name (`RECEIVER_TYPE_FIELD`); statically imported fields use the import when
no local variable, parameter or field of the same name is in scope (`STATIC_IMPORT_FIELD`).

### Time budget

The symbol solver cannot be interrupted, and on some code bases single invocations become very expensive. Every
type lookup the solver performs passes through RippleMap's type solver, which checks a per-reference budget
(`ripplemap.analysis.reference-budget`, 2 s) as well as the analysis deadline and cancellation. A reference that
exceeds the budget falls back to the rules above and is reported as `RESOLUTION_TIMEOUT`.

## 8. What static resolution cannot see

* **Polymorphic dispatch.** A call through an interface resolves to the interface method. Changing an
  implementation therefore does not mark callers of the interface method as affected on the method layer. On the
  type layer, implementations depend on their interfaces (`IMPLEMENTS`).
* Reflection, dependency-injection wiring, annotation processing, generated code, configuration files and
  frameworks that connect components at runtime. For example, a call to a Lombok-generated getter adds no
  method-level edge. The type-level dependency on the class remains when it is visible in a declaration (field,
  parameter, variable, …).
* Library code: members inherited from library types are external, and library types that would extend project
  types (for example modules missing from the upload) are not visible.

The impact page and the reports state these limitations next to every result.

## 9. Graph algorithms

The graph engine (`com.ripplemap.graph`) is independent of parsing, persistence and the UI.

* Nodes are sorted by id and adjacency lists are sorted and duplicate-free, so every traversal is deterministic.
  Duplicate edges collapse; edges whose endpoints are not nodes are ignored and counted.
* Traversals are iterative breadth-first searches with visited sets (no recursion limits, no infinite loops on
  cycles). The start node is never part of its own reachable set, even when it lies on a cycle.
* Explanation paths are BFS shortest paths; each consecutive pair of an explanation path is a real edge.
  Ties are broken by ascending node id.
* Strongly connected components use an iterative Tarjan implementation. A component is **cyclic** when it has more
  than one member, or one member with a self-loop. The number of cyclic components is not the number of distinct
  simple cycles. For each cyclic component one **representative cycle** is shown: the shortest cycle through its
  smallest member (BFS restricted to the component).
* Isolated nodes have no edge to or from another node (self-loops ignored). Degrees count distinct other nodes.
* Graph filters (node kinds, relationship kinds, resolved-only) define the subgraph on which neighborhoods,
  transitive scopes and cycles are computed.

## 10. Impact analysis

For a selected node of a layer, with `N` = nodes of that layer, `D` = distinct direct dependents,
`T` = distinct transitive dependents (direct ones included, the selected node excluded) and
`H` = the largest shortest reverse distance to an affected node:

```
DirectSignal = D / (N − 1)          ReachSignal = T / (N − 1)          DepthSignal = min(H, 5) / 5

Score = 100 × (0.45 × DirectSignal + 0.40 × ReachSignal + 0.15 × DepthSignal)
      = (45 × D + 40 × T) / (N − 1) + 3 × min(H, 5)
```

* The score is 0 when `N ≤ 1` or there are no dependents, and is clamped to [0, 100].
* `ImpactPercentage = 100 × T / (N − 1)` (0 when `N ≤ 1`).
* The second, algebraically identical form is used for computation, so the golden values are exact
  (chain `Controller → Service → Repository`, changing `Repository`: `N=3, D=1, T=2, H=2`, score **68.5**, 100 %,
  HIGH; diamond `A→B, A→C, B→D, C→D`, changing `D`: `N=4, D=2, T=3, H=2`, score **76.0**, `A` counted once).
* Classification uses exact rational comparison against the thresholds (default LOW `< 30`, MEDIUM `30–60`,
  HIGH `≥ 60`; configurable, `0 < low < high ≤ 100`). Values are rounded to one decimal (half-up) for display only.
* Affected source files are the distinct files that declare affected entities.
* By default impact follows resolved and inferred relationships; *resolved only* is available per request and as a
  setting.
* The score is a structural heuristic: **potential impact does not necessarily mean the change will break the
  application**, and unresolved references may hide dependents. Diagnostics whose unresolved symbol equals the name
  of the selected component are listed with the result.

Per-type metrics (direct and transitive dependents, maximum distance, score) are computed once per snapshot with the
default model and stored, so dashboards can rank types without recomputation. For type graphs above
`ripplemap.analysis.node-metrics-limit` (20 000 types) only degrees are stored; impact requests always compute on
demand.

## 11. Diagnostics

| Category | Severity | Meaning |
|---|---|---|
| `PARSE_ERROR`, `FILE_TOO_LARGE`, `FILE_UNREADABLE` | error | a file was not analyzed (makes the snapshot PARTIAL) |
| `UNRESOLVED_TYPE`, `AMBIGUOUS_TYPE`, `UNRESOLVED_CALL`, `AMBIGUOUS_CALL`, `UNRESOLVED_FIELD` | warning | a project dependency may be missing |
| `RESOLUTION_ERROR` | warning | the solver failed unexpectedly on an expression |
| `DUPLICATE_TYPE`, `DUPLICATE_MEMBER`, `ENCODING_REPLACED`, `SKIPPED_LINK`, `LIMIT_REACHED` | warning | input anomalies |
| `LIKELY_EXTERNAL`, `RESOLUTION_TIMEOUT`, `EXCLUDED_SOURCES`, `GENERATED_CODE` | info | informational |

Counts per category are exact. At most `ripplemap.analysis.max-diagnostics` (5 000) diagnostics are stored per
snapshot; the snapshot records whether the list was truncated. Diagnostic text is bounded, stripped of control and
bidi characters, and always escaped when displayed.
