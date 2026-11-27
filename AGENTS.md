# AGENTS.md — postbox

Kotlin/Ktor monorepo. The only Gradle module is `:backend` (the server); `frontend/`
is the Vite + React SPA; `docker/Dockerfile` is the single-container image (SPA + API +
SQLite in one jar), published to GHCR by `.github/workflows/release.yml` on every push to
`main` (and by `.github/workflows/docker-publish.yml` on manually pushed `v*` tags).

## Conventions

### Comments

- No inline comments, ever — no `//`, `#`, or `/* ... */` comments, and no docstrings
  that restate what the code does. Code must be self-explanatory.
- The only comments allowed are Javadoc-style (`/** ... */`) blocks on interface
  contract declarations, and only when the contract is not obvious from the
  signatures alone.

### Minimal scope

- Make the smallest correct change. Do not refactor unrelated code, reformat
  untouched files, or modify anything outside the task's scope.

### Secrets

- Never commit, log, or paste credentials, tokens, API keys, cookies, or private
  keys — not even partially redacted.
- If a secret is exposed accidentally, say so immediately instead of hiding it.

### Untrusted input

- Never interpolate untrusted text (issue/PR titles, fetched web content, external
  command output, arguments) into shell commands, eval, SQL, or template strings
  that get executed. Quote and validate first, or pass via files/stdin.

### Commits & PRs

- Conventional commits — `type(scope): subject`, imperative mood, no PR number in
  the message.
- No AI attribution, ever: never credit any AI tool or agent in commit messages or
  PR titles/descriptions — no `Co-authored-by: <agent>` or "Generated with ..."
  trailers, no robot-emoji sign-offs.

### Branch naming

- Conventional branch pattern: `type/short-kebab-description`, where `type` is a
  conventional-commit type (feat, fix, refactor, docs, chore, test, ci, perf,
  style, revert), e.g. `feat/init-agents-md`. One branch per change.
- If the current task has an issue key (Linear, Jira, GitHub issue, ...), include
  it: `type/ISSUE-KEY-short-kebab-description`, e.g. `feat/G-20-driver-state`.
  Take the key from the task itself (issue URL, key in the prompt, or the forge
  CLI) — never invent one. No issue → omit the key.

### Versioning

