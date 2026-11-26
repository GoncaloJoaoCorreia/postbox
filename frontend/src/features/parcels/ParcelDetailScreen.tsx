import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { ApiError } from "../../api/client";
import { TopNav } from "../../app/TopNav";
import { UserMenu } from "../../app/UserMenu";
import { carrierLabel, carrierTrackingUrl } from "./carriers";
import { ExternalLinkIcon } from "./icons";
import { detectionMethodLabel, STATUS_LABELS, statusChipClass } from "./parcelView";
import { formatEta, formatEventTime, relativeTime } from "./relativeTime";
import { useParcel, useParcelEvents, useRefreshParcel } from "./useParcels";

export function ParcelDetailScreen() {
  const { id } = useParams();
  const parcelQuery = useParcel(id);
  const eventsQuery = useParcelEvents(id);
  const refresh = useRefreshParcel();
  const [refreshError, setRefreshError] = useState<string | null>(null);

  const parcel = parcelQuery.data;
  const events = eventsQuery.data;
  const trackingUrl = parcel
    ? carrierTrackingUrl(parcel.carrier, parcel.trackingNumber, parcel.metadata)
    : null;

  async function handleRefresh() {
    if (!id) return;
    setRefreshError(null);
    try {
      await refresh.mutateAsync(id);
    } catch (e) {
      setRefreshError(e instanceof ApiError ? e.message : "Could not refresh, please try again");
    }
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/parcels" className="brand header-brand">
          <span className="brand-mark" aria-hidden="true" />
          Postbox
        </Link>
        <TopNav />
        <UserMenu />
      </header>
      <div className="app-body">
        <main className="app-main">
          {parcelQuery.isPending && <p role="status">Loading parcel…</p>}
          {parcelQuery.isError && (
            <div>
              <p role="alert">
                Could not load this parcel
                {parcelQuery.error instanceof ApiError ? `: ${parcelQuery.error.message}` : ""}
              </p>
              <p>
                <Link to="/parcels">Back to your parcels</Link>
              </p>
            </div>
          )}
          {parcel && (
            <>
              <nav className="breadcrumb" aria-label="Breadcrumb">
                <Link to="/parcels">Your parcels</Link>
                <span aria-hidden="true"> / </span>
                <span>{parcel.displayName}</span>
              </nav>
              <section className="detail-header" aria-label="Parcel summary">
                <div className="detail-title-row">
                  <h1>{parcel.displayName}</h1>
                  <span className="tracking-number">{parcel.trackingNumber}</span>
                </div>
                <div className="detail-badges">
                  <span className="carrier-badge">{carrierLabel(parcel.carrier)}</span>
                  <span className={statusChipClass(parcel.status)}>
                    {STATUS_LABELS[parcel.status]}
                  </span>
                </div>
                {parcel.estimatedDeliveryAt && (
                  <p className="detail-eta">
                    Estimated delivery: {formatEta(parcel.estimatedDeliveryAt)}
                  </p>
                )}
                <p className="detail-meta">
                  {detectionMethodLabel(parcel.detectionMethod)}
                  {parcel.lastCheckedAt && ` · Last checked ${relativeTime(parcel.lastCheckedAt)}`}
                </p>
                <div className="detail-actions">
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={handleRefresh}
                    disabled={refresh.isPending}
                  >
                    {refresh.isPending ? (
                      <>
                        <span className="spinner" aria-hidden="true" />
                        Refreshing…
                      </>
                    ) : (
                      "Refresh tracking"
                    )}
                  </button>
                  {trackingUrl && (
                    <a
                      className="btn-ghost detail-carrier-link"
                      href={trackingUrl}
                      target="_blank"
                      rel="noreferrer"
                    >
                      View on {carrierLabel(parcel.carrier)}
                      <ExternalLinkIcon />
                    </a>
                  )}
                </div>
                {parcel.lastCheckError && (
                  <p className="parcel-warning" role="status">
                    {parcel.lastCheckError}
                  </p>
                )}
                {refreshError && <p role="alert">{refreshError}</p>}
              </section>
              <section className="detail-section" aria-label="Tracking history">
                <h2>Tracking history</h2>
                {eventsQuery.isPending && <p role="status">Loading events…</p>}
                {eventsQuery.isError && (
                  <p role="alert">
                    Could not load events
                    {eventsQuery.error instanceof Error ? `: ${eventsQuery.error.message}` : ""}
                  </p>
                )}
                {eventsQuery.isSuccess && events?.length === 0 && (
                  <p className="empty-timeline">
                    Waiting for the first carrier scan — this parcel has no tracking events yet.
                  </p>
                )}
                {events && events.length > 0 && (
                  <ol className="timeline">
                    {events.map((event, index) => (
                      <li
                        key={event.id}
                        className={index === 0 ? "timeline-item latest" : "timeline-item"}
                      >
                        <div className="timeline-body">
                          <div className="timeline-top">
                            <strong>{event.description}</strong>
                            <span className={statusChipClass(event.status)}>
                              {STATUS_LABELS[event.status]}
                            </span>
                          </div>
                          <div className="timeline-meta">
                            {event.location ? `${event.location} · ` : ""}
                            {formatEventTime(event.occurredAt)}
                          </div>
                        </div>
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </>
          )}
        </main>
      </div>
    </div>
  );
}
