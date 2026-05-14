/**
 * Encrypted, ephemeral session vault.
 *
 * Storage policy (PROJECT_PLAN §🔒 Core Security Mandates):
 *  - We use sessionStorage, NOT localStorage. sessionStorage is per-tab and
 *    is cleared automatically by the browser when the tab closes.
 *  - Values are AES-GCM encrypted with a per-tab ephemeral key
 *    (see ./crypto.ts). The wrapping key is never persisted.
 *  - On `beforeunload`, `pagehide`, and explicit `signOut()` we wipe the
 *    vault. On `visibilitychange` we kick the inactivity timer.
 *  - Nothing about the authenticated user (id token, access token, profile)
 *    is ever written to localStorage, cookies, or IndexedDB by this module.
 */

import { decryptString, encryptString, rotateEphemeralKey } from './crypto';

const STORAGE_PREFIX = 'hopepms.v1.';
const KEY_SESSION = `${STORAGE_PREFIX}session`;
const KEY_TOUCHED_AT = `${STORAGE_PREFIX}touched`;

export interface SessionPayload {
  /** Auth0 access token (JWT) used for API Gateway → Lambda calls. */
  accessToken: string;
  /** Auth0 ID token (JWT) — used only to read profile claims. */
  idToken?: string;
  /** Token expiry in unix seconds. */
  expiresAt: number;
  /** Auth0 user id (sub claim). */
  userId: string;
  /** Mapped user_type from our user table: SUPERADMIN | ADMIN | USER. */
  userType: 'SUPERADMIN' | 'ADMIN' | 'USER';
  /** Cached username for header rendering. */
  username: string;
  /** Right IDs the user holds with right_value=1. */
  rights: string[];
}

const isBrowser = (): boolean =>
  typeof window !== 'undefined' && typeof window.sessionStorage !== 'undefined';

export async function writeSession(payload: SessionPayload): Promise<void> {
  if (!isBrowser()) return;
  const ct = await encryptString(JSON.stringify(payload));
  window.sessionStorage.setItem(KEY_SESSION, ct);
  touch();
}

export async function readSession(): Promise<SessionPayload | null> {
  if (!isBrowser()) return null;
  const ct = window.sessionStorage.getItem(KEY_SESSION);
  if (!ct) return null;
  const raw = await decryptString(ct);
  if (!raw) {
    clearSession();
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as SessionPayload;
    if (parsed.expiresAt * 1000 < Date.now()) {
      clearSession();
      return null;
    }
    return parsed;
  } catch {
    clearSession();
    return null;
  }
}

export function touch(): void {
  if (!isBrowser()) return;
  window.sessionStorage.setItem(KEY_TOUCHED_AT, String(Date.now()));
}

export function lastTouchedAt(): number {
  if (!isBrowser()) return 0;
  const v = window.sessionStorage.getItem(KEY_TOUCHED_AT);
  return v ? Number(v) : 0;
}

export function clearSession(): void {
  if (!isBrowser()) return;
  // Remove every key under our namespace — defence in depth in case a future
  // feature drops a value here that we forgot to enumerate.
  const toRemove: string[] = [];
  for (let i = 0; i < window.sessionStorage.length; i++) {
    const k = window.sessionStorage.key(i);
    if (k && k.startsWith(STORAGE_PREFIX)) toRemove.push(k);
  }
  for (const k of toRemove) window.sessionStorage.removeItem(k);
  rotateEphemeralKey();
}

/**
 * Wire up lifecycle listeners that guarantee the vault is wiped at the right
 * moments. Idempotent — safe to call from every page that imports it.
 */
let listenersBound = false;
export function bindLifecycleHandlers(onForceLogout: () => void): void {
  if (!isBrowser() || listenersBound) return;
  listenersBound = true;

  const wipe = () => {
    clearSession();
  };

  // Tab/window closing — sessionStorage will also be cleared by the browser,
  // but rotating the in-memory key prevents any lingering plaintext in
  // detached references from being decrypted by future loads.
  window.addEventListener('beforeunload', wipe);
  window.addEventListener('pagehide', wipe);

  // If the user navigates away then comes back after the inactivity window,
  // force a fresh login.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      const now = Date.now();
      const idle = now - lastTouchedAt();
      const timeoutMs = sessionTimeoutMs();
      if (lastTouchedAt() > 0 && idle > timeoutMs) {
        clearSession();
        onForceLogout();
      } else {
        touch();
      }
    }
  });
}

export function sessionTimeoutMs(): number {
  const raw = Number(import.meta.env.PUBLIC_SESSION_TIMEOUT_MIN ?? '30');
  const minutes = Number.isFinite(raw) && raw > 0 ? raw : 30;
  return minutes * 60 * 1000;
}