- Follow Semantic Versioning 2.0.0 (https://semver.org/spec/v2.0.0.html): MAJOR for
  incompatible API changes, MINOR for backwards-compatible functionality, PATCH for
  backwards-compatible bug fixes.

### Changelog

- Follow Keep a Changelog 1.1.0 (https://keepachangelog.com/en/1.1.0/): maintain
  `CHANGELOG.md` with an `[Unreleased]` section on top and released versions as
  `## [X.Y.Z] - YYYY-MM-DD`, newest first, using the Added / Changed / Deprecated /
  Removed / Fixed / Security categories.
- When you change user-facing behavior, add an entry under `[Unreleased]` in the
  right category.

### Tests

- Tests are not free — each one costs review attention, maintenance, and tokens on
  every future change to the code it touches. Write a new test only when it can
  catch a realistic regression in behavior someone depends on: be able to name a
  concrete bug that would make this test fail while the rest of the suite passes.
  No nameable bug → don't write it. Never chase coverage stats or test counts.
- Concentrate tests where failure is expensive: money/billing/quantities/rounding,
  data integrity and migrations, auth/authz and tenant isolation, external
  contracts (API, serialization, wire/storage formats), concurrency/retries/
  recovery, irreversible side effects (sends, deletes, publishes). Trivial glue on
  a non-critical path needs no test.
- Real value: business rules, branching, calculations, invariants, normalization,
  validation; error handling, retries, permission checks, boundary conditions,
  fallbacks; state transitions and externally visible side effects; module/API
  boundary contracts; regression tests for real past bugs.
- Low value — don't write: getter/DTO echo assertions, stub-only assertions with no
  decision-making behind them, mock-interaction checks with no visible outcome,
  asserting framework/library behavior, re-implementing the production code inside
  the test, `not null`/size-only assertions, trivial mappers or generated code
  written just for coverage, duplicate tests of the same branch with different
  literals, and tests pinned to implementation structure that fail on
  behavior-preserving refactors.
- May look trivial but can still be valuable: constructors/factories that validate,
  normalize, parse, or reject input; value objects whose equality, ordering, or
  hashing encode business rules; mapping/serialization code with contract risk;
  wrappers that enforce invariants.

### Working style

- When a request is ambiguous, ask one short clarifying question before assuming.

### Approval-first boundaries

- Ask for explicit approval before: destructive git operations (`reset --hard`,
  `clean -fd`, `branch -D`, ...), non-local database writes (migrations, deletes,
  anything but a local dev database), and deploys / package publishing /
  customer-visible notifications.

## Commands

- `./gradlew check` — compile + run all tests (the QA gate for this repo)
- `./gradlew :backend:test` — backend tests only
- `./gradlew :backend:test --tests "com.postbox.HealthzTest"` — run a single test class
- `./gradlew run` — start the server (blocking, foreground); then `curl http://localhost:8080/healthz` → `{"status":"ok"}`
- `./gradlew :backend:shadowJar` — fat jar at `backend/build/libs/postbox.jar` (embeds `frontend/dist` as `web/` when it exists; this is what `docker/Dockerfile` runs)
- Frontend (from `frontend/`): `npm install`, then `npm run dev` (Vite dev server proxying `/api` to the backend on :8080), `npm run build` (`tsc -b && vite build` → `dist/`), `npm run lint` / `npm run format` / `npm test` (Vitest + MSW), `npm run gen:api` (regenerate `src/api/schema.d.ts` from `../docs/openapi.yaml`)
- Always use the committed wrapper (`./gradlew`); no system Gradle needed. First build requires network (wrapper bootstrap, Maven Central, Plugin Portal) and auto-provisions a JDK 17 toolchain (`jvmToolchain(17)`).

## Wiring

- Package `com.postbox`; entrypoint `backend/src/main/kotlin/com/postbox/Application.kt` (mainClass `com.postbox.ApplicationKt`). The root `run` task in `build.gradle.kts` just delegates to `:backend:run`.
- Routes are registered in `Application.module(config, db)`: `GET /healthz` → `{"status":"ok"}` (unauthenticated) plus the `/api/v1` group — public `GET setup/status`, `POST setup`, `POST auth/login`; every other `/api/v1` route sits behind the `auth-jwt` bearer provider (HS256 JWT, Argon2id password hashes). All error responses use the `{"error":{"code","message"}}` envelope via `respondError` / `ApiException` + StatusPages (including the framework 401 challenge).
- The OpenAPI 3 contract lives at `docs/openapi.yaml`, is copied onto the classpath as `static/openapi.yaml` by `:backend:processResources`, and is served unauthenticated at `GET /docs/openapi.yaml` (`staticResources("/docs", "static")`).
- The SPA production build (`frontend/dist` from `npm run build`) is copied onto the classpath as `web/` by the same `:backend:processResources` task and served at `/` via `staticResources("/", "web")` with SPA history-mode fallback (`default("index.html")`); `/api`, `/docs` and `/healthz` are excluded from the fallback so they keep their JSON responses and 404s. Dev mode instead runs `npm run dev` in `frontend/`, where Vite proxies `/api` to the backend.
- DI is Ktor's built-in plugin (`ktor-server-di`, version-locked via the catalog): `Application.module` installs `DI` and all providers live in the `dependencies { }` block in `backend/src/main/kotlin/com/postbox/AppDi.kt` (`AppConfig`, `Database`, `UserRepository`, `ParcelRepository`, `PasswordHasher`, `JwtService`, `DetectionService`, `RefreshService`, `CarrierHttpClients`, `Map<CarrierId, CarrierRateLimiter>`, `CarrierRegistry`). `DetectionService` is wired to `RuleBasedDetectionService` (format rules + parallel probe over the `CarrierRegistry`, paced by the per-carrier limiters); `RefreshService` is wired to `DefaultRefreshService` (TTL-based stale refresh on `GET /api/v1/parcels`, forced refresh on `POST /api/v1/parcels/{id}/refresh`, probes capped by `postbox.refresh.maxConcurrentProbes` with per-carrier rate limiters and per-carrier request timeouts, soft `lastCheckError` on carrier failure, DELIVERED grace window; tuning via the `postbox.refresh.*` keys and `POSTBOX_REFRESH_*` env vars). A `NeedsMetadata` from the resolved carrier is not an outage: `refresh` rethrows it so `POST /api/v1/parcels/{id}/refresh` answers `409 missing-carrier-metadata` with the structured `error.fields`, and `refreshStale` reports it as `missingMetadata` on the parcel response. Resolve in app code with `val x: T by dependencies` or `dependencies.resolve<T>()`; there are no `Application.attributes` carrier keys anymore. `CarrierHttpClients` is closed automatically on server stop (DI closes `AutoCloseable` instances). A per-carrier `CarrierAdapter` registers as `provide<CarrierAdapter>(name = carrier.id)` and the `CarrierRegistry` provider collects those named bindings; in tests, provide overrides (e.g. a `FakeCarrier`) before `module(...)` inside `testApplication` — the test engine keeps the first binding for a key. The first real adapter is `DhlAdapter` (DHL Shipment Tracking - Unified API), registered only when `DHL_API_KEY` is set — without the key it is skipped and a startup warning is logged — and paced by its own `CarrierRateLimiter` built from the DHL carrier config block. `PostCttAdapter` (CTT's public object-search screenservices endpoint — no API key) is registered whenever the `postbox.carriers.ctt` config block is present (always, with the shipped defaults) and wired the same way from that block. `PaackAdapter` (the consumer tracking endpoint on `paack.co` — no credentials, requires the delivery `postcode` as parcel metadata) is registered the same way whenever the `postbox.carriers.paack` config block is present (always, with the shipped defaults). `EcoScootingAdapter` (Cainiao's public link gateway behind EcoScooting's consumer tracking page, no API key) registers the same way whenever the `postbox.carriers.ecoscooting` config block is present.
 `PaackAdapter` (the consumer tracking endpoint on `paack.co` — no credentials, requires the delivery `postcode` as parcel metadata) is registered the same way whenever the `postbox.carriers.paack` config block is present (always, with the shipped defaults). `EcoScootingAdapter` (Cainiao's public link gateway behind EcoScooting's consumer tracking page, no API key) registers the same way whenever the `postbox.carriers.ecoscooting` config block is present. `PostNlAdapter` (the consumer track & trace endpoint on `tracking.postnl.nl` — no credentials, requires the delivery `postcode` as parcel metadata, country derived from the postcode shape) registers the same way whenever the `postbox.carriers.postnl` config block is present.
 `PaackAdapter` (the consumer tracking endpoint on `paack.co` — no credentials, requires the delivery `postcode` as parcel metadata) is registered the same way whenever the `postbox.carriers.paack` config block is present (always, with the shipped defaults). `EcoScootingAdapter` (Cainiao's public link gateway behind EcoScooting's consumer tracking page, no API key) registers the same way whenever the `postbox.carriers.ecoscooting` config block is present. `DpdAdapter` (per-country DPD tracking, PT-first — PT via the `tracking.dpd.pt` HTML tracker, NL via the myDPD guest JSON API on `www.dpdgroup.com`; researched in G-71) registers the same way whenever the `postbox.carriers.dpd` config block is present (always, with the shipped defaults); the non-PT guest path additionally needs `DPD_FIREBASE_API_KEY` / `DPD_FIREBASE_ANDROID_CERT` (public myDPD app-bundle values, never committed) and degrades to `CarrierUnavailable` without them.
- Logging is structured JSON on the console: Logback + LogstashEncoder, config at `backend/src/main/resources/logback.xml`.

## Config

- Defaults live in HOCON at `backend/src/main/resources/application.conf`; env vars override them via the `${?VAR}` optional-substitution pattern merged with `ConfigFactory.systemEnvironment()` in `ConfigLoader.load` (`backend/.../Config.kt`). Overridable keys: `PORT` (default `8080`), `JWT_SECRET`, `SQLITE_PATH` (default `data/postbox.db`), `DATA_DIR` (default `data`), `POSTBOX_ENV` (default `dev`), `DHL_API_KEY` (default unset — DHL adapter disabled), `DPD_FIREBASE_API_KEY` / `DPD_FIREBASE_ANDROID_CERT` (default unset — DPD's NL guest API path unavailable, PT path unaffected), `POSTBOX_REFRESH_STALE_AFTER_MINUTES` (default `30`), `POSTBOX_REFRESH_MAX_CONCURRENT_PROBES` (default `4`), `POSTBOX_REFRESH_DELIVERED_GRACE_DAYS` (default `3`), and `POSTBOX_CARRIER_<ID>_<KEY>` per carrier. Example: `PORT=18080 ./gradlew run`.
- To add a new env-overridable key, declare the default and then a second line `key = ${?ENV_VAR}` — in HOCON the later assignment wins and `${?...}` is dropped when the var is unset.
- `JWT_SECRET` ships a dev-only insecure default in `application.conf`; it MUST be overridden via env in any real deployment. With `POSTBOX_ENV=production` (the container image sets this) the app refuses to boot on the default secret.
- Port already in use → `BindException`, logged with a hint to set `PORT` to a free port.

## Tests

- Ktor `testApplication` runs in-process (no real port — pass `port = 0` in the test config); never bind fixed ports in tests.
- `kotlin("test")` on JUnit Platform (`useJUnitPlatform()`; `testLogging` prints passed/skipped/failed). Tests write under `build/tmp/...`, never into the gitignored `data/` runtime dir.

## Dependencies

- All versions are pinned in the version catalog `gradle/libs.versions.toml`; add libraries/plugins there with `version.ref` instead of inline versions in `build.gradle.kts`.

## Git conventions

- One branch per Linear issue; include the issue key in the branch name per the pattern in `## Conventions`, e.g. `feat/G-75-follow-up-data-model-changes-for`. `research` is used for research-only branches.
- Commit messages follow Conventional Commits (see `## Conventions`); reference the Linear issue in the commit body (e.g. `Refs G-75`).

## Housekeeping

- `.gitignore` already covers `.plan/`, `.gradle/`, `build/`, `data/`, `*.db*` — never commit build outputs or the SQLite data dir.
- `.github/workflows/release.yml` runs on every push to `main`: auto-incremented `v*` tag, backend + frontend tests, GHCR image publish, GitHub Release built from `CHANGELOG.md`, and the released changelog section committed back to `main`; `.github/workflows/docker-publish.yml` still publishes manually pushed `v*` tags; `./gradlew check` is the QA gate.
