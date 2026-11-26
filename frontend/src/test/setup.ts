import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";
import { setupServer } from "msw/node";

export const mswServer = setupServer();

beforeAll(() => mswServer.listen({ onUnhandledFrame: "error" }));
afterEach(() => {
  cleanup();
  mswServer.resetHandlers();
  localStorage.clear();
});
afterAll(() => mswServer.close());
