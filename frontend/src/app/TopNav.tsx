import { NavLink } from "react-router-dom";
import { useMe } from "../auth/useMe";

export function TopNav() {
  const me = useMe();
  if (me.data?.role !== "ADMIN") return null;
  return (
    <nav className="header-nav" aria-label="Sections">
      <NavLink to="/admin" className={({ isActive }) => (isActive ? "active" : "")}>
        Admin
      </NavLink>
    </nav>
  );
}
