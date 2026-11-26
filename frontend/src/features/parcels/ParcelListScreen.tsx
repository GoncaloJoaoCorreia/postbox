import { useState } from "react";
import { useAuth } from "../../auth/AuthContext";
import { useMe } from "../../auth/useMe";
import { AddParcelScreen } from "./AddParcelScreen";
import { ParcelRow } from "./ParcelRow";
import { AlertIcon, ArchiveIcon, BoxIcon, CheckIcon, TruckIcon } from "./icons";
import { filterParcels, parcelCounts, type ParcelView } from "./parcelView";
import { useParcels } from "./useParcels";

export function ParcelListScreen() {
  const { logout } = useAuth();
  const me = useMe();
  const parcelsQuery = useParcels();
  const [search, setSearch] = useState("");
  const [view, setView] = useState<ParcelView>("all");
  const [adding, setAdding] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);

  const parcels = parcelsQuery.data ?? [];
  const visible = filterParcels(parcels, view, search);
  const counts = parcelCounts(parcels);
  const displayName = me.data?.displayName ?? "Account";
  const initial = displayName.trim().charAt(0).toUpperCase() || "?";

  return (
    <div className="app-shell">
      <header className="app-header">
        <input
          type="search"
          className="header-search"
          aria-label="Search parcels"
          placeholder="Search parcels..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <div className="user-menu">
          <button
            type="button"
            className="user-button"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            <span className="avatar" aria-hidden="true">
              {initial}
            </span>
            {displayName}
          </button>
          {menuOpen && (
            <div className="menu" role="menu">
              <button type="button" role="menuitem" onClick={logout}>
                Sign out
              </button>
            </div>
          )}
        </div>
      </header>
      <div className="app-body">
        <aside className="app-sidebar">
          <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            Postbox
          </div>
          <nav className="side-nav">
            <button
              type="button"
              className={view === "all" ? "active" : ""}
              onClick={() => setView("all")}
            >
              <BoxIcon /> All parcels
            </button>
            <button
              type="button"
              className={view === "in-transit" ? "active" : ""}
              onClick={() => setView("in-transit")}
            >
              <TruckIcon /> In transit
            </button>
            <button
              type="button"
              className={view === "delivered" ? "active" : ""}
              onClick={() => setView("delivered")}
            >
              <CheckIcon /> Delivered
            </button>
            <button
              type="button"
              className={view === "archived" ? "active" : ""}
              onClick={() => setView("archived")}
            >
              <ArchiveIcon /> Archived
            </button>
          </nav>
          <p className="sidebar-footer">Self-hosted instance.</p>
        </aside>
        {adding ? (
          <AddParcelScreen onClose={() => setAdding(false)} />
        ) : (
          <main className="app-main">
            <div className="list-heading">
              <div>
                <h1>Your parcels</h1>
                <p className="subtitle">Everything you're waiting for, in one place.</p>
              </div>
              <button type="button" className="btn-primary" onClick={() => setAdding(true)}>
                + Add parcel
              </button>
            </div>
            <div className="summary-cards">
              <button
                type="button"
                aria-label={`${counts.inTransit} In transit`}
                className={`summary-card card-orange${view === "in-transit" ? " active" : ""}`}
                onClick={() => setView("in-transit")}
              >
                <TruckIcon />
                <span className="count">{counts.inTransit}</span>
                <span>In transit</span>
              </button>
              <button
                type="button"
                aria-label={`${counts.delivered} Delivered`}
                className={`summary-card card-green${view === "delivered" ? " active" : ""}`}
                onClick={() => setView("delivered")}
              >
                <CheckIcon />
                <span className="count">{counts.delivered}</span>
                <span>Delivered</span>
              </button>
              <button
                type="button"
                aria-label={`${counts.attention} Needs attention`}
                className={`summary-card card-red${view === "attention" ? " active" : ""}`}
                onClick={() => setView("attention")}
              >
                <AlertIcon />
                <span className="count">{counts.attention}</span>
                <span>Needs attention</span>
              </button>
            </div>
            <select
              className="status-filter"
              aria-label="Filter by status"
              value={view}
              onChange={(e) => setView(e.target.value as ParcelView)}
            >
              <option value="all">All statuses</option>
              <option value="in-transit">In transit</option>
              <option value="delivered">Delivered</option>
              <option value="attention">Needs attention</option>
              <option value="archived">Archived</option>
            </select>
            {parcelsQuery.isPending && <p role="status">Loading parcels…</p>}
            {parcelsQuery.isError && (
              <p role="alert">
                Could not load parcels
                {parcelsQuery.error instanceof Error ? `: ${parcelsQuery.error.message}` : ""}
              </p>
            )}
            {parcelsQuery.isSuccess && visible.length === 0 && (
              <p>No parcels found. Add a tracking number to get started.</p>
            )}
            <ul className="parcel-list">
              {visible.map((parcel) => (
                <ParcelRow key={parcel.id} parcel={parcel} />
              ))}
            </ul>
          </main>
        )}
      </div>
    </div>
  );
}
