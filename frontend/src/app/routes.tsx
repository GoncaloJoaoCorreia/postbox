import { Navigate, Route, Routes } from "react-router-dom";
import { LoginScreen } from "../features/auth/LoginScreen";
import { RegisterScreen } from "../features/auth/RegisterScreen";
import { ParcelDetailScreen } from "../features/parcels/ParcelDetailScreen";
import { ParcelListScreen } from "../features/parcels/ParcelListScreen";
import { RequireAuth } from "./RequireAuth";

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginScreen />} />
      <Route path="/register" element={<RegisterScreen />} />
      <Route element={<RequireAuth />}>
        <Route path="/parcels" element={<ParcelListScreen />} />
        <Route path="/parcels/:id" element={<ParcelDetailScreen />} />
      </Route>
      <Route path="*" element={<Navigate to="/parcels" replace />} />
    </Routes>
  );
}
