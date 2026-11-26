import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useApi } from "../../api/ApiContext";
import type { CreateParcelRequest, DetectRequest, UpdateParcelRequest } from "../../api/client";

export const parcelsQueryKey = ["parcels"];

export function useParcels() {
  const api = useApi();
  return useQuery({ queryKey: parcelsQueryKey, queryFn: () => api.listParcels() });
}

export function useDetectCarrier() {
  const api = useApi();
  return useMutation({ mutationFn: (body: DetectRequest) => api.detectCarrier(body) });
}

export function useCreateParcel() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateParcelRequest) => api.createParcel(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: parcelsQueryKey }),
  });
}

export function useUpdateParcel() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateParcelRequest }) =>
      api.updateParcel(id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: parcelsQueryKey }),
  });
}

export function useDeleteParcel() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.deleteParcel(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: parcelsQueryKey }),
  });
}

export function useRefreshParcel() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.refreshParcel(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: parcelsQueryKey }),
  });
}
