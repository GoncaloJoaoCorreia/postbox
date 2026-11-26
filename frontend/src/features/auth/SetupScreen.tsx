import { useQueryClient } from "@tanstack/react-query";
import { useState, type FormEvent } from "react";
import { Navigate, useNavigate } from "react-router-dom";
import { ApiError, type SetupStatusResponse } from "../../api/client";
import { useApi } from "../../api/ApiContext";
import { setupStatusQueryKey, useSetupStatus } from "../../app/useSetupStatus";
import { emailError, newPasswordError, type FieldErrors } from "./validation";

export function SetupScreen() {
  const api = useApi();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { data: setupStatus, isLoading } = useSetupStatus();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (isLoading) return null;
  if (setupStatus && !setupStatus.setupPending) {
    return <Navigate to="/login" replace />;
  }

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
    setSubmitting(true);
    try {
      await api.createFirstAdmin(email, password);
      queryClient.setQueryData<SetupStatusResponse>(setupStatusQueryKey, { setupPending: false });
      navigate("/login", { replace: true, state: { setupCompleted: true } });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Setup failed, please try again");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <h1>Set up postbox</h1>
      <p>Create the first admin account to get started.</p>
      <form onSubmit={handleSubmit} noValidate>
        <label htmlFor="email">Email</label>
        <input
          id="email"
          name="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          aria-invalid={errors.email ? true : undefined}
          aria-describedby={errors.email ? "email-error" : undefined}
        />
        {errors.email && (
          <p id="email-error" className="field-error">
            {errors.email}
          </p>
        )}
        <label htmlFor="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          autoComplete="new-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          aria-invalid={errors.password ? true : undefined}
          aria-describedby={errors.password ? "password-error" : undefined}
        />
        {errors.password && (
          <p id="password-error" className="field-error">
            {errors.password}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          Create admin account
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
