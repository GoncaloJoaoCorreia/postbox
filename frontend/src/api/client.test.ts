import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { mswServer } from "../test/setup";
import { ApiError, createApiClient } from "./client";

describe("createApiClient", () => {
  it("attaches the bearer token from getToken on every request", async () => {
    let seen: string | null = null;
    mswServer.use(
      http.get("*/api/v1/me", ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({
          id: "u1",
          email: "a@b.c",
          displayName: "A",
          role: "USER",
          isActive: true,
          createdAt: "2026-01-01T00:00:00Z",
        });
      }),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => "jwt-123" });
    await client.getMe();
    expect(seen).toBe("Bearer jwt-123");
  });

  it("sends no Authorization header when there is no token", async () => {
    let seen: string | null = "unset";
    mswServer.use(
      http.get("*/api/v1/setup/status", ({ request }) => {
        seen = request.headers.get("Authorization");
        return HttpResponse.json({ setupPending: true });
      }),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => null });
    await client.getSetupStatus();
    expect(seen).toBeNull();
  });

  it("maps the API error envelope to ApiError with status, code and message", async () => {
    mswServer.use(
      http.post("*/api/v1/auth/login", () =>
        HttpResponse.json(
          { error: { code: "invalid-credentials", message: "Invalid email or password" } },
          { status: 401 },
        ),
      ),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => null });
    const failure = await client.login("a@b.c", "wrong").catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(401);
    expect(apiError.code).toBe("invalid-credentials");
    expect(apiError.message).toBe("Invalid email or password");
  });

  it("returns undefined for 204 responses", async () => {
    mswServer.use(
      http.delete("*/api/v1/parcels/abc", () => new HttpResponse(null, { status: 204 })),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => "t" });
    await expect(client.deleteParcel("abc")).resolves.toBeUndefined();
  });

  it("invokes onUnauthorized on a 401 from a protected endpoint", async () => {
    mswServer.use(
      http.get("*/api/v1/parcels", () =>
        HttpResponse.json(
          { error: { code: "unauthorized", message: "Missing or invalid token" } },
          { status: 401 },
        ),
      ),
    );
    let calls = 0;
    const client = createApiClient({
      baseUrl: "",
      getToken: () => "expired-jwt",
      onUnauthorized: () => {
        calls += 1;
      },
    });
    const failure = await client.listParcels().catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    expect(calls).toBe(1);
  });

  it("does not invoke onUnauthorized for a 401 from the login endpoint", async () => {
    mswServer.use(
      http.post("*/api/v1/auth/login", () =>
        HttpResponse.json(
          { error: { code: "invalid-credentials", message: "Invalid email or password" } },
          { status: 401 },
        ),
      ),
    );
    let calls = 0;
    const client = createApiClient({
      baseUrl: "",
      getToken: () => null,
      onUnauthorized: () => {
        calls += 1;
      },
    });
    await client.login("a@b.c", "wrong").catch(() => undefined);
    expect(calls).toBe(0);
  });

  it("falls back to a status-based error when the body is not the envelope", async () => {
    mswServer.use(http.get("*/api/v1/parcels", () => new HttpResponse("boom", { status: 500 })));
    const client = createApiClient({ baseUrl: "", getToken: () => "t" });
    const failure = await client.listParcels().catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(500);
    expect(apiError.code).toBe("request-failed");
  });

  it("posts to the detect endpoint and returns the detection outcome", async () => {
    let body: unknown = null;
    mswServer.use(
      http.post("*/api/v1/parcels/detect", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({
          trackingNumber: "1Z999AA10123456784",
          carrier: "dhl",
          method: "probe",
          candidates: ["dhl", "ctt"],
        });
      }),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => "t" });
    const result = await client.detectCarrier({ trackingNumber: "1Z999AA10123456784" });
    expect(body).toEqual({ trackingNumber: "1Z999AA10123456784" });
    expect(result.carrier).toBe("dhl");
    expect(result.method).toBe("probe");
    expect(result.candidates).toEqual(["dhl", "ctt"]);
  });

  it("exposes error.fields on a 409 missing-carrier-metadata refresh response", async () => {
    mswServer.use(
      http.post("*/api/v1/parcels/p1/refresh", () =>
        HttpResponse.json(
          {
            error: {
              code: "missing-carrier-metadata",
              message: "The carrier requires additional metadata: postcode",
              fields: [
                {
                  name: "postcode",
                  label: "Recipient postcode",
                  hint: "The 4 digits of your postcode",
                  validationRegex: "\\d{4}",
                },
              ],
            },
          },
          { status: 409 },
        ),
      ),
    );
    const client = createApiClient({ baseUrl: "", getToken: () => "t" });
    const failure = await client.refreshParcel("p1").catch((e: unknown) => e);
    expect(failure).toBeInstanceOf(ApiError);
    const apiError = failure as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe("missing-carrier-metadata");
    expect(apiError.fields?.[0]?.name).toBe("postcode");
    expect(apiError.fields?.[0]?.validationRegex).toBe("\\d{4}");
  });
});
