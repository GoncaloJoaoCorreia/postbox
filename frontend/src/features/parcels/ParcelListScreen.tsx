import { useAuth } from "../../auth/AuthContext";

export function ParcelListScreen() {
  const { logout } = useAuth();
  return (
    <main>
      <header>
        <h1>Parcels</h1>
        <button type="button" onClick={logout}>
          Sign out
        </button>
      </header>
      <p>The parcel list and add-parcel flow land with the feature screens.</p>
    </main>
  );
}
