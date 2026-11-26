import { useState, type FormEvent } from "react";
import { ApiError, type Carrier, type DetectResponse } from "../../api/client";
import { CARRIERS, carrierLabel, carrierName } from "./carriers";
import { useCreateParcel, useDetectCarrier } from "./useParcels";

const trackingNumberPattern = /^[A-Za-z0-9][A-Za-z0-9-]{0,63}$/;

export function AddParcelScreen({ onClose }: { onClose: () => void }) {
  const detect = useDetectCarrier();
  const createParcel = useCreateParcel();
  const [trackingNumber, setTrackingNumber] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [result, setResult] = useState<DetectResponse | null>(null);
  const [carrier, setCarrier] = useState<Carrier | "">("");
  const [showCarrierSelect, setShowCarrierSelect] = useState(false);
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
      setShowCarrierSelect(false);
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

  const methodLabel =
    result?.method === "rule"
      ? "Pending confirmation"
      : result?.method === "override"
        ? "Manual selection"
        : "Automatically detected";

  return (
    <main className="app-main">
      <section className="add-panel" aria-label="Add parcel">
        <h2>Add a parcel</h2>
        <p className="subtitle">Paste a tracking number and Postbox will find the carrier.</p>
        {detect.isPending && (
          <p role="status" className="finding">
            <span className="spinner" aria-hidden="true" />
            Finding the carrier…
          </p>
        )}
        {!result ? (
          <form onSubmit={handleDetect} noValidate>
            <label htmlFor="add-tracking-number">Tracking number</label>
            <input
              id="add-tracking-number"
              placeholder="e.g. JD01460006281..."
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
              placeholder="e.g. Desk lamp, New headphones..."
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
            <div className="panel-actions">
              <button type="button" className="btn-ghost" onClick={onClose}>
                Cancel
              </button>
              <button type="submit" className="btn-primary" disabled={detect.isPending}>
                Detect carrier
              </button>
            </div>
          </form>
        ) : (
          <div className="detect-result">
            <p className="resolved">
              <span className="carrier-badge">{carrierLabel(result.carrier)}</span>
              <strong>{carrierName(result.carrier)}</strong>
              <span className="method-label">{methodLabel}</span>
            </p>
            {result.method === "rule" && (
              <p className="pending-note">
                No carrier recognizes this number yet — saving keeps the top candidate and marks the
                parcel pending confirmation.
              </p>
            )}
            <ul className="candidate-list">
              {result.candidates.map((c) => (
                <li key={c} className={c === result.carrier ? "candidate resolved" : "candidate"}>
                  <span className="carrier-badge">{carrierLabel(c)}</span>
                  <span>{carrierName(c)}</span>
                  <span className="candidate-state">
                    {c === result.carrier
                      ? result.method === "probe"
                        ? "Tracking found"
                        : "Top candidate"
                      : "Not a match"}
                  </span>
                </li>
              ))}
            </ul>
            <button
              type="button"
              className="link-button"
              onClick={() => setShowCarrierSelect((show) => !show)}
            >
              Choose a different carrier
            </button>
            {showCarrierSelect && (
              <div className="carrier-select">
                <label htmlFor="add-carrier">Carrier</label>
                <select
                  id="add-carrier"
                  value={carrier}
                  onChange={(e) => setCarrier(e.target.value as Carrier | "")}
                >
                  <option value="">No carrier</option>
                  {CARRIERS.map((c) => (
                    <option key={c} value={c}>
                      {carrierName(c)}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="panel-actions">
              <button type="button" className="btn-ghost" onClick={onClose}>
                Cancel
              </button>
              <button
                type="button"
                className="btn-primary"
                onClick={handleSave}
                disabled={createParcel.isPending}
              >
                Add parcel
              </button>
            </div>
          </div>
        )}
        {error && <p role="alert">{error}</p>}
      </section>
    </main>
  );
}
