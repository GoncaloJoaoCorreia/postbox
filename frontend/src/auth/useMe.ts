import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiContext";

export const meQueryKey = ["me"];

export function useMe() {
  const api = useApi();
  return useQuery({ queryKey: meQueryKey, queryFn: () => api.getMe() });
}
