import { useState, type FormEvent } from "react";
import { ApiError, type MetadataField, type Parcel } from "../../api/client";
import { useRefreshParcel, useUpdateParcel } from "./useParcels";

function fieldError(value: string, field: MetadataField): string | null {
  const trimmed = value.trim();
  if (!trimmed) return `${field.label} is required`;
  if (field.validationRegex && !new RegExp(`^(?:${field.validationRegex})$`).test(trimmed)) {
    return "Does not match the expected format";
  }
  return null;
}

export function MetadataPrompt({ parcel, onDone }: { parcel: Parcel; onDone: () => void }) {
  const updateParcel = useUpdateParcel();
  const refreshParcel = useRefreshParcel();
  const [fields, setFields] = useState<MetadataField[]>(parcel.missingMetadata ?? []);
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const next: Record<string, string> = {};
    for (const field of fields) {
      const message = fieldError(values[field.name] ?? "", field);
      if (message) next[field.name] = message;
    }
    setErrors(next);
    if (Object.keys(next).length > 0) return;
    setSaving(true);
    try {
      const metadata = {
        ...(parcel.metadata ?? {}),
        ...Object.fromEntries(
          fields.map((field) => [field.name, (values[field.name] ?? "").trim()]),
        ),
      };
      await updateParcel.mutateAsync({ id: parcel.id, body: { metadata } });
      try {
        await refreshParcel.mutateAsync(parcel.id);
        onDone();
      } catch (e) {
        if (
          e instanceof ApiError &&
          e.status === 409 &&
          e.code === "missing-carrier-metadata" &&
          e.fields
        ) {
          setFields(e.fields);
          setValues({});
          setErrors({});
        } else {
          throw e;
        }
      }
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save the details, please try again");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="metadata-prompt" onSubmit={handleSubmit} noValidate>
      <p>This carrier needs a few more details to track this parcel:</p>
      {fields.map((field) => (
        <div key={field.name}>
          <label htmlFor={`metadata-${field.name}`}>{field.label}</label>
          <input
            id={`metadata-${field.name}`}
            value={values[field.name] ?? ""}
            onChange={(e) => setValues({ ...values, [field.name]: e.target.value })}
            aria-invalid={errors[field.name] ? true : undefined}
            aria-describedby={
              errors[field.name] ? `metadata-${field.name}-error` : `metadata-${field.name}-hint`
            }
          />
          {errors[field.name] ? (
            <p id={`metadata-${field.name}-error`} className="field-error">
              {errors[field.name]}
            </p>
          ) : (
            <p id={`metadata-${field.name}-hint`} className="field-hint">
              {field.hint}
            </p>
          )}
        </div>
      ))}
      <button type="submit" disabled={saving}>
        Save details
      </button>
      {error && <p role="alert">{error}</p>}
    </form>
  );
}
