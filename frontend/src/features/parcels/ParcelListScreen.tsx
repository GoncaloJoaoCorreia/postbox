import { useState } from "react";
import { useAuth } from "../../auth/AuthContext";
import { AddParcelDialog } from "./AddParcelDialog";
import { ParcelRow } from "./ParcelRow";
import { groupParcels } from "./statusGroups";
import { useParcels } from "./useParcels";

export function ParcelListScreen() {
  const { logout } = useAuth();
  const parcelsQuery = useParcels();
  const [search, setSearch] = useState("");
  const [adding, setAdding] = useState(false);

  const parcels = parcelsQuery.data ?? [];
  const query = search.trim().toLowerCase();
  const visible = query
    ? parcels.filter(
        (p) =>
          p.displayName.toLowerCase().includes(query) ||
          p.trackingNumber.toLowerCase().includes(query),
      )
    : parcels;
  const groups = groupParcels(visible);

  return (
    <main>
      <header className="parcel-list-header">
        <h1>Parcels</h1>
        <div className="parcel-actions">
          <button type="button" onClick={() => setAdding(true)}>
            Add parcel
          </button>
          <button type="button" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>
      <input
        type="search"
        aria-label="Search parcels"
        placeholder="Search by name or number"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />
      {parcelsQuery.isPending && <p role="status">Loading parcels…</p>}
      {parcelsQuery.isError && (
        <p role="alert">
          Could not load parcels
          {parcelsQuery.error instanceof Error ? `: ${parcelsQuery.error.message}` : ""}
        </p>
      )}
      {parcelsQuery.isSuccess && groups.length === 0 && (
        <p>No parcels found. Add a tracking number to get started.</p>
      )}
      {groups.map((group) => (
        <section key={group.label} className="parcel-group">
          <h2>
            {group.label} ({group.parcels.length})
          </h2>
          <ul>
            {group.parcels.map((parcel) => (
              <ParcelRow key={parcel.id} parcel={parcel} />
            ))}
          </ul>
        </section>
      ))}
      {adding && <AddParcelDialog onClose={() => setAdding(false)} />}
    </main>
  );
}
