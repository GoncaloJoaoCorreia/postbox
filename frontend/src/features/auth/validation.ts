export interface FieldErrors {
  email?: string;
  password?: string;
}

export const minPasswordLength = 8;

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function emailError(email: string): string | null {
  return emailPattern.test(email.trim()) ? null : "Enter a valid email address";
}

export function loginPasswordError(password: string): string | null {
  return password.length > 0 ? null : "Enter your password";
}

export function newPasswordError(password: string): string | null {
  return password.length >= minPasswordLength
    ? null
    : `The password must be at least ${minPasswordLength} characters long`;
}
