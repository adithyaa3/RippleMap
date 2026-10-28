# Security

RippleMap is a local, single-user developer tool. Imported projects are treated as **untrusted data**.

## Imported code is never executed

* Only `.java` files are extracted from archives; build scripts, binaries, class files and resources are never
  written to disk.
* Sources are parsed into syntax trees. Nothing is compiled, loaded, executed or "built" (no Maven, Gradle, tests or
  scripts of the analyzed project).
* The symbol solver resolves types against the analyzed sources and the JDK only (`java.*`, `javax.*`). Classes on
  RippleMap's own classpath are never used to resolve analyzed code.

## ZIP import protections (`SafeZipExtractor`)

| Threat | Protection |
|---|---|
| Zip Slip / parent traversal | names containing `..` segments (with `/` or `\`) are rejected; every target path is normalized and must stay inside the import directory (checked before and after creating parent directories) |
| Absolute paths | names starting with `/`, `\\`, or a drive letter are rejected |
| Windows-specific names | `:` (drive / alternate data stream), reserved device names (`CON`, `NUL`, `COM1`, …), names ending with `.` or space, and control characters are rejected |
| Symlink entries | links are never created: every entry is written as a regular file with `CREATE_NEW`; a symlink entry becomes a small text file inside the import directory |
| Duplicate entries | duplicate names (case-insensitive, after normalization) are rejected |
| ZIP bombs | only `.java` entries are decompressed; sizes are enforced while streaming (headers are not trusted), per file and in total; entries over 64 KB with a compression ratio above the limit are rejected |
| Excessive entries / depth | entry count, Java file count, path depth and path length limits |
| Malformed / encrypted archives | rejected with a clear message |
| Storage exhaustion | upload size limit, total stored-import limit |
| Cleanup | the temporary upload and the partially extracted directory are removed on every failure; orphaned directories are removed at startup |

Default limits (all configurable under `ripplemap.import.*` and `ripplemap.analysis.*`):

| Limit | Default |
|---|---|
| upload size (`max-upload-size`, multipart limit) | 50 MB |
| extracted Java bytes per archive (`max-expanded-size`) | 200 MB |
| single Java file (`max-entry-size`, `analysis.max-file-size`) | 2 MB |
| archive entries (`max-entries`) | 20 000 |
| Java files (`max-java-files`) | 10 000 |
| path depth / length | 40 / 512 |
| compression ratio | 200 : 1 |
| total stored imports (`max-stored-size`) | 2 GB |
| concurrent analyses / queue | 2 / 16 |
| analysis time limit (`timeout`) | 10 min (cooperative) |
| solver time per reference (`reference-budget`) | 2 s |
| snapshots per project | 100 |
| stored diagnostics per snapshot | 5 000 |
| graph nodes per response | 2 000 (explorer default 300) |

The analysis time limit is cooperative: it is checked between files, while walking syntax trees and inside the symbol
solver's type lookups. A single JavaParser parse of one file cannot be interrupted; file size limits bound that work.

## Local-directory scanning

Disabled by default. When `ripplemap.local-scan.allowed-roots` is set, a directory may be registered only if its real
path (links resolved) lies inside an allowed root. The path is re-checked on every rescan; discovery never follows
symbolic links or other reparse points (Windows directory junctions are reported by the JDK as directories that are
also "other" files and are skipped the same way, see `SourceDiscovery`), and files are opened without following
links. Skipped links are reported as `SKIPPED_LINK` diagnostics. RippleMap never writes into scanned directories.

These rules are covered by `LocalDirectoryIntegrationTest`: a link whose target is outside the allowed root is
rejected at registration, and a link inside a registered project pointing outside is not followed. On Windows without
the symbolic-link privilege the test uses directory junctions; the junction test was confirmed to fail when the
reparse-point check is removed.

## HTTP protections

* The server binds to `127.0.0.1` by default.
* **Host allowlist** (`ripplemap.security.allowed-hosts`: `localhost`, `127.0.0.1`, `[::1]`): other Host headers get
  HTTP 403. This prevents DNS-rebinding attacks from web pages.
* **CSRF**: every POST/PUT/PATCH/DELETE must carry the per-process random token in the `X-RippleMap-CSRF` header.
  The token is only available to pages served by RippleMap. Requests with a foreign `Origin` or with
  `Sec-Fetch-Site: cross-site` are rejected. No CORS headers are ever sent.
* **Security headers** on every response: a strict `Content-Security-Policy` (`default-src 'self'; script-src 'self';
  style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'`), `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cross-Origin-Opener-Policy` and
  `Cross-Origin-Resource-Policy: same-origin`. API responses are `Cache-Control: no-store`.
* Errors never include stack traces or exception messages. Unexpected errors return a generic message; details go to
  the server log.
* The H2 console is disabled. Actuator exposes only `health` and `info`, without details.
* Swagger UI is read-only in practice: "try it out" is disabled, and state-changing calls would lack the CSRF token.
  Swagger UI's bundled page needs inline scripts and styles, so only `/swagger-ui/**` and `/api-docs` are served with
  `script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'` (all other directives unchanged). Every
  RippleMap page keeps the strict policy above.

## Output encoding

* The browser UI inserts all server-provided text with `textContent`; no server data is ever interpreted as HTML.
* HTML reports escape every value and declare `default-src 'none'` (no scripts, no external loads). Downloads use
  `Content-Disposition: attachment` with sanitized file names.
* CSV reports quote per RFC 4180 and prefix text cells that start with `=`, `+`, `-`, `@`, tab or carriage return (and
  their full-width forms) with an apostrophe, so spreadsheets do not evaluate them as formulas.
* Diagnostic messages and symbols are stored bounded and with control and bidirectional-override characters removed.

## Known limitations

* The CSRF token is per process: after a restart, open pages must be reloaded.
* RippleMap has no authentication. Do not expose it to a network (`server.address` other than localhost) without
  putting an authenticating reverse proxy in front of it and adding the host name to `allowed-hosts`.
