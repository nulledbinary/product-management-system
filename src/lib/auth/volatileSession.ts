/**
 * Volatile, in-memory session store for HopePMS.
 *
 * State-volatility model (ADR-001):
 *   - No localStorage, sessionStorage, IndexedDB, or cookies on the client.
 *   - The opaque session id lives only in an HttpOnly cookie set by the
 *     Spring Boot backend; the browser drops that cookie on tab close.
 *   - Identity/rights cached here in a Nano Stores `atom`, which is plain
 *     JS heap — a page refresh tears it down.
 *   - `beforeunload` / `pagehide` / `visibilitychange(hidden)` invoke the
 *     backend's /api/auth/invalidate via navigator.sendBeacon so the
 *     server-side Redis entry is dropped immediately (no orphans).
 *   - `BroadcastChannel('hopepms-auth')` propagates logout across tabs.
 */

import { atom, computed, onMount } from 'nanostores';

export type UserType = 'SUPERADMIN' | 'ADMIN' | 'USER';

export interface SessionIdentity {
  readonly userId: string;
  readonly username: string;
  readonly email: string;
  readonly userType: UserType;
  readonly rights: ReadonlySet<string>;
}

const CHANNEL = 'hopepms-auth';
const INVALIDATE_URL = '/api/auth/invalidate';
const ME_URL = '/api/auth/me';

export const $session = atom<SessionIdentity | null>(null);
export const $isAuthenticated = computed($session, s => s !== null);
export const $userType = computed($session, s => s?.userType ?? null);

export function hasRight(rightId: string): boolean {
  const s = $session.get();
  return s !== null && s.rights.has(rightId);
}

export function isAdmin(): boolean {
  const s = $session.get();
  return s?.userType === 'ADMIN' || s?.userType === 'SUPERADMIN';
}

export function isSuperAdmin(): boolean {
  return $session.get()?.userType === 'SUPERADMIN';
}

/**
 * Pulls identity from the backend using the HttpOnly session cookie.
 * Returns null if the cookie is missing or the server says it's invalid.
 */
export async function loadSession(): Promise<SessionIdentity | null> {
  try {
    const res = await fetch(ME_URL, {
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    });
    if (!res.ok) {
      $session.set(null);
      return null;
    }
    const body = (await res.json()) as {
      userId: string;
      username: string;
      email: string;
      userType: UserType;
      rights: string[];
    };
    const identity: SessionIdentity = {
      userId: body.userId,
      username: body.username,
      email: body.email ?? '',
      userType: body.userType,
      rights: new Set(body.rights ?? []),
    };
    $session.set(identity);
    return identity;
  } catch {
    $session.set(null);
    return null;
  }
}

export function clearSessionLocal(): void {
  $session.set(null);
}

export function invalidateAndRedirect(target = '/login?reason=timeout'): void {
  fireInvalidate('explicit');
  clearSessionLocal();
  broadcastLogout();
  if (typeof window !== 'undefined') window.location.replace(target);
}

function fireInvalidate(reason: string): void {
  if (typeof navigator === 'undefined' || !('sendBeacon' in navigator)) return;
  try {
    const blob = new Blob([JSON.stringify({ reason })], { type: 'application/json' });
    navigator.sendBeacon(INVALIDATE_URL, blob);
  } catch {
    /* sendBeacon is best-effort; the server-side TTL is the real safety net */
  }
}

function broadcastLogout(): void {
  try {
    const channel = new BroadcastChannel(CHANNEL);
    channel.postMessage({ type: 'logout' });
    channel.close();
  } catch {
    /* unsupported in old browsers — TTL covers it */
  }
}

onMount($session, () => {
  if (typeof window === 'undefined') return;

  let channel: BroadcastChannel | null = null;
  try {
    channel = new BroadcastChannel(CHANNEL);
    channel.onmessage = (ev: MessageEvent<{ type: string }>) => {
      if (ev.data?.type === 'logout') $session.set(null);
    };
  } catch {
    channel = null;
  }

  const onUnload = () => {
    fireInvalidate('unload');
  };
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') fireInvalidate('hidden');
  };

  window.addEventListener('beforeunload', onUnload, { capture: true });
  window.addEventListener('pagehide', onUnload, { capture: true });
  document.addEventListener('visibilitychange', onVisibility);

  return () => {
    window.removeEventListener('beforeunload', onUnload, { capture: true });
    window.removeEventListener('pagehide', onUnload, { capture: true });
    document.removeEventListener('visibilitychange', onVisibility);
    channel?.close();
  };
});

/** Kick off the Auth0 login round-trip via the backend. */
export function startLogin(connection?: 'google-oauth2', returnTo: string = '/products'): void {
  const params = new URLSearchParams();
  if (connection) params.set('connection', connection);
  params.set('returnTo', returnTo);
  window.location.assign(`/api/auth/start?${params.toString()}`);
}

/** Explicit user-initiated logout. */
export async function signOut(): Promise<void> {
  try {
    const res = await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
    });
    clearSessionLocal();
    broadcastLogout();
    if (res.ok) {
      const body = (await res.json()) as { logoutUrl?: string };
      window.location.assign(body.logoutUrl ?? '/login');
      return;
    }
  } catch {
    /* fall through */
  }
  window.location.assign('/login');
}
