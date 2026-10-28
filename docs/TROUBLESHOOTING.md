# Troubleshooting

### `PKIX path building failed` while Maven downloads dependencies
An antivirus or corporate proxy re-signs HTTPS traffic with its own root certificate, which the JDK does not trust.
On Windows, tell the JVM to use the Windows certificate store for that build:

```powershell
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT"
.\mvnw.cmd package
```

On macOS use `-Djavax.net.ssl.trustStoreType=KeychainStore`; on Linux, import the proxy's root certificate into the
JDK (`keytool -importcert -cacerts …`). The application itself makes no outbound connections.

### The IDE shows hundreds of "cannot be resolved" errors, but `./mvnw verify` passes
The IDE compiler must not share `target/` with command-line Maven. If it does, every `mvnw clean` deletes the
classes its incremental build relies on, and the IDE's half-built classes can break Maven test runs
(`NoClassDefFoundError`). The `ide` profile in `pom.xml` is activated automatically by m2e-based IDEs (Eclipse,
the Java extension for VS Code). It moves the IDE's output to `target-ide/`. If the errors appear anyway,
for example right after the profile was added, reload the project:

* **VS Code:** run "Java: Reload Projects", or, if that is not enough, "Java: Clean Java Language Server Workspace".
* **Eclipse:** Maven → Update Project.

### `Port 8080 is already in use`
Start on another port: `--server.port=9090` or `RIPPLEMAP_PORT=9090`.

### The page says "Your session token has expired"
RippleMap was restarted; every process issues a new security token. Reload the page.

### "Requests must use a local host name"
Open RippleMap via `http://localhost:<port>` or `http://127.0.0.1:<port>`. Other host names are rejected to prevent
DNS-rebinding attacks; add names to `ripplemap.security.allowed-hosts` only if you know why you need them.

### An import is rejected
The message names the rule: invalid or encrypted ZIP, no `.java` files, unsafe entry names (`../`, absolute paths,
reserved names), duplicate entries, or a size limit. Limits are listed on the Import and Settings pages and can be
raised in `application.yml` (`ripplemap.import.*`, `spring.servlet.multipart.max-file-size`).

### An analysis is PARTIAL
Some files could not be parsed, read, or were larger than `ripplemap.analysis.max-file-size`. The overview lists the
files and the diagnostics show the parse errors. If the project uses Java features newer than 21, set
`ripplemap.analysis.language-level` (for example `JAVA_25`).

### Many unresolved references
The analyzer only sees the uploaded sources and the JDK. References into libraries that share a name with a project
type, or calls whose receiver type comes from a library, cannot always be resolved. Unresolved references are listed
in the diagnostics. `LIKELY_EXTERNAL` entries are almost always library symbols; `UNRESOLVED_*` entries may hide
project dependencies.

### Calls are missing in a Lombok (or AutoValue, Immutables, MapStruct) project
Code generated at compile time does not exist in the sources RippleMap reads. Calls to generated getters, builders
or mappers therefore add no method-level edge and are counted as external. Such files are listed with a
`GENERATED_CODE` diagnostic. Type-level dependencies that appear in declarations (fields, parameters, variables)
are still recorded. Workarounds:

* **AutoValue, Immutables, MapStruct:** they write classes to `target/generated-sources/annotations`. Copy those into a
  regular source directory before importing (RippleMap skips build-output directories).
* **Lombok:** it changes the compiler's syntax tree and writes no source files. Run `delombok` (the Lombok Maven or
  Gradle plugin) and import its output instead.

### An analysis takes long or hits the time limit
Symbol resolution is the expensive part, especially for deep class hierarchies with many fields. Progress is shown as
"Resolving symbols: file N of M". Options: analyze production sources only, raise `ripplemap.analysis.timeout`, or
lower `ripplemap.analysis.reference-budget` (references over the budget use the fallback rules and are reported as
`RESOLUTION_TIMEOUT`).

### An analysis stays RUNNING after the application was killed
On the next start, RippleMap marks such analyses FAILED (no partial results were saved). Start a new analysis.

### "This project already has 100 snapshots"
Delete old snapshots in Analysis History, or raise `ripplemap.analysis.max-snapshots-per-project`.

### Out of memory for very large projects
Each analysis holds the syntax trees of the project in memory while it runs. Give the JVM more heap
(`java -Xmx4g -jar …`) or lower `ripplemap.analysis.concurrency` to 1.

### Resetting all data
Stop RippleMap and delete the data directory (default `~/.ripplemap`). Nothing else is stored.
