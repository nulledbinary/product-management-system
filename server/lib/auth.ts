/**
 * Auth0 JWT verifier for API Gateway → Lambda. Caches JWKS in memory.
 * - RS256 only; rejects anything else.
 * - Verifies iss, aud, exp, nbf.
 * - Returns a minimal { sub, email } claim object.
 */
import { createPublicKey, createVerify } from 'node:crypto';

interface JwtHeader { alg: string; kid: string; typ?: string }
interface JwtClaims {
  sub: string;
  iss: string;
  aud: string | string[];
  exp: number;
  nbf?: number;
  email?: string;
  email_verified?: boolean;
  [k: string]: unknown;
}

interface Jwk { kid: string; kty: string; n: string; e: string; alg?: string; use?: string }

let cachedJwks: { fetchedAt: number; keys: Jwk[] } | null = null;
const JWKS_TTL_MS = 10 * 60 * 1000;

async function fetchJwks(): Promise<Jwk[]> {
  const now = Date.now();
  if (cachedJwks && now - cachedJwks.fetchedAt < JWKS_TTL_MS) return cachedJwks.keys;
  const url = process.env.AUTH0_JWKS_URI;
  if (!url) throw new Error('AUTH0_JWKS_URI is not set');
  const res = await fetch(url);
  if (!res.ok) throw new Error(`JWKS fetch failed: ${res.status}`);
  const json = (await res.json()) as { keys: Jwk[] };
  cachedJwks = { fetchedAt: now, keys: json.keys };
  return json.keys;
}

function base64UrlToBuffer(s: string): Buffer {
  const pad = 4 - (s.length % 4 || 4);
  const b64 = (s + (pad < 4 ? '='.repeat(pad) : '')).replace(/-/g, '+').replace(/_/g, '/');
  return Buffer.from(b64, 'base64');
}

function decodePart<T>(part: string): T {
  return JSON.parse(base64UrlToBuffer(part).toString('utf8')) as T;
}

function buildKey(jwk: Jwk): string {
  const keyObj = createPublicKey({ key: jwk as unknown as object, format: 'jwk' });
  return keyObj.export({ format: 'pem', type: 'spki' }) as string;
}

export interface VerifiedToken {
  sub: string;
  email?: string;
  claims: JwtClaims;
}

export async function verifyAuthHeader(authHeader: string | undefined): Promise<VerifiedToken> {
  if (!authHeader || !authHeader.toLowerCase().startsWith('bearer ')) {
    throw new Error('missing_bearer');
  }
  const token = authHeader.slice(7).trim();
  const [h, p, s] = token.split('.');
  if (!h || !p || !s) throw new Error('malformed_token');

  const header = decodePart<JwtHeader>(h);
  if (header.alg !== 'RS256') throw new Error('unsupported_alg');

  const keys = await fetchJwks();
  const jwk = keys.find((k) => k.kid === header.kid);
  if (!jwk) throw new Error('unknown_kid');

  const verifier = createVerify('RSA-SHA256');
  verifier.update(`${h}.${p}`);
  verifier.end();
  const sig = base64UrlToBuffer(s);
  if (!verifier.verify(buildKey(jwk), sig)) throw new Error('bad_signature');

  const claims = decodePart<JwtClaims>(p);
  const now = Math.floor(Date.now() / 1000);
  if (claims.exp < now) throw new Error('expired');
  if (claims.nbf && claims.nbf > now) throw new Error('not_yet_valid');

  const issuer = process.env.AUTH0_ISSUER ?? '';
  if (claims.iss !== issuer) throw new Error('bad_issuer');

  const audience = process.env.AUTH0_AUDIENCE ?? '';
  const audOk = Array.isArray(claims.aud) ? claims.aud.includes(audience) : claims.aud === audience;
  if (!audOk) throw new Error('bad_audience');

  return { sub: claims.sub, email: claims.email, claims };
}
