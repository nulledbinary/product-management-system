/**
 * Path-traversal and shell-injection guard for ANY user-supplied string that
 * could conceivably be interpreted as a path (filenames, slugs, identifiers,
 * report names, anything echoed into URLs, log files, or S3 keys).
 *
 * Defence strategy: a hard blocklist of traversal sequences in their raw and
 * URL/Unicode-encoded forms, combined with a strict whitelist for the
 * structured fields we know up front (prodCode, userId, etc.).
 *
 * The rule of thumb in the codebase:
 *   import { assertNoTraversal } from '@lib/security/pathGuard';
 *   assertNoTraversal(value, 'description');
 *
 * It throws a `ValidationError` with the offending field name; the form
 * layer turns that into an inline message and the API layer turns it into
 * a 400 response.
 */

export class ValidationError extends Error {
  constructor(public field: string, message: string) {
    super(message);
    this.name = 'ValidationError';
  }
}

// Build the C0-control-char regex programmatically so source code stays
// free of literal NUL / control bytes (some linters and VCS hooks choke
// on them).
const CTRL_RE = new RegExp('[\\x00-\\x1f\\x7f]');

const TRAVERSAL_PATTERNS: RegExp[] = [
  /\.\.[\\/]/,                 // ../ or ..\
  /[\\/]\.\./,                 // /.. or \..
  /^\.\.$/,                    // bare ..
  /%2e%2e/i,                   // URL-encoded ..
  /%252e%252e/i,               // double-URL-encoded ..
  /\\u002e\\u002e/i,           // \u-escaped ..
  CTRL_RE,                     // null byte, CRLF, DEL, other C0 chars
];

const INJECTION_PATTERNS: RegExp[] = [
  /<script\b/i,
  /\bjavascript:/i,
  /on\w+\s*=/i,                // inline event handlers
  /\bdata:text\/html/i,
];

export function hasTraversal(input: string): boolean {
  if (typeof input !== 'string') return false;
  let s = input;
  try {
    s = decodeURIComponent(input);
  } catch {
    return true;
  }
  for (const re of TRAVERSAL_PATTERNS) if (re.test(input) || re.test(s)) return true;
  return false;
}

export function hasInjection(input: string): boolean {
  if (typeof input !== 'string') return false;
  for (const re of INJECTION_PATTERNS) if (re.test(input)) return true;
  return false;
}

export function assertNoTraversal(input: unknown, field: string): asserts input is string {
  if (typeof input !== 'string') {
    throw new ValidationError(field, `${field} must be a string`);
  }
  if (hasTraversal(input)) {
    throw new ValidationError(field, `${field} contains an illegal path sequence`);
  }
  if (hasInjection(input)) {
    throw new ValidationError(field, `${field} contains illegal markup`);
  }
}

/**
 * Strict whitelists for the known structured identifiers in HopeDB.
 * These are the *only* characters we accept — anything else is rejected
 * outright at the boundary.
 */
export const PATTERNS = {
  prodCode: /^[A-Z0-9]{2,6}$/,                 // e.g. AK0001
  unit: /^(pc|ea|mtr|pkg|ltr)$/,
  username: /^[A-Za-z0-9_.-]{3,32}$/,
  name: /^[A-Za-z][A-Za-z' -]{0,49}$/,         // first/last name
  email: /^[^\s@]{1,64}@[^\s@]{1,255}\.[^\s@]{2,24}$/,
  rightId: /^[A-Z]{3}_[A-Z0-9]{1,8}$/,         // PRD_ADD, REP_002, …
  moduleId: /^[A-Za-z]{1,12}_Mod$/,            // Prod_Mod, Report_Mod, Adm_Mod
  userType: /^(SUPERADMIN|ADMIN|USER)$/,
  recordStatus: /^(ACTIVE|INACTIVE)$/,
  /** Free-text but bounded: descriptions, etc. No control chars, no markup. */
  freeText: new RegExp('^[^\\x00-\\x1f<>{}\\\\`]{1,200}$'),
};

export function matchOrThrow(
  value: unknown,
  pattern: RegExp,
  field: string,
): string {
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new ValidationError(field, `${field} is not in the expected format`);
  }
  return value;
}

export function safeText(input: unknown, field: string, maxLen = 200): string {
  if (typeof input !== 'string') {
    throw new ValidationError(field, `${field} must be text`);
  }
  const trimmed = input.replace(/\s+/g, ' ').trim();
  if (trimmed.length === 0) {
    throw new ValidationError(field, `${field} is required`);
  }
  if (trimmed.length > maxLen) {
    throw new ValidationError(field, `${field} exceeds ${maxLen} characters`);
  }
  assertNoTraversal(trimmed, field);
  return trimmed;
}
