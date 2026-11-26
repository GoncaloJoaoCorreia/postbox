import { Navigate, Route, Routes } from "react-router-dom";
import { LoginScreen } from "../features/auth/LoginScreen";
import { RegisterScreen } from "../features/auth/RegisterScreen";
import { SetupScreen } from "../features/auth/SetupScreen";
import { ParcelDetailScreen } from "../features/parcels/ParcelDetailScreen";
import { ParcelListScreen } from "../features/parcels/ParcelListScreen";
import { RequireAuth } from "./RequireAuth";
import { SetupGate } from "./SetupGate";

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<SetupGate />}>
        <Route path="/login" element={<LoginScreen />} />
        <Route path="/setup" element={<SetupScreen />} />
        <Route path="/register" element={<RegisterScreen />} />
        <Route element={<RequireAuth />}>
          <Route path="/parcels" element={<ParcelListScreen />} />
          <Route path="/parcels/:id" element={<ParcelDetailScreen />} />
        </Route>
        <Route path="*" element={<Navigate to="/parcels" replace />} />
      </Route>
    </Routes>
  );
}
