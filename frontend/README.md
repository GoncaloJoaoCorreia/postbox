# frontend

The postbox single-page app: Vite + React 19 + TypeScript (strict), ESLint + Prettier
from the start.

## Layout

- `src/main.tsx` — composition root: `<AppProviders>` (TanStack Query + API client +
  auth, plus the router) around `<AppRoutes>`
- `src/app/` — routes and the `RequireAuth` guard (unauthenticated users redirect to
  `/login`); three areas: `/login` + `/register`, `/parcels`, `/parcels/:id`
- `src/api/` — `schema.d.ts` (generated from `../docs/openapi.yaml` via
  `openapi-typescript`), `client.ts` (`createApiClient({ baseUrl, getToken })`: bearer
  injection + typed `{"error": {"code", "message"}}` envelope handling → `ApiError`),
  `ApiContext.tsx` (`useApi()`), `tokenStorage.ts` (token in localStorage)
- `src/auth/` — `AuthContext.tsx` (token state, `login`/`logout`)
- `src/features/` — screens per route area

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
