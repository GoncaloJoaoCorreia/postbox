import { Link } from "react-router-dom";

export function RegisterScreen() {
  return (
    <main>
      <h1>postbox</h1>
      <p>There is no public registration — accounts are created by an administrator.</p>
      <p>
        <Link to="/login">Back to sign in</Link>
      </p>
    </main>
  );
}
