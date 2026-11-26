import { createContext, useContext, useState, type ReactNode } from "react";
import { createApiClient, type ApiClient } from "./client";
import { getStoredToken } from "./tokenStorage";

const ApiContext = createContext<ApiClient | null>(null);

type UnauthorizedListener = () => void;

const unauthorizedListeners = new Set<UnauthorizedListener>();

export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

export function ApiProvider({ children }: { children: ReactNode }) {
  const [client] = useState<ApiClient>(() =>
    createApiClient({
      baseUrl: "",
      getToken: getStoredToken,
      onUnauthorized: () => unauthorizedListeners.forEach((listener) => listener()),
    }),
  );
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>;
}

export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) throw new Error("useApi must be used inside <ApiProvider>");
  return client;
}
