import { useState, type FormEvent } from "react";
import { ApiError, type Carrier, type DetectResponse } from "../../api/client";
import { CARRIERS, carrierLabel } from "./carriers";
import { useCreateParcel, useDetectCarrier } from "./useParcels";

const trackingNumberPattern = /^[A-Za-z0-9][A-Za-z0-9-]{0,63}$/;

export function AddParcelDialog({ onClose }: { onClose: () => void }) {
  const detect = useDetectCarrier();
  const createParcel = useCreateParcel();
  const [trackingNumber, setTrackingNumber] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [result, setResult] = useState<DetectResponse | null>(null);
  const [carrier, setCarrier] = useState<Carrier | "">("");
  const [numberError, setNumberError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleDetect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setNumberError(null);
    const number = trackingNumber.trim();
    if (!trackingNumberPattern.test(number)) {
      setNumberError("Enter a tracking number (letters, digits and dashes only)");
      return;
    }
    try {
      const response = await detect.mutateAsync({ trackingNumber: number });
      setResult(response);
      setCarrier(response.carrier ?? response.candidates[0] ?? "");
    } catch (e) {
      setResult(null);
      setError(e instanceof ApiError ? e.message : "Detection failed, please try again");
    }
  }

  async function handleSave() {
    if (!result) return;
    setError(null);
    try {
      await createParcel.mutateAsync({
        trackingNumber: result.trackingNumber,
        displayName: displayName.trim() || undefined,
        carrier: carrier || undefined,
      });
      onClose();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save the parcel, please try again");
    }
  }

  return (
    <section className="dialog" aria-label="Add parcel">
      <h2>Add parcel</h2>
      {detect.isPending && (
        <p role="status">Probing candidate carriers for {trackingNumber.trim()}…</p>
      )}
      {!result ? (
        <form onSubmit={handleDetect} noValidate>
          <label htmlFor="add-tracking-number">Tracking number</label>
          <input
            id="add-tracking-number"
            value={trackingNumber}
            onChange={(e) => setTrackingNumber(e.target.value)}
            aria-invalid={numberError ? true : undefined}
            aria-describedby={numberError ? "add-tracking-number-error" : undefined}
          />
          {numberError && (
            <p id="add-tracking-number-error" className="field-error">
              {numberError}
            </p>
          )}
          <label htmlFor="add-display-name">Name (optional)</label>
          <input
            id="add-display-name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
          <div className="parcel-actions">
            <button type="submit" disabled={detect.isPending}>
              Detect carrier
            </button>
            <button type="button" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="detect-result">
          <p>
            <span className="carrier-badge">{carrierLabel(result.carrier)}</span>{" "}
            {result.method === "rule"
              ? "No carrier recognizes this number yet."
              : result.method === "override"
                ? "Manual carrier selected."
                : "Carrier detected."}
          </p>
          {result.method === "rule" && (
            <p className="pending-note">
              Saving will keep the top candidate and mark the parcel pending confirmation.
            </p>
          )}
          {result.candidates.length > 0 && (
            <p className="candidates">
              Candidates: {result.candidates.map((c) => carrierLabel(c)).join(", ")}
            </p>
          )}
          <label htmlFor="add-carrier">Carrier</label>
          <select
            id="add-carrier"
            value={carrier}
            onChange={(e) => setCarrier(e.target.value as Carrier | "")}
          >
            <option value="">No carrier</option>
            {CARRIERS.map((c) => (
              <option key={c} value={c}>
                {carrierLabel(c)}
              </option>
            ))}
          </select>
          <div className="parcel-actions">
            <button type="button" onClick={handleSave} disabled={createParcel.isPending}>
              Save parcel
            </button>
            <button
              type="button"
              onClick={() => {
                setResult(null);
                setCarrier("");
              }}
            >
              Back
            </button>
          </div>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  );
}
