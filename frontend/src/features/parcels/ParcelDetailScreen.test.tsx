import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import type { Parcel, ParcelEvent, TrackingStatus } from "../../api/client";
import { storeToken } from "../../api/tokenStorage";
import { Providers } from "../../app/AppProviders";
import { AppRoutes } from "../../app/routes";
import { mswServer } from "../../test/setup";

function renderDetail(id: string) {
  storeToken("jwt");
  return render(
    <Providers>
      <MemoryRouter initialEntries={[`/parcels/${id}`]}>
        <AppRoutes />
      </MemoryRouter>
    </Providers>,
  );
}

function makeParcel(overrides: Partial<Parcel>): Parcel {
  return {
    id: "p1",
    trackingNumber: "JD014600006281",
    carrier: "dhl",
    detectionMethod: "probe",
    displayName: "Mechanical keyboard",
    status: "OUT_FOR_DELIVERY",
    metadata: null,
    missingMetadata: null,
    lastCheckedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
    lastCheckError: null,
    estimatedDeliveryAt: null,
    createdAt: "2026-11-26T09:00:00Z",
    archivedAt: null,
    latestEvent: null,
    ...overrides,
  };
}

function makeEvent(overrides: Partial<ParcelEvent>): ParcelEvent {
  return {
    id: "e1",
    occurredAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    status: "IN_TRANSIT",
    description: "Arrived at sorting center",
    location: null,
    ...overrides,
  };
}

function etaTodayAt(hour: number): string {
  const d = new Date();
  d.setHours(hour, 0, 0, 0);
  return d.toISOString();
}

