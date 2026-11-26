import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { afterAll, afterEach, beforeAll } from "vitest";

export const mswServer = setupServer(
  http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: false })),
);

beforeAll(() => mswServer.listen({ onUnhandledFrame: "error" }));
afterEach(() => {
  cleanup();
  mswServer.resetHandlers();
  localStorage.clear();
});
afterAll(() => mswServer.close());
