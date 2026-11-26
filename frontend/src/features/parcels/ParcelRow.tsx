import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ApiError, type Carrier, type Parcel, type UpdateParcelRequest } from "../../api/client";
import { CARRIERS, carrierLabel } from "./carriers";
import { MetadataPrompt } from "./MetadataPrompt";
import { STATUS_LABELS, statusChipClass } from "./parcelView";
import { relativeTime } from "./relativeTime";
import { useDeleteParcel, useUpdateParcel } from "./useParcels";

export function ParcelRow({ parcel }: { parcel: Parcel }) {
  const updateParcel = useUpdateParcel();
  const deleteParcel = useDeleteParcel();
  const [editing, setEditing] = useState(false);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [showMetadata, setShowMetadata] = useState(false);
  const [name, setName] = useState(parcel.displayName);
  const [archived, setArchived] = useState(Boolean(parcel.archivedAt));
  const [carrier, setCarrier] = useState<string>(parcel.carrier ?? "");
  const [error, setError] = useState<string | null>(null);

  function startEdit() {
    setName(parcel.displayName);
    setArchived(Boolean(parcel.archivedAt));
    setCarrier(parcel.carrier ?? "");
    setError(null);
    setEditing(true);
  }

  async function handleSave(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Name cannot be empty");
      return;
    }
    const body: UpdateParcelRequest = {};
    if (trimmed !== parcel.displayName) body.displayName = trimmed;
    if (archived !== Boolean(parcel.archivedAt)) body.archived = archived;
    if (carrier && carrier !== parcel.carrier) body.carrier = carrier as Carrier;
    if (Object.keys(body).length === 0) {
      setEditing(false);
      return;
    }
    try {
      await updateParcel.mutateAsync({ id: parcel.id, body });
      setEditing(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save, please try again");
    }
  }

  async function handleDelete() {
    setError(null);
    try {
      await deleteParcel.mutateAsync(parcel.id);
      setConfirmingDelete(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not delete, please try again");
    }
  }

  const missingMetadata = parcel.missingMetadata ?? [];

  return (
    <li className="parcel-row">
      <div className="parcel-row-top">
        <span className="carrier-badge">{carrierLabel(parcel.carrier)}</span>
        <Link to={`/parcels/${parcel.id}`} className="parcel-name">
          {parcel.displayName}
        </Link>
        <span className={statusChipClass(parcel.status)}>{STATUS_LABELS[parcel.status]}</span>
        {parcel.latestEvent && (
          <span className="parcel-time">{relativeTime(parcel.latestEvent.occurredAt)}</span>
        )}
        <span className="parcel-row-actions">
          <button type="button" className="btn-ghost btn-small" onClick={startEdit}>
            Edit
          </button>
          <button
            type="button"
            className="btn-ghost btn-small"
            onClick={() => setConfirmingDelete(true)}
          >
            Delete
          </button>
        </span>
      </div>
      <div className="parcel-row-bottom">
        {parcel.latestEvent && (
          <span className="parcel-event">{parcel.latestEvent.description}</span>
        )}
        <span className="tracking-number">{parcel.trackingNumber}</span>
      </div>
      {parcel.lastCheckError && <p className="parcel-warning">{parcel.lastCheckError}</p>}
      {missingMetadata.length > 0 && !showMetadata && (
        <button type="button" className="link-button" onClick={() => setShowMetadata(true)}>
          Add required details ({missingMetadata.length})
        </button>
      )}
      {missingMetadata.length > 0 && showMetadata && (
        <MetadataPrompt parcel={parcel} onDone={() => setShowMetadata(false)} />
      )}
      {confirmingDelete && (
        <div className="confirm-delete">
          <p>Delete this parcel and its events?</p>
          <div className="parcel-row-actions">
            <button
              type="button"
              className="btn-danger btn-small"
              onClick={handleDelete}
              disabled={deleteParcel.isPending}
            >
              Confirm delete
            </button>
            <button
              type="button"
              className="btn-ghost btn-small"
              onClick={() => setConfirmingDelete(false)}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
      {editing && (
        <form className="parcel-edit" onSubmit={handleSave} noValidate>
          <label htmlFor={`edit-name-${parcel.id}`}>Name</label>
          <input
            id={`edit-name-${parcel.id}`}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <label htmlFor={`edit-carrier-${parcel.id}`}>Carrier</label>
          <select
            id={`edit-carrier-${parcel.id}`}
            value={carrier}
            onChange={(e) => setCarrier(e.target.value)}
          >
            <option value="">No change</option>
            {CARRIERS.map((c) => (
              <option key={c} value={c}>
                {carrierLabel(c)}
              </option>
            ))}
          </select>
          <label>
            <input
              type="checkbox"
              checked={archived}
              onChange={(e) => setArchived(e.target.checked)}
            />
            Archived
          </label>
          <div className="parcel-row-actions">
            <button
              type="submit"
              className="btn-primary btn-small"
              disabled={updateParcel.isPending}
            >
              Save
            </button>
            <button type="button" className="btn-ghost btn-small" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </div>
          {error && <p role="alert">{error}</p>}
        </form>
      )}
      {!editing && error && <p role="alert">{error}</p>}
    </li>
  );
}