describe("parcel detail screen", () => {
  it("renders the summary header and the event timeline, newest first", async () => {
    const now = Date.now();
    const events = [
      makeEvent({
        id: "e3",
        occurredAt: new Date(now - 30 * 60 * 1000).toISOString(),
        status: "OUT_FOR_DELIVERY",
        description: "Out for delivery",
        location: "Porto, Portugal",
      }),
      makeEvent({
        id: "e2",
        occurredAt: new Date(now - 3 * 60 * 60 * 1000).toISOString(),
        status: "IN_TRANSIT",
        description: "Arrived at delivery facility",
        location: "Porto, Portugal",
      }),
      makeEvent({
        id: "e1",
        occurredAt: new Date(now - 26 * 60 * 60 * 1000).toISOString(),
        status: "PENDING",
        description: "Shipment picked up",
        location: "Lisbon, Portugal",
      }),
    ];
    const parcel = makeParcel({
      status: "OUT_FOR_DELIVERY",
      estimatedDeliveryAt: etaTodayAt(18),
      latestEvent: events[0],
    });
    mswServer.use(
      http.get("*/api/v1/parcels/p1", () => HttpResponse.json(parcel)),
      http.get("*/api/v1/parcels/p1/events", () => HttpResponse.json(events)),
    );
    renderDetail("p1");

    expect(await screen.findByRole("heading", { name: "Mechanical keyboard" })).toBeInTheDocument();
    expect(screen.getByText("JD014600006281")).toBeInTheDocument();
    expect(screen.getByText("DHL")).toBeInTheDocument();
    expect(screen.getAllByText("Out for delivery").length).toBeGreaterThan(0);
    expect(screen.getByText(/Estimated delivery: Today by 18:00/)).toBeInTheDocument();
    expect(screen.getByText(/Carrier detected automatically/)).toBeInTheDocument();
    expect(screen.getByText(/Last checked 5 min ago/)).toBeInTheDocument();
    const viewOn = screen.getByRole("link", { name: /View on DHL/i });
    expect(viewOn).toHaveAttribute(
      "href",
      "https://www.dhl.com/global-en/home/tracking.html?tracking-id=JD014600006281",
    );
    expect(viewOn).toHaveAttribute("target", "_blank");
    expect(viewOn).toHaveAttribute("rel", expect.stringContaining("noreferrer"));

    expect(await screen.findByRole("heading", { name: /tracking history/i })).toBeInTheDocument();
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveTextContent("Out for delivery");
    expect(items[0]).toHaveTextContent("Porto, Portugal");
    expect(items[1]).toHaveTextContent("Arrived at delivery facility");
    expect(items[2]).toHaveTextContent("Shipment picked up");
    expect(items[2]).toHaveTextContent("Lisbon, Portugal");
  });

  it("refresh shows a pending spinner, posts to the refresh endpoint, and updates the timeline with the soft-error state", async () => {
    let refreshCalls = 0;
    let eventsCalls = 0;
    let currentParcel = makeParcel({ latestEvent: null });
    let currentEvents: ParcelEvent[] = [];
    mswServer.use(
      http.get("*/api/v1/parcels/p1", () => HttpResponse.json(currentParcel)),
      http.get("*/api/v1/parcels/p1/events", () => {
        eventsCalls += 1;
        return HttpResponse.json(currentEvents);
      }),
      http.post("*/api/v1/parcels/p1/refresh", async () => {
        refreshCalls += 1;
        await new Promise((resolve) => setTimeout(resolve, 100));
        currentParcel = {
          ...currentParcel,
          status: "DELIVERED",
          lastCheckedAt: new Date().toISOString(),
          lastCheckError: "carrier unreachable, data from 2026-11-26T15:00:00.000Z",
        };
        currentEvents = [
          makeEvent({
            id: "e9",
            occurredAt: new Date().toISOString(),
            status: "DELIVERED",
            description: "Delivered to recipient",
            location: "Porto, Portugal",
          }),
        ];
        return HttpResponse.json(currentParcel);
      }),
    );
    renderDetail("p1");
    const user = userEvent.setup();
    await screen.findByRole("heading", { name: "Mechanical keyboard" });

    await user.click(screen.getByRole("button", { name: /refresh tracking/i }));
    expect(await screen.findByRole("button", { name: /refreshing/i })).toBeDisabled();
    await waitFor(() => expect(refreshCalls).toBe(1));

    expect(await screen.findByText("Delivered to recipient")).toBeInTheDocument();
    expect(
      screen.getByText(/carrier unreachable, data from 2026-11-26T15:00:00\.000Z/),
    ).toBeInTheDocument();
    await waitFor(() => expect(eventsCalls).toBeGreaterThan(1));
  });

  it("shows the waiting-for-first-scan empty state for a pending parcel with no events", async () => {
    const parcel = makeParcel({
      id: "p2",
      status: "PENDING",
      carrier: null,
      detectionMethod: null,
      lastCheckedAt: null,
      latestEvent: null,
    });
    mswServer.use(
      http.get("*/api/v1/parcels/p2", () => HttpResponse.json(parcel)),
      http.get("*/api/v1/parcels/p2/events", () => HttpResponse.json([])),
    );
    renderDetail("p2");

    expect(await screen.findByText(/waiting for the first carrier scan/i)).toBeInTheDocument();
    expect(screen.getByText("Label created")).toBeInTheDocument();
    expect(screen.getByText("Carrier not detected yet")).toBeInTheDocument();
    expect(screen.queryByText(/Estimated delivery:/)).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /view on/i })).not.toBeInTheDocument();
  });

  it("renders every normalized status as a labeled pill in the timeline", async () => {
    const statuses: TrackingStatus[] = [
      "PENDING",
      "IN_TRANSIT",
      "OUT_FOR_DELIVERY",
      "DELIVERED",
      "EXCEPTION",
      "UNKNOWN",
    ];
    const events = statuses.map((status, i) =>
      makeEvent({
        id: `e${i}`,
        occurredAt: new Date(Date.now() - (i + 1) * 60 * 60 * 1000).toISOString(),
        status,
        description: `Event ${status}`,
      }),
    );
    mswServer.use(
      http.get("*/api/v1/parcels/p1", () => HttpResponse.json(makeParcel({ status: "DELIVERED" }))),
      http.get("*/api/v1/parcels/p1/events", () => HttpResponse.json(events)),
    );
    renderDetail("p1");

    await screen.findByRole("heading", { name: /tracking history/i });
    const labels = [
      "Label created",
      "In transit",
      "Out for delivery",
      "Delivered",
      "Needs attention",
      "Not checked yet",
    ];
    for (const label of labels) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0);
    }
  });

  it(
    "shows a not-found state with a way back when the parcel does not exist",
    { timeout: 15000 },
    async () => {
      mswServer.use(
        http.get("*/api/v1/parcels/missing", () =>
          HttpResponse.json(
            { error: { code: "parcel-not-found", message: "No parcel with this id exists" } },
            { status: 404 },
          ),
        ),
        http.get("*/api/v1/parcels/missing/events", () => HttpResponse.json([])),
      );
      renderDetail("missing");

      expect(await screen.findByRole("alert", undefined, { timeout: 10000 })).toHaveTextContent(
        "Could not load this parcel: No parcel with this id exists",
      );
      expect(screen.getByRole("link", { name: /back to your parcels/i })).toHaveAttribute(
        "href",
        "/parcels",
      );
    },
  );
});
