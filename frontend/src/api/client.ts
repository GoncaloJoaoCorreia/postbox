import type { components } from "./schema";

export type User = components["schemas"]["User"];
export type Parcel = components["schemas"]["Parcel"];
export type ParcelEvent = components["schemas"]["ParcelEvent"];
export type LoginResponse = components["schemas"]["LoginResponse"];
export type SetupStatusResponse = components["schemas"]["SetupStatusResponse"];
export type CreateParcelRequest = components["schemas"]["CreateParcelRequest"];
export type UpdateParcelRequest = components["schemas"]["UpdateParcelRequest"];
export type DetectRequest = components["schemas"]["DetectRequest"];
export type DetectResponse = components["schemas"]["DetectResponse"];
export type Carrier = components["schemas"]["Carrier"];
export type TrackingStatus = components["schemas"]["TrackingStatus"];
export type DetectionMethod = components["schemas"]["DetectionMethod"];
export type Metadata = components["schemas"]["Metadata"];
export type MetadataField = components["schemas"]["MetadataField"];
export type ErrorResponse = components["schemas"]["ErrorResponse"];

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields?: MetadataField[];

  constructor(status: number, code: string, message: string, fields?: MetadataField[]) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
  }
}

export interface ApiClientOptions {
  baseUrl: string;
  getToken: () => string | null;
  onUnauthorized?: () => void;
}

export type ApiClient = ReturnType<typeof createApiClient>;

export function createApiClient({ baseUrl, getToken, onUnauthorized }: ApiClientOptions) {
  async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const token = getToken();
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      const error = await toApiError(response);
      if (error.status === 401 && path !== "/api/v1/auth/login") onUnauthorized?.();
      throw error;
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  }

  return {
    get: <T>(path: string) => request<T>("GET", path),
    post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
    patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
    delete: <T>(path: string) => request<T>("DELETE", path),
    getSetupStatus: () => request<SetupStatusResponse>("GET", "/api/v1/setup/status"),
    createFirstAdmin: (email: string, password: string) =>
      request<User>("POST", "/api/v1/setup", { email, password }),
    login: (email: string, password: string) =>
      request<LoginResponse>("POST", "/api/v1/auth/login", { email, password }),
    getMe: () => request<User>("GET", "/api/v1/me"),
    listParcels: () => request<Parcel[]>("GET", "/api/v1/parcels"),
    createParcel: (body: CreateParcelRequest) => request<Parcel>("POST", "/api/v1/parcels", body),
    getParcel: (id: string) => request<Parcel>("GET", `/api/v1/parcels/${id}`),
    updateParcel: (id: string, body: UpdateParcelRequest) =>
      request<Parcel>("PATCH", `/api/v1/parcels/${id}`, body),
    deleteParcel: (id: string) => request<void>("DELETE", `/api/v1/parcels/${id}`),
    listParcelEvents: (id: string) => request<ParcelEvent[]>("GET", `/api/v1/parcels/${id}/events`),
    detectCarrier: (body: DetectRequest) =>
      request<DetectResponse>("POST", "/api/v1/parcels/detect", body),
    refreshParcel: (id: string) => request<Parcel>("POST", `/api/v1/parcels/${id}/refresh`),
  };
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback = `Request failed with status ${response.status}`;
  const data = (await response.json().catch(() => null)) as ErrorResponse | null;
  const error = data?.error;
  if (error && typeof error.code === "string" && typeof error.message === "string") {
    const fields = Array.isArray(error.fields) ? error.fields : undefined;
    return new ApiError(response.status, error.code, error.message, fields);
  }
  return new ApiError(response.status, "request-failed", fallback);
}
