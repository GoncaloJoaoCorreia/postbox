import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll } from "vitest";

export const mswServer = setupServer(
  http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: false })),
  http.get("*/api/v1/parcels", () => HttpResponse.json([])),
  http.get("*/api/v1/me", () =>
    HttpResponse.json({
      id: "u1",
      email: "a@b.c",
      displayName: "Alex Morgan",
      role: "USER",
      isActive: true,
      createdAt: "2026-01-01T00:00:00Z",
    }),
  ),
);

beforeAll(() => mswServer.listen({ onUnhandledFrame: "error" }));
afterEach(() => {
  cleanup();
  mswServer.resetHandlers();
  localStorage.clear();
});
afterAll(() => mswServer.close());
