/**
 * Pure-function input validation helpers.
 *
 * These run on the client to short-circuit obviously-bad input before it ever
 * reaches the network. The Spring Boot backend re-validates everything; the
 * client copies are convenience, not security.
 */

export class ValidationError extends Error {
  constructor(public field: string, message: string) {
    super(`${field}: ${message}`);
    this.name = 'ValidationError';
  }
}

export const PATTERNS = {
  prodCode:     /^[A-Z]{2}\d{4}$/,
  unit:         /^(pc|ea|mtr|pkg|ltr)$/,
  recordStatus: /^(ACTIVE|INACTIVE)$/,
  rightId:      /^[A-Z]{3}_[A-Z0-9_]{1,10}$/,
  email:        /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,24}$/,
  userId:       /^[A-Za-z0-9|_-]{1,64}$/,
  username:     /^[A-Za-z0-9._-]{3,32}$/,
  name:         /^[\p{L}\p{M}'\- ]{1,50}$/u,
} as const;

export function hasTraversal(value: string): boolean {
  if (!value) return false;
  return (
    value.includes('..') ||
    value.includes('\\') ||
    value.includes('\0') ||
    /%2e%2e/i.test(value)
  );
}

export function matchOrThrow(value: string, regex: RegExp, field: string): string {
  if (typeof value !== 'string' || !regex.test(value)) {
    throw new ValidationError(field, `value does not match ${regex}`);
  }
  return value;
}

export function safeText(value: string, field: string, max: number): string {
  if (typeof value !== 'string') {
    throw new ValidationError(field, 'must be a string');
  }
  const trimmed = value.trim();
  if (trimmed.length === 0) {
    throw new ValidationError(field, 'must not be empty');
  }
  if (trimmed.length > max) {
    throw new ValidationError(field, `must be ≤ ${max} characters`);
  }
  if (hasTraversal(trimmed)) {
    throw new ValidationError(field, 'contains a forbidden sequence');
  }
  // Strip ASCII control characters (except tab/newline).
  if (/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(trimmed)) {
    throw new ValidationError(field, 'contains control characters');
  }
  return trimmed;
}
