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
    expect(screen.queryByRole("heading", { name: "Parcels" })).not.toBeInTheDocument();
  });

  it("shows the parcel list area when a token is stored", async () => {
    storeToken("jwt");
    renderApp(["/parcels"]);
    expect(await screen.findByRole("heading", { name: "Parcels" })).toBeInTheDocument();
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
    expect(await screen.findByRole("heading", { name: "Parcels" })).toBeInTheDocument();
    expect(getStoredToken()).toBe("jwt-abc");
  });
});
