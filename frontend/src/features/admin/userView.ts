import type { UserRole } from "../../api/client";

export function roleLabel(role: UserRole): string {
  return role === "ADMIN" ? "Admin" : "User";
}

export function roleChipClass(role: UserRole): string {
  return role === "ADMIN" ? "chip chip-orange" : "chip chip-muted";
}

export function activeChipClass(isActive: boolean): string {
  return isActive ? "chip chip-green" : "chip chip-red";
}

export function formatCreatedAt(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}
