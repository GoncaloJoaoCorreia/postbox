import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { BrowserRouter } from "react-router-dom";
import { ApiProvider } from "../api/ApiContext";
import { AuthProvider } from "../auth/AuthContext";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());
  return (
    <QueryClientProvider client={queryClient}>
      <ApiProvider>
        <AuthProvider>{children}</AuthProvider>
      </ApiProvider>
    </QueryClientProvider>
  );
}

export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <Providers>
      <BrowserRouter>{children}</BrowserRouter>
    </Providers>
  );
}
