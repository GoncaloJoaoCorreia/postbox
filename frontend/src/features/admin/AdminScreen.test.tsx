import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { User } from "../../api/client";
import { storeToken } from "../../api/tokenStorage";
import { Providers } from "../../app/AppProviders";
import { AppRoutes } from "../../app/routes";
import { mswServer } from "../../test/setup";

function renderApp(initialEntries: string[]) {
  storeToken("jwt");
  return render(
    <Providers>
      <MemoryRouter initialEntries={initialEntries}>
        <AppRoutes />
      </MemoryRouter>
    </Providers>,
  );
}

function makeUser(overrides: Partial<User>): User {
  return {
    id: "u1",
    email: "admin@postbox.dev",
    displayName: "Admin",
    role: "ADMIN",
    isActive: true,
    createdAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

const adminMe = makeUser({});

const sampleUsers = () => [
  makeUser({ id: "u1", email: "admin@postbox.dev", displayName: "Admin", role: "ADMIN" }),
  makeUser({
    id: "u2",
    email: "ana@example.com",
    displayName: "Ana",
    role: "USER",
    isActive: false,
    createdAt: "2026-02-10T12:30:00Z",
  }),
];

function mockAdminMe() {
  mswServer.use(http.get("*/api/v1/me", () => HttpResponse.json(adminMe)));
}

function rowOf(email: string): HTMLElement {
  const row = screen.getByText(email).closest("li");
  if (!row) throw new Error(`no row for ${email}`);
  return row as HTMLElement;
}

describe("admin screen", () => {
  it("shows the Admin link in the top navigation and lists users with email, name, role, status and created date", async () => {
    mockAdminMe();
    mswServer.use(http.get("*/api/v1/admin/users", () => HttpResponse.json(sampleUsers())));
    renderApp(["/admin"]);
    expect(await screen.findByRole("heading", { name: /^users$/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Admin" })).toBeInTheDocument();
    expect(await screen.findByText("ana@example.com")).toBeInTheDocument();
    const anaRow = rowOf("ana@example.com");
    expect(within(anaRow).getByText("Ana")).toBeInTheDocument();
    expect(within(anaRow).getByText("User")).toBeInTheDocument();
    expect(within(anaRow).getByText("Deactivated")).toBeInTheDocument();
    expect(within(anaRow).getByText("10 Feb 2026")).toBeInTheDocument();
    const adminRow = rowOf("admin@postbox.dev");
    expect(within(adminRow).getAllByText("Admin").length).toBeGreaterThan(0);
    expect(within(adminRow).getByText("Active")).toBeInTheDocument();
    expect(within(adminRow).getByText("You")).toBeInTheDocument();
  });

  it("hides the admin section from non-admins and never calls the admin endpoints", async () => {
    let adminCalls = 0;
    mswServer.use(
      http.get("*/api/v1/admin/users", () => {
        adminCalls += 1;
        return HttpResponse.json(
          { error: { code: "admin-required", message: "An admin account is required" } },
          { status: 403 },
        );
      }),
    );
    renderApp(["/admin"]);
    expect(await screen.findByRole("heading", { name: /your parcels/i })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^users$/i })).not.toBeInTheDocument();
    expect(adminCalls).toBe(0);
  });

  it("creates a user with a generated password, reveals it once with a copy button and refreshes the list", async () => {
    mockAdminMe();
    let createBody: unknown = null;
    let listCalls = 0;
    const created = makeUser({
      id: "u3",
      email: "bruno@example.com",
      displayName: "Bruno",
      role: "USER",
    });
    mswServer.use(
      http.get("*/api/v1/admin/users", () => {
        listCalls += 1;
        return HttpResponse.json(listCalls > 1 ? [...sampleUsers(), created] : sampleUsers());
      }),
      http.post("*/api/v1/admin/users", async ({ request }) => {
        createBody = await request.json();
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    renderApp(["/admin"]);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "+ Add user" }));
    const panel = await screen.findByRole("region", { name: /add user/i });
    await user.type(within(panel).getByLabelText(/^email$/i), "bruno@example.com");
    await user.type(within(panel).getByLabelText(/display name/i), "Bruno");
    await user.click(within(panel).getByRole("button", { name: /generate a password/i }));
    const password = (within(panel).getByLabelText(/initial password/i) as HTMLInputElement).value;
    expect(password.length).toBeGreaterThanOrEqual(8);
    await user.click(within(panel).getByRole("button", { name: /create user/i }));
    expect(await screen.findByText(/shown once/i)).toBeInTheDocument();
    expect(createBody).toEqual({
      email: "bruno@example.com",
      password,
      displayName: "Bruno",
      role: "USER",
    });
    expect(screen.getByText(password)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^copy$/i })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /dismiss/i }));
    expect(screen.queryByText(password)).not.toBeInTheDocument();
    expect(await screen.findByText("bruno@example.com")).toBeInTheDocument();
    expect(listCalls).toBeGreaterThan(1);
  });

  it("validates the create form inline and maps a duplicate email to the email field", async () => {
    mockAdminMe();
    let posted = false;
    mswServer.use(
      http.get("*/api/v1/admin/users", () => HttpResponse.json(sampleUsers())),
      http.post("*/api/v1/admin/users", async () => {
        posted = true;
        return HttpResponse.json(
          {
            error: {
              code: "email-already-used",
              message: "An account with this email already exists",
            },
          },
          { status: 409 },
        );
      }),
    );
    renderApp(["/admin"]);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "+ Add user" }));
    const panel = await screen.findByRole("region", { name: /add user/i });
    await user.click(within(panel).getByRole("button", { name: /create user/i }));
    expect(await within(panel).findByText(/valid email address/i)).toBeInTheDocument();
    expect(within(panel).getByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(posted).toBe(false);
    await user.type(within(panel).getByLabelText(/^email$/i), "ana@example.com");
    await user.type(within(panel).getByLabelText(/initial password/i), "supersecret1");
    await user.click(within(panel).getByRole("button", { name: /create user/i }));
    expect(
      await within(panel).findByText(/account with this email already exists/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/shown once/i)).not.toBeInTheDocument();
  });

  it("deactivates another user only after confirmation, keeps the own account locked, and reactivates directly", async () => {
    mockAdminMe();
    let users = sampleUsers();
    const patches: { id: unknown; body: unknown }[] = [];
    mswServer.use(
      http.get("*/api/v1/admin/users", () => HttpResponse.json(users)),
      http.patch("*/api/v1/admin/users/:id", async ({ request, params }) => {
        const body = (await request.json()) as { isActive?: boolean };
        patches.push({ id: params.id, body });
        users = users.map((u) =>
          u.id === params.id ? { ...u, isActive: body.isActive ?? u.isActive } : u,
        );
        return HttpResponse.json(users.find((u) => u.id === params.id));
      }),
    );
    renderApp(["/admin"]);
    const user = userEvent.setup();
    await screen.findByText("ana@example.com");
    const ownDeactivate = within(rowOf("admin@postbox.dev")).getByRole("button", {
      name: /^deactivate$/i,
    });
    expect(ownDeactivate).toBeDisabled();
    expect(
      within(rowOf("admin@postbox.dev")).queryByRole("button", { name: /reset password/i }),
    ).not.toBeInTheDocument();

    await user.click(within(rowOf("ana@example.com")).getByRole("button", { name: /reactivate/i }));
    await waitFor(() => expect(patches).toContainEqual({ id: "u2", body: { isActive: true } }));

    await user.click(
      await within(rowOf("ana@example.com")).findByRole("button", { name: /^deactivate$/i }),
    );
    expect(screen.getByText(/they will not be able to sign in/i)).toBeInTheDocument();
    expect(patches).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: /confirm deactivate/i }));
    await waitFor(() => expect(patches).toContainEqual({ id: "u2", body: { isActive: false } }));
  });

  it("resets another user's password after inline validation and reveals the new password once", async () => {
    mockAdminMe();
    const patches: { id: unknown; body: unknown }[] = [];
    mswServer.use(
      http.get("*/api/v1/admin/users", () => HttpResponse.json(sampleUsers())),
      http.patch("*/api/v1/admin/users/:id", async ({ request, params }) => {
        const body = await request.json();
        patches.push({ id: params.id, body });
        return HttpResponse.json(sampleUsers().find((u) => u.id === params.id));
      }),
    );
    renderApp(["/admin"]);
    const user = userEvent.setup();
    await screen.findByText("ana@example.com");
    const anaRow = rowOf("ana@example.com");
    await user.click(within(anaRow).getByRole("button", { name: /reset password/i }));
    const passwordInput = within(anaRow).getByLabelText(/new password/i);
    await user.type(passwordInput, "short");
    await user.click(within(anaRow).getByRole("button", { name: /confirm reset/i }));
    expect(await within(anaRow).findByText(/at least 8 characters/i)).toBeInTheDocument();
    expect(patches).toHaveLength(0);
    await user.click(within(anaRow).getByRole("button", { name: /generate a password/i }));
    const newPassword = (within(anaRow).getByLabelText(/new password/i) as HTMLInputElement).value;
    await user.click(within(anaRow).getByRole("button", { name: /confirm reset/i }));
    await waitFor(() => expect(patches).toContainEqual({ id: "u2", body: { newPassword } }));
    expect(await screen.findByText(newPassword)).toBeInTheDocument();
    expect(screen.getByText(/new password for/i)).toBeInTheDocument();
  });
});
