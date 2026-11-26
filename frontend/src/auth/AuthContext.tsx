import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { onUnauthorized, useApi } from "../api/ApiContext";
import { clearStoredToken, getStoredToken, storeToken } from "../api/tokenStorage";

interface AuthContextValue {
  token: string | null;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [token, setToken] = useState<string | null>(() => getStoredToken());

  const login = useCallback(
    async (email: string, password: string) => {
      const { token: jwt } = await api.login(email, password);
      storeToken(jwt);
      setToken(jwt);
    },
    [api],
  );

  const logout = useCallback(() => {
    clearStoredToken();
    setToken(null);
  }, []);

  useEffect(() => onUnauthorized(logout), [logout]);

  return <AuthContext.Provider value={{ token, login, logout }}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside <AuthProvider>");
  return value;
}
