import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useSetupStatus } from "./useSetupStatus";

export function SetupGate() {
  const { data } = useSetupStatus();
  const location = useLocation();
  if (data?.setupPending && location.pathname !== "/setup") {
    return <Navigate to="/setup" replace />;
  }
  return <Outlet />;
}
