import { useState } from "react";
import { Link } from "react-router-dom";
import { TopNav } from "../../app/TopNav";
import { UserMenu } from "../../app/UserMenu";
import { CreateUserPanel } from "./CreateUserPanel";
import { PasswordReveal } from "./PasswordReveal";
import { UserRow } from "./UserRow";
import { useUsers } from "./useUsers";

interface RevealedPassword {
  label: string;
  email: string;
  password: string;
}

export function AdminScreen() {
  const usersQuery = useUsers();
  const [adding, setAdding] = useState(false);
  const [revealed, setRevealed] = useState<RevealedPassword | null>(null);

  const users = usersQuery.data ?? [];

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/parcels" className="brand header-brand">
          <span className="brand-mark" aria-hidden="true" />
          Postbox
        </Link>
        <TopNav />
        <UserMenu />
      </header>
      <div className="app-body">
        {adding ? (
          <CreateUserPanel
            onClose={() => setAdding(false)}
            onCreated={(email, password) => {
              setAdding(false);
              setRevealed({ label: "Initial password", email, password });
            }}
          />
        ) : (
          <main className="app-main">
            <div className="list-heading">
              <div>
                <h1>Users</h1>
                <p className="subtitle">Accounts on this instance.</p>
              </div>
              <button type="button" className="btn-primary" onClick={() => setAdding(true)}>
                + Add user
              </button>
            </div>
            {revealed && (
              <PasswordReveal
                label={revealed.label}
                email={revealed.email}
                password={revealed.password}
                onDismiss={() => setRevealed(null)}
              />
            )}
            {usersQuery.isPending && <p role="status">Loading users…</p>}
            {usersQuery.isError && (
              <p role="alert">
                Could not load users
                {usersQuery.error instanceof Error ? `: ${usersQuery.error.message}` : ""}
              </p>
            )}
            {usersQuery.isSuccess && users.length === 0 && <p>No users yet.</p>}
            <ul className="user-list">
              {users.map((user) => (
                <UserRow
                  key={user.id}
                  user={user}
                  onPasswordReset={(password) =>
                    setRevealed({ label: "New password", email: user.email, password })
                  }
                />
              ))}
            </ul>
          </main>
        )}
      </div>
    </div>
  );
}
