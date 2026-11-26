import { useState, type FormEvent } from "react";
import { ApiError, type User } from "../../api/client";
import { useMe } from "../../auth/useMe";
import { newPasswordError } from "../auth/validation";
import { generatePassword } from "./generatePassword";
import { useUpdateUser } from "./useUsers";
import { activeChipClass, formatCreatedAt, roleChipClass, roleLabel } from "./userView";

export function UserRow({
  user,
  onPasswordReset,
}: {
  user: User;
  onPasswordReset: (password: string) => void;
}) {
  const me = useMe();
  const updateUser = useUpdateUser();
  const isSelf = me.data?.id === user.id;
  const [confirmingDeactivate, setConfirmingDeactivate] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [password, setPassword] = useState("");
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const initial = user.displayName.trim().charAt(0).toUpperCase() || "?";

  async function handleDeactivate() {
    setError(null);
    try {
      await updateUser.mutateAsync({ id: user.id, body: { isActive: false } });
      setConfirmingDeactivate(false);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not deactivate, please try again");
    }
  }

  async function handleReactivate() {
    setError(null);
    try {
      await updateUser.mutateAsync({ id: user.id, body: { isActive: true } });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not reactivate, please try again");
    }
  }

  async function handleReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const message = newPasswordError(password);
    if (message) {
      setPasswordError(message);
      return;
    }
    setPasswordError(null);
    try {
      await updateUser.mutateAsync({ id: user.id, body: { newPassword: password } });
      setResetting(false);
      onPasswordReset(password);
      setPassword("");
    } catch (e) {
      setError(
        e instanceof ApiError ? e.message : "Could not reset the password, please try again",
      );
    }
  }

  return (
    <li className="user-row">
      <div className="user-row-top">
        <span className="avatar" aria-hidden="true">
          {initial}
        </span>
        <strong>{user.displayName}</strong>
        {isSelf && <span className="chip chip-muted">You</span>}
        <span className={roleChipClass(user.role)}>{roleLabel(user.role)}</span>
        <span className={activeChipClass(user.isActive)}>
          {user.isActive ? "Active" : "Deactivated"}
        </span>
        <span className="user-time">{formatCreatedAt(user.createdAt)}</span>
        <span className="user-row-actions">
          {!isSelf && (
            <button
              type="button"
              className="btn-ghost btn-small"
              onClick={() => {
                setResetting(true);
                setConfirmingDeactivate(false);
                setError(null);
              }}
            >
              Reset password
            </button>
          )}
          {user.isActive ? (
            <button
              type="button"
              className="btn-ghost btn-small"
              disabled={isSelf}
              title={isSelf ? "You cannot deactivate your own account" : undefined}
              onClick={() => {
                setConfirmingDeactivate(true);
                setResetting(false);
                setError(null);
              }}
            >
              Deactivate
            </button>
          ) : (
            <button
              type="button"
              className="btn-ghost btn-small"
              disabled={updateUser.isPending}
              onClick={handleReactivate}
            >
              Reactivate
            </button>
          )}
        </span>
      </div>
      <div className="user-row-bottom">
        <span className="user-email">{user.email}</span>
      </div>
      {confirmingDeactivate && (
        <div className="confirm-delete">
          <p>
            Deactivate {user.displayName}'s account? They will not be able to sign in; their parcels
            are kept untouched.
          </p>
          <div className="user-row-actions">
            <button
              type="button"
              className="btn-danger btn-small"
              onClick={handleDeactivate}
              disabled={updateUser.isPending}
            >
              Confirm deactivate
            </button>
            <button
              type="button"
              className="btn-ghost btn-small"
              onClick={() => setConfirmingDeactivate(false)}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
      {resetting && (
        <form className="user-form" onSubmit={handleReset} noValidate>
          <label htmlFor={`reset-password-${user.id}`}>New password for {user.displayName}</label>
          <input
            id={`reset-password-${user.id}`}
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            aria-invalid={passwordError ? true : undefined}
            aria-describedby={passwordError ? `reset-password-${user.id}-error` : undefined}
          />
          {passwordError && (
            <p id={`reset-password-${user.id}-error`} className="field-error">
              {passwordError}
            </p>
          )}
          <button
            type="button"
            className="link-button"
            onClick={() => {
              setPassword(generatePassword());
              setPasswordError(null);
            }}
          >
            Generate a password
          </button>
          <div className="user-row-actions">
            <button type="submit" className="btn-primary btn-small" disabled={updateUser.isPending}>
              Confirm reset
            </button>
            <button
              type="button"
              className="btn-ghost btn-small"
              onClick={() => setResetting(false)}
            >
              Cancel
            </button>
          </div>
        </form>
      )}
      {error && <p role="alert">{error}</p>}
    </li>
  );
}
