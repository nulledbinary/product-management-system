/**
 * Auth0 SPA client wrapper.
 *
 * - Configures the SDK with `cacheLocation: 'memory'` so refresh tokens
 *   never touch localStorage. Anything we *do* need to persist for the tab
 *   lifetime (the access token, profile, rights) is encrypted by
 *   `@lib/security/session` and lives in sessionStorage only.
 * - Exposes a tiny imperative API: login (email or Google), handleCallback,
 *   logout, getAccessToken (refreshing if needed).
 */

import { Auth0Client, type Auth0ClientOptions } from '@auth0/auth0-spa-js';
import {
  bindLifecycleHandlers,
  clearSession,
  readSession,
  writeSession,
  type SessionPayload,
} from '@lib/security/session';

let client: Auth0Client | null = null;

function env(name: string): string {
  const v = import.meta.env[name as keyof ImportMetaEnv];
  if (!v) throw new Error(`Missing env var: ${name}`);
  return String(v);
}

function options(): Auth0ClientOptions {
  return {
    domain: env('PUBLIC_AUTH0_DOMAIN'),
    clientId: env('PUBLIC_AUTH0_CLIENT_ID'),
    authorizationParams: {
      audience: env('PUBLIC_AUTH0_AUDIENCE'),
      redirect_uri: env('PUBLIC_AUTH0_REDIRECT_URI'),
      scope: 'openid profile email offline_access',
    },
    // CRITICAL: keep refresh tokens in JS memory only, never on disk.
    cacheLocation: 'memory',
    useRefreshTokens: true,
    useRefreshTokensFallback: false,
  };
}

export async function getClient(): Promise<Auth0Client> {
  if (client) return client;
  client = new Auth0Client(options());
  bindLifecycleHandlers(() => {
    window.location.href = '/login?reason=timeout';
  });
  return client;
}

export async function loginWithEmail(email: string, password: string): Promise<void> {
  // Auth0's Resource Owner Password flow is not supported by the SPA client;
  // the production flow goes through Universal Login (redirect). We expose a
  // small wrapper so the page can pre-fill `login_hint`.
  const c = await getClient();
  await c.loginWithRedirect({
    authorizationParams: { login_hint: email },
  });
  // Password is intentionally not forwarded — Auth0 Universal Login owns it.
  // Caller's password value is wiped after this call returns; see LoginForm.
  void password;
}

export async function loginWithGoogle(): Promise<void> {
  const c = await getClient();
  await c.loginWithRedirect({
    authorizationParams: { connection: 'google-oauth2' },
  });
}

export async function handleRedirectCallback(): Promise<SessionPayload | null> {
  const c = await getClient();
  await c.handleRedirectCallback();
  const user = await c.getUser();
  if (!user || !user.sub) return null;

  const accessToken = await c.getTokenSilently();
  const idTokenClaims = await c.getIdTokenClaims();
  const expSec =
    typeof idTokenClaims?.exp === 'number'
      ? idTokenClaims.exp
      : Math.floor(Date.now() / 1000) + 60 * 60;

  // The provisioning record (user_type + rights) is fetched from our API,
  // not from the Auth0 token, because rights can change after issuance.
  const profile = await fetchProvisioning(accessToken);

  const payload: SessionPayload = {
    accessToken,
    idToken: idTokenClaims?.__raw,
    expiresAt: expSec,
    userId: user.sub,
    userType: profile.userType,
    username: profile.username || (user.nickname ?? user.email?.split('@')[0] ?? 'user'),
    rights: profile.rights,
  };

  await writeSession(payload);
  return payload;
}

async function fetchProvisioning(accessToken: string): Promise<{
  userType: SessionPayload['userType'];
  username: string;
  rights: string[];
}> {
  const base = String(import.meta.env.PUBLIC_API_BASE_URL ?? '/api');
  const res = await fetch(`${base}/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) {
    // 403 here typically means INACTIVE — caller will route to /login?reason=not_activated.
    throw new Error(`provisioning_fetch_failed:${res.status}`);
  }
  const json = (await res.json()) as {
    user_type: 'SUPERADMIN' | 'ADMIN' | 'USER';
    username: string;
    rights: string[];
  };
  return { userType: json.user_type, username: json.username, rights: json.rights };
}

export async function getAccessToken(): Promise<string | null> {
  const session = await readSession();
  if (session && session.expiresAt * 1000 > Date.now() + 30_000) {
    return session.accessToken;
  }
  // Refresh via Auth0 (in-memory cache holds the refresh token).
  try {
    const c = await getClient();
    const fresh = await c.getTokenSilently();
    if (session) {
      await writeSession({ ...session, accessToken: fresh });
    }
    return fresh;
  } catch {
    return null;
  }
}

export async function signOut(): Promise<void> {
  clearSession();
  try {
    const c = await getClient();
    await c.logout({
      logoutParams: { returnTo: window.location.origin + '/login' },
    });
  } catch {
    window.location.href = '/login';
  }
}
