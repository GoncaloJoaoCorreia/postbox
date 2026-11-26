import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Parcel } from "../../api/client";
import { storeToken } from "../../api/tokenStorage";
import { Providers } from "../../app/AppProviders";
import { AppRoutes } from "../../app/routes";
import { mswServer } from "../../test/setup";

function renderApp() {
  storeToken("jwt");
  return render(
    <Providers>
      <MemoryRouter initialEntries={["/parcels"]}>
        <AppRoutes />
      </MemoryRouter>
    </Providers>,
  );
}

function makeParcel(overrides: Partial<Parcel>): Parcel {
  return {
    id: "p1",
    trackingNumber: "1Z999AA10123456784",
    carrier: "dhl",
    detectionMethod: "probe",
    displayName: "Shoes order",
    status: "IN_TRANSIT",
    metadata: null,
    missingMetadata: null,
    lastCheckedAt: "2026-11-26T10:00:00Z",
    lastCheckError: null,
    createdAt: "2026-11-26T09:00:00Z",
    archivedAt: null,
    latestEvent: {
      id: "e1",
      occurredAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      status: "IN_TRANSIT",
      description: "Arrived at sorting center",
      location: null,
    },
    ...overrides,
  };
}

describe("parcel list screen", () => {
  it("groups parcels by status with carrier badge, last event and relative time, and filters by search", async () => {
    mswServer.use(
      http.get("*/api/v1/parcels", () =>
        HttpResponse.json([
          makeParcel({
            id: "p1",
            displayName: "Shoes order",
            status: "IN_TRANSIT",
            carrier: "dhl",
          }),
          makeParcel({
            id: "p2",
            displayName: "Books",
            status: "DELIVERED",
            carrier: "ctt",
            trackingNumber: "CT000111222PT",
            latestEvent: {
              id: "e2",
              occurredAt: new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString(),
              status: "DELIVERED",
              description: "Delivered to recipient",
              location: null,
            },
          }),
          makeParcel({
            id: "p3",
            displayName: "Lamp",
            status: "EXCEPTION",
            carrier: "postnl",
            trackingNumber: "PQ999888",
            latestEvent: {
              id: "e3",
              occurredAt: new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString(),
              status: "EXCEPTION",
              description: "Held at customs",
              location: null,
            },
          }),
          makeParcel({
            id: "p4",
            displayName: "Old gadget",
            status: "IN_TRANSIT",
            archivedAt: "2026-11-20T00:00:00Z",
          }),
        ]),
      ),
    );
    renderApp();
    expect(await screen.findByRole("heading", { name: /in transit/i })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /delivered/i })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /exception/i })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /archived/i })).toBeInTheDocument();
    expect(screen.getAllByText("DHL").length).toBeGreaterThan(0);
    expect(screen.getAllByText(/arrived at sorting center/i).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/5 min ago/).length).toBeGreaterThan(0);
    expect(screen.getByText(/2 h ago/)).toBeInTheDocument();
    expect(screen.getByText(/3 d ago/)).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/search parcels/i), "books");
    expect(screen.getByText("Books")).toBeInTheDocument();
    expect(screen.queryByText("Shoes order")).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText(/search parcels/i));
    await user.type(screen.getByLabelText(/search parcels/i), "ct000111222pt");
    expect(screen.getByText("Books")).toBeInTheDocument();
    expect(screen.queryByText("Lamp")).not.toBeInTheDocument();
  });

  it("add-parcel flow: probes detection, shows the resolved carrier, allows override, saves and refreshes the list", async () => {
    let detectBody: unknown = null;
    let createBody: unknown = null;
    let listCalls = 0;
    const created = makeParcel({
      id: "p9",
      displayName: "Shoes order",
      carrier: "ctt",
      detectionMethod: "override",
      status: "UNKNOWN",
      latestEvent: null,
    });
    mswServer.use(
      http.get("*/api/v1/parcels", () => {
        listCalls += 1;
        return HttpResponse.json(listCalls > 1 ? [created] : []);
      }),
      http.post("*/api/v1/parcels/detect", async ({ request }) => {
        detectBody = await request.json();
        await new Promise((resolve) => setTimeout(resolve, 100));
        return HttpResponse.json({
          trackingNumber: "1Z999AA10123456784",
          carrier: "dhl",
          method: "probe",
          candidates: ["dhl", "ctt"],
        });
      }),
      http.post("*/api/v1/parcels", async ({ request }) => {
        createBody = await request.json();
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /add parcel/i }));
    const dialog = await screen.findByRole("region", { name: /add parcel/i });
    await user.type(within(dialog).getByLabelText(/tracking number/i), "1Z999AA10123456784");
    await user.type(within(dialog).getByLabelText(/name \(optional\)/i), "Shoes order");
    await user.click(within(dialog).getByRole("button", { name: /detect carrier/i }));
    expect(await within(dialog).findByRole("status")).toHaveTextContent(
      /probing candidate carriers/i,
    );
    expect(await within(dialog).findByText(/carrier detected/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/candidates: dhl, ctt/i)).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText(/^carrier$/i), "ctt");
    await user.click(within(dialog).getByRole("button", { name: /save parcel/i }));
    expect(await screen.findByText("Shoes order")).toBeInTheDocument();
    expect(detectBody).toEqual({ trackingNumber: "1Z999AA10123456784" });
    expect(createBody).toEqual({
      trackingNumber: "1Z999AA10123456784",
      displayName: "Shoes order",
      carrier: "ctt",
    });
    expect(listCalls).toBeGreaterThan(1);
  });

  it("saves an unresolved detection with the top candidate marked pending confirmation", async () => {
    let createBody: unknown = null;
    mswServer.use(
      http.post("*/api/v1/parcels/detect", async ({ request }) => {
        const body = (await request.json()) as { trackingNumber: string };
        return HttpResponse.json({
          trackingNumber: body.trackingNumber,
          carrier: "ctt",
          method: "rule",
          candidates: ["ctt", "dhl"],
        });
      }),
      http.post("*/api/v1/parcels", async ({ request }) => {
        createBody = await request.json();
        return HttpResponse.json(makeParcel({ id: "p9", carrier: "ctt" }), { status: 201 });
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /add parcel/i }));
    await user.type(screen.getByLabelText(/tracking number/i), "QQ123456");
    await user.click(screen.getByRole("button", { name: /detect carrier/i }));
    expect(await screen.findByText(/pending confirmation/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /save parcel/i }));
    await waitFor(() => expect(createBody).toEqual({ trackingNumber: "QQ123456", carrier: "ctt" }));
  });

  it("deletes a parcel only after confirmation", async () => {
    let deletedId: string | null = null;
    let listCalls = 0;
    mswServer.use(
      http.get("*/api/v1/parcels", () => {
        listCalls += 1;
        return HttpResponse.json(listCalls > 1 ? [] : [makeParcel({ id: "p1" })]);
      }),
      http.delete("*/api/v1/parcels/:id", ({ params }) => {
        deletedId = params.id as string;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /^delete$/i }));
    expect(screen.getByText(/delete this parcel and its events/i)).toBeInTheDocument();
    expect(deletedId).toBeNull();
    await user.click(screen.getByRole("button", { name: /confirm delete/i }));
    await waitFor(() => expect(deletedId).toBe("p1"));
    expect(await screen.findByText(/no parcels found/i)).toBeInTheDocument();
  });

  it("prompts inline for missing carrier metadata, validates, saves metadata and refreshes", async () => {
    const parcel = makeParcel({
      id: "p9",
      carrier: "postnl",
      missingMetadata: [
        {
          name: "postcode",
          label: "Recipient postcode",
          hint: "The 4 digits of your postcode",
          validationRegex: "\\d{4}",
        },
      ],
    });
    let patchBody: unknown = null;
    let refreshed = false;
    mswServer.use(
      http.get("*/api/v1/parcels", () =>
        HttpResponse.json([refreshed ? { ...parcel, missingMetadata: null } : parcel]),
      ),
      http.patch("*/api/v1/parcels/p9", async ({ request }) => {
        patchBody = await request.json();
        return HttpResponse.json({ ...parcel, metadata: { postcode: "1234" } });
      }),
      http.post("*/api/v1/parcels/p9/refresh", () => {
        refreshed = true;
        return HttpResponse.json({
          ...parcel,
          missingMetadata: null,
          metadata: { postcode: "1234" },
        });
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /add required details/i }));
    await user.type(screen.getByLabelText(/recipient postcode/i), "12");
    await user.click(screen.getByRole("button", { name: /save details/i }));
    expect(await screen.findByText(/expected format/i)).toBeInTheDocument();
    expect(patchBody).toBeNull();
    await user.clear(screen.getByLabelText(/recipient postcode/i));
    await user.type(screen.getByLabelText(/recipient postcode/i), "1234");
    await user.click(screen.getByRole("button", { name: /save details/i }));
    await waitFor(() => expect(patchBody).toEqual({ metadata: { postcode: "1234" } }));
    await waitFor(() => expect(refreshed).toBe(true));
    await waitFor(() =>
      expect(screen.queryByLabelText(/recipient postcode/i)).not.toBeInTheDocument(),
    );
  });

  it("renames a parcel and overrides its carrier through the edit form", async () => {
    let patchBody: unknown = null;
    mswServer.use(
      http.get("*/api/v1/parcels", () =>
        HttpResponse.json([makeParcel({ id: "p1", carrier: "dhl" })]),
      ),
      http.patch("*/api/v1/parcels/p1", async ({ request }) => {
        patchBody = await request.json();
        return HttpResponse.json(makeParcel({ id: "p1", displayName: "New name", carrier: "dpd" }));
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    await user.clear(screen.getByLabelText(/^name$/i));
    await user.type(screen.getByLabelText(/^name$/i), "New name");
    await user.selectOptions(screen.getByLabelText(/^carrier$/i), "dpd");
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(patchBody).toEqual({ displayName: "New name", carrier: "dpd" }));
  });
});
