import { useQuery } from "@tanstack/react-query";
import { useApi } from "../api/ApiContext";

export const setupStatusQueryKey = ["setupStatus"];

export function useSetupStatus() {
  const api = useApi();
  return useQuery({ queryKey: setupStatusQueryKey, queryFn: () => api.getSetupStatus() });
}
