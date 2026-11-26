# frontend

The postbox single-page app: Vite + React 19 + TypeScript (strict), ESLint + Prettier
from the start.

## Layout

- `src/main.tsx` — composition root: `<AppProviders>` (TanStack Query + API client +
  auth, plus the router) around `<AppRoutes>`
- `src/app/` — routes, the `SetupGate` (while `GET /api/v1/setup/status` reports
  `setupPending`, every route redirects to the `/setup` first-run wizard, which is
  unreachable once an admin exists) and the `RequireAuth` guard (unauthenticated users
  redirect to `/login`); areas: `/setup`, `/login` + `/register`, `/parcels`, `/parcels/:id`
- `src/api/` — `schema.d.ts` (generated from `../docs/openapi.yaml` via
  `openapi-typescript`), `client.ts` (`createApiClient({ baseUrl, getToken, onUnauthorized })`:
  bearer injection + typed `{"error": {"code", "message"}}` envelope handling → `ApiError`,
  and a `401` from any non-login route fires `onUnauthorized`), `ApiContext.tsx`
  (`useApi()`, `onUnauthorized()` listener registry), `tokenStorage.ts` (token in localStorage)
- `src/auth/` — `AuthContext.tsx` (token state, `login`/`logout`; registers `logout` as the
  `onUnauthorized` listener so any `401` drops the token and returns to `/login`)
- `src/features/` — screens per route area: `auth/` (login, register, first-run setup) and
  `parcels/` (list screen with app shell, summary cards, search and status filters; the
  add-parcel panel with carrier detection progress and manual override; edit/delete on
  existing parcels; the inline carrier-metadata prompt)

## Commands

```sh
npm install
npm run gen:api      # regenerate src/api/schema.d.ts from ../docs/openapi.yaml
npm run dev          # Vite dev server, proxies /api to the Ktor backend on :8080
npm run build        # tsc -b && vite build -> dist/
npm run typecheck    # tsc -b
npm run lint         # eslint
npm run format       # prettier --check .
npm test             # vitest (jsdom + MSW mocking /api/v1)
```

`VITE_BACKEND_URL` overrides the dev proxy target (default `http://localhost:8080`).

## Dev vs prod

- Dev: run the backend (`./gradlew run` from the repo root) and `npm run dev` here;
  the SPA calls `/api/...` on the Vite origin and Vite proxies it to Ktor.
- Prod: `npm run build`, then start the backend — `:backend:processResources` copies
  `frontend/dist` onto the classpath as `web/` and Ktor serves it at `/` with SPA
  history-mode fallback to `index.html` (API paths keep their JSON 404s). No separate
  web container.
