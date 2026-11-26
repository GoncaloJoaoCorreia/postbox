import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { emailError, loginPasswordError, type FieldErrors } from "./validation";

export function LoginScreen() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const state = location.state as { from?: string; setupCompleted?: boolean } | null;
  const from = state?.from ?? "/parcels";

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    const next: FieldErrors = {};
    const emailMessage = emailError(email);
    if (emailMessage) next.email = emailMessage;
    const passwordMessage = loginPasswordError(password);
    if (passwordMessage) next.password = passwordMessage;
    setErrors(next);
    if (next.email || next.password) return;
    setSubmitting(true);
    try {
      await login(email, password);
      navigate(from, { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Sign in failed, please try again");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main>
      <h1>postbox</h1>
      {state?.setupCompleted && <p role="status">Admin account created. Sign in to continue.</p>}
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
          autoComplete="current-password"
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
          Sign in
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
      <p>
        <Link to="/register">Register</Link>
      </p>
    </main>
  );
}
