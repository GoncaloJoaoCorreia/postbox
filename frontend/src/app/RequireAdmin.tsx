import { Navigate, Outlet } from "react-router-dom";
import { useMe } from "../auth/useMe";

export function RequireAdmin() {
  const me = useMe();
  if (me.data?.role === "ADMIN") return <Outlet />;
  if (me.data || me.isError) return <Navigate to="/parcels" replace />;
  return null;
}
