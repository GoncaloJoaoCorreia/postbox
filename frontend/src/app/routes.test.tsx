import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { getStoredToken, storeToken } from "../api/tokenStorage";
import { mswServer } from "../test/setup";
import { Providers } from "./AppProviders";
import { AppRoutes } from "./routes";

function renderApp(initialEntries: string[]) {
  return render(
    <Providers>
      <MemoryRouter initialEntries={initialEntries}>
        <AppRoutes />
      </MemoryRouter>
    </Providers>,
  );
}

describe("app routing", () => {
  it("redirects unauthenticated users from /parcels to the login screen", async () => {
    renderApp(["/parcels"]);
    expect(await screen.findByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /your parcels/i })).not.toBeInTheDocument();
  });

  it("shows the parcel list area when a token is stored", async () => {
    storeToken("jwt");
    renderApp(["/parcels"]);
    expect(await screen.findByRole("heading", { name: /your parcels/i })).toBeInTheDocument();
  });

  it("round-trips a login error from the API error envelope", async () => {
    mswServer.use(
      http.post("*/api/v1/auth/login", () =>
        HttpResponse.json(
          { error: { code: "invalid-credentials", message: "Invalid email or password" } },
          { status: 401 },
        ),
      ),
    );
    renderApp(["/login"]);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), "a@b.c");
    await user.type(screen.getByLabelText(/password/i), "wrong-password");
    await user.click(screen.getByRole("button", { name: /sign in/i }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(getStoredToken()).toBeNull();
  });

  it("stores the token and navigates to the parcel list on successful login", async () => {
    mswServer.use(
      http.post("*/api/v1/auth/login", async ({ request }) => {
        const body = (await request.json()) as { email: string; password: string };
        if (body.email === "a@b.c" && body.password === "correct-password") {
          return HttpResponse.json({ token: "jwt-abc" });
        }
        return HttpResponse.json(
          { error: { code: "invalid-credentials", message: "Invalid email or password" } },
          { status: 401 },
        );
      }),
    );
    renderApp(["/login"]);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), "a@b.c");
    await user.type(screen.getByLabelText(/password/i), "correct-password");
    await user.click(screen.getByRole("button", { name: /sign in/i }));
    expect(await screen.findByRole("heading", { name: /your parcels/i })).toBeInTheDocument();
    expect(getStoredToken()).toBe("jwt-abc");
  });
});

describe("first-run setup", () => {
  it("routes every screen to the setup wizard while no admin exists", async () => {
    mswServer.use(
      http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: true })),
    );
    renderApp(["/parcels"]);
    expect(await screen.findByRole("heading", { name: /set up postbox/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /sign in/i })).not.toBeInTheDocument();
  });

  it("creates the first admin, lands on login, and never offers the wizard again", async () => {
    let setupBody: unknown = null;
    mswServer.use(
      http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: true })),
      http.post("*/api/v1/setup", async ({ request }) => {
        setupBody = await request.json();
        return HttpResponse.json(
          {
            id: "u1",
            email: "admin@postbox.dev",
            displayName: "admin",
            role: "ADMIN",
            isActive: true,
            createdAt: "2026-01-01T00:00:00Z",
          },
          { status: 201 },
        );
      }),
    );
    renderApp(["/setup"]);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/email/i), "admin@postbox.dev");
    await user.type(screen.getByLabelText(/password/i), "supersecret1");
    await user.click(screen.getByRole("button", { name: /create admin account/i }));
    expect(await screen.findByRole("button", { name: /sign in/i })).toBeInTheDocument();
    expect(setupBody).toEqual({ email: "admin@postbox.dev", password: "supersecret1" });

    mswServer.use(
      http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: false })),
    );
    renderApp(["/setup"]);
    expect(await screen.findByRole("button", { name: /sign in/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /create admin account/i })).not.toBeInTheDocument();
  });

  it("validates the setup form inline and sends no request", async () => {
    let posted = false;
    mswServer.use(
      http.get("*/api/v1/setup/status", () => HttpResponse.json({ setupPending: true })),
      http.post("*/api/v1/setup", () => {
        posted = true;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    renderApp(["/setup"]);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/email/i), "not-an-email");
    await user.type(screen.getByLabelText(/password/i), "short");
    await user.click(screen.getByRole("button", { name: /create admin account/i }));
    expect(await screen.findByText(/valid email address/i)).toBeInTheDocument();
    expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(posted).toBe(false);
  });
});

describe("login screen validation", () => {
  it("shows inline errors and sends no request when the fields are invalid", async () => {
    renderApp(["/login"]);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /sign in/i }));
    expect(await screen.findByText(/valid email address/i)).toBeInTheDocument();
    expect(screen.getByText(/enter your password/i)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("session expiry", () => {
  it("drops the stored token and returns to login when the API answers 401", async () => {
    storeToken("expired-jwt");
    mswServer.use(
      http.get("*/api/v1/setup/status", () =>
        HttpResponse.json(
          { error: { code: "unauthorized", message: "Missing or invalid token" } },
          { status: 401 },
        ),
      ),
    );
    renderApp(["/parcels"]);
    expect(await screen.findByLabelText(/email/i)).toBeInTheDocument();
    expect(getStoredToken()).toBeNull();
  });
});
