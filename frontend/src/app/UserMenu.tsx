import { useState } from "react";
import { useAuth } from "../auth/AuthContext";
import { useMe } from "../auth/useMe";

export function UserMenu() {
  const { logout } = useAuth();
  const me = useMe();
  const [menuOpen, setMenuOpen] = useState(false);

  const displayName = me.data?.displayName ?? "Account";
  const initial = displayName.trim().charAt(0).toUpperCase() || "?";

  return (
    <div className="user-menu">
      <button
        type="button"
        className="user-button"
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        onClick={() => setMenuOpen((open) => !open)}
      >
        <span className="avatar" aria-hidden="true">
          {initial}
        </span>
        {displayName}
      </button>
      {menuOpen && (
        <div className="menu" role="menu">
          <button type="button" role="menuitem" onClick={logout}>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}
