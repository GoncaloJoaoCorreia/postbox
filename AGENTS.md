# AGENTS.md — postbox

Kotlin/Ktor monorepo scaffold. The only Gradle module is `:backend` (the server). `frontend/` and `docker/` are placeholders (README only) reserved for later issues.

## Commands

- `./gradlew check` — compile + run all tests (the QA gate for this repo)
- `./gradlew :backend:test` — backend tests only
- `./gradlew :backend:test --tests "com.postbox.HealthzTest"` — run a single test class
- `./gradlew run` — start the server (blocking, foreground); then `curl http://localhost:8080/healthz` → `{"status":"ok"}`
- Always use the committed wrapper (`./gradlew`); no system Gradle needed. First build requires network (wrapper bootstrap, Maven Central, Plugin Portal) and auto-provisions a JDK 17 toolchain (`jvmToolchain(17)`).

## Wiring

- Package `com.postbox`; entrypoint `backend/src/main/kotlin/com/postbox/Application.kt` (mainClass `com.postbox.ApplicationKt`). The root `run` task in `build.gradle.kts` just delegates to `:backend:run`.
- Routes are registered in `Application.module(config)`; currently only `GET /healthz` → `{"status":"ok"}`, unauthenticated.
- Logging is structured JSON on the console: Logback + LogstashEncoder, config at `backend/src/main/resources/logback.xml`.

## Config

- Defaults live in HOCON at `backend/src/main/resources/application.conf`; env vars override them via the `${?VAR}` optional-substitution pattern merged with `ConfigFactory.systemEnvironment()` in `ConfigLoader.load` (`backend/.../Config.kt`). Overridable keys: `PORT` (default `8080`), `JWT_SECRET`, `SQLITE_PATH` (default `data/postbox.db`), `DATA_DIR` (default `data`). Example: `PORT=18080 ./gradlew run`.
- To add a new env-overridable key, declare the default and then a second line `key = ${?ENV_VAR}` — in HOCON the later assignment wins and `${?...}` is dropped when the var is unset.
- `JWT_SECRET` ships a dev-only insecure default in `application.conf`; it MUST be overridden via env in any real deployment.
- Port already in use → `BindException`, logged with a hint to set `PORT` to a free port.

## Tests

- Ktor `testApplication` runs in-process (no real port — pass `port = 0` in the test config); never bind fixed ports in tests.
- `kotlin("test")` on JUnit Platform (`useJUnitPlatform()`; `testLogging` prints passed/skipped/failed). Tests write under `build/tmp/...`, never into the gitignored `data/` runtime dir.

## Dependencies

- All versions are pinned in the version catalog `gradle/libs.versions.toml`; add libraries/plugins there with `version.ref` instead of inline versions in `build.gradle.kts`.

## Git conventions

- Branch names: `<type>/<linear-issue>`, e.g. `feat/G-75` or `fix/G-12` — optionally with a short slug appended (`feat/G-50-sqlite-data-model`). Types: `feat`, `fix`, `docs`, `research`, `chore`. One branch per Linear issue.
- Commit messages follow Conventional Commits: `<type>(<scope>): <short summary>` (e.g. `feat(backend): scaffold Kotlin/Ktor backend`), one logical change per commit. Reference the Linear issue in the commit body (e.g. `Refs G-75`).

## Housekeeping

- `.gitignore` already covers `.plan/`, `.gradle/`, `build/`, `data/`, `*.db*` — never commit build outputs or the SQLite data dir.
- No CI workflows yet; `./gradlew check` is the gate.
