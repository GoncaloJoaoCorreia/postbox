import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useApi } from "../../api/ApiContext";
import type { CreateUserRequest, UpdateUserRequest } from "../../api/client";

export const usersQueryKey = ["admin", "users"];

export function useUsers() {
  const api = useApi();
  return useQuery({ queryKey: usersQueryKey, queryFn: () => api.listUsers() });
}

export function useCreateUser() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUserRequest) => api.createUser(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: usersQueryKey }),
  });
}

export function useUpdateUser() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateUserRequest }) => api.updateUser(id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: usersQueryKey }),
  });
}
