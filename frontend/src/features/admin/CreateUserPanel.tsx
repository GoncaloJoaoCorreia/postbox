import { useState, type FormEvent } from "react";
import { ApiError, type UserRole } from "../../api/client";
import { emailError, newPasswordError, type FieldErrors } from "../auth/validation";
import { generatePassword } from "./generatePassword";
import { useCreateUser } from "./useUsers";

export function CreateUserPanel({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: (email: string, password: string) => void;
}) {
  const createUser = useCreateUser();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [role, setRole] = useState<UserRole>("USER");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const next: FieldErrors = {};
    const emailMessage = emailError(email);
    if (emailMessage) next.email = emailMessage;
    const passwordMessage = newPasswordError(password);
    if (passwordMessage) next.password = passwordMessage;
    setErrors(next);
    if (next.email || next.password) return;
    const trimmedEmail = email.trim();
    try {
      await createUser.mutateAsync({
        email: trimmedEmail,
        password,
        displayName: displayName.trim() || undefined,
        role,
      });
      onCreated(trimmedEmail, password);
    } catch (e) {
      if (e instanceof ApiError) {
        if (e.code === "email-already-used") {
          setErrors({ email: e.message });
          return;
        }
        setError(e.message);
      } else {
        setError("Could not create the user, please try again");
      }
    }
  }

  return (
    <main className="app-main">
      <section className="add-panel" aria-label="Add user">
        <h2>Add a user</h2>
        <p className="subtitle">Create an account with an initial password you hand over once.</p>
        <form onSubmit={handleSubmit} noValidate>
          <label htmlFor="create-email">Email</label>
          <input
            id="create-email"
            name="email"
            type="email"
            autoComplete="off"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            aria-invalid={errors.email ? true : undefined}
            aria-describedby={errors.email ? "create-email-error" : undefined}
          />
          {errors.email && (
            <p id="create-email-error" className="field-error">
              {errors.email}
            </p>
          )}
          <label htmlFor="create-display-name">Display name (optional)</label>
          <input
            id="create-display-name"
            placeholder="e.g. Ana Silva"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
          <label htmlFor="create-role">Role</label>
          <select
            id="create-role"
            value={role}
            onChange={(e) => setRole(e.target.value as UserRole)}
          >
            <option value="USER">User</option>
            <option value="ADMIN">Admin</option>
          </select>
          <label htmlFor="create-password">Initial password</label>
          <input
            id="create-password"
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={errors.password ? true : undefined}
            aria-describedby={errors.password ? "create-password-error" : undefined}
          />
          {errors.password && (
            <p id="create-password-error" className="field-error">
              {errors.password}
            </p>
          )}
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setPassword(generatePassword());
              setErrors((prev) => ({ ...prev, password: undefined }));
            }}
          >
            Generate a password
          </button>
          <div className="panel-actions">
            <button type="button" className="btn-ghost" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={createUser.isPending}>
              Create user
            </button>
          </div>
        </form>
        {error && <p role="alert">{error}</p>}
      </section>
    </main>
  );
}
