/**
 * Server-side mirror of src/lib/security/pathGuard. We DO NOT trust the
 * client's validation — every Lambda handler re-validates on entry.
 *
 * `assertSafeString` is the single rejection point for traversal
 * sequences and ctrl-chars; structured fields are then checked against
 * the explicit PATTERNS below.
 */

export class ValidationError extends Error {
  constructor(public field: string, message: string) {
    super(message);
    this.name = 'ValidationError';
  }
}

const CTRL_RE = new RegExp('[\\x00-\\x1f\\x7f]');

const TRAVERSAL_PATTERNS: RegExp[] = [
  /\.\.[\\/]/,
  /[\\/]\.\./,
  /^\.\.$/,
  /%2e%2e/i,
  /%252e%252e/i,
  /\\u002e\\u002e/i,
  CTRL_RE,
];

const INJECTION_PATTERNS: RegExp[] = [
  /<script\b/i,
  /\bjavascript:/i,
  /on\w+\s*=/i,
  /\bdata:text\/html/i,
];

export const PATTERNS = {
  prodCode: /^[A-Z0-9]{2,6}$/,
  unit: /^(pc|ea|mtr|pkg|ltr)$/,
  username: /^[A-Za-z0-9_.-]{3,32}$/,
  name: /^[A-Za-z][A-Za-z' -]{0,49}$/,
  email: /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,24}$/,
  rightId: /^[A-Z]{3}_[A-Z0-9]{1,8}$/,
  userType: /^(SUPERADMIN|ADMIN|USER)$/,
  recordStatus: /^(ACTIVE|INACTIVE)$/,
  userId: /^[A-Za-z0-9|_-]{1,64}$/,
  freeText: new RegExp('^[^\\x00-\\x1f<>{}\\\\`]{1,200}$'),
};

export function assertSafeString(value: unknown, field: string): asserts value is string {
  if (typeof value !== 'string') throw new ValidationError(field, `${field} must be a string`);
  let decoded = value;
  try { decoded = decodeURIComponent(value); } catch { throw new ValidationError(field, `${field} is malformed`); }
  for (const re of TRAVERSAL_PATTERNS) {
    if (re.test(value) || re.test(decoded)) {
      throw new ValidationError(field, `${field} contains an illegal path sequence`);
    }
  }
  for (const re of INJECTION_PATTERNS) {
    if (re.test(value)) throw new ValidationError(field, `${field} contains illegal markup`);
  }
}

export function pattern(value: unknown, re: RegExp, field: string): string {
  if (typeof value !== 'string' || !re.test(value)) {
    throw new ValidationError(field, `${field} is not in the expected format`);
  }
  return value;
}

export function safeText(value: unknown, field: string, maxLen = 200): string {
  if (typeof value !== 'string') throw new ValidationError(field, `${field} must be text`);
  const t = value.replace(/\s+/g, ' ').trim();
  if (!t) throw new ValidationError(field, `${field} is required`);
  if (t.length > maxLen) throw new ValidationError(field, `${field} exceeds ${maxLen} chars`);
  assertSafeString(t, field);
  return t;
}

export function positiveMoney(value: unknown, field: string): number {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n) || n <= 0 || n > 9_999_999.99) {
    throw new ValidationError(field, `${field} must be > 0 and ≤ 9,999,999.99`);
  }
  return Math.round(n * 100) / 100;
}
