/**
 * Browser-side AES-GCM crypto for wrapping session material.
 *
 * Threat model:
 *  - We never trust the browser to keep secrets at rest. The token wrap key is
 *    derived from (a) a build-time pepper served via PUBLIC_SESSION_PEPPER and
 *    (b) a per-tab ephemeral salt that lives ONLY in memory (a JS module
 *    constant), so the wrapping key cannot be reconstructed from disk alone.
 *  - sessionStorage is scoped per-tab and auto-clears on tab close. We also
 *    wipe it on unload, on visibility-change (after inactivity), and on
 *    explicit signOut().
 *  - All ciphertext is AES-256-GCM with a fresh 12-byte IV per write.
 */

const PEPPER = (import.meta.env.PUBLIC_SESSION_PEPPER ?? '').trim();
const ENC = new TextEncoder();
const DEC = new TextDecoder();

// Per-tab ephemeral salt — fresh on every page load; never persisted.
const EPHEMERAL_SALT: Uint8Array = (() => {
  if (typeof crypto === 'undefined' || !crypto.getRandomValues) {
    return new Uint8Array(32); // SSR fallback; client always re-derives.
  }
  const s = new Uint8Array(32);
  crypto.getRandomValues(s);
  return s;
})();

let cachedKey: CryptoKey | null = null;

function b64ToBytes(b64: string): Uint8Array {
  if (typeof atob === 'undefined') return new Uint8Array();
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function bytesToB64(bytes: Uint8Array): string {
  if (typeof btoa === 'undefined') return '';
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

function concat(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

async function deriveKey(): Promise<CryptoKey> {
  if (cachedKey) return cachedKey;

  const pepperBytes = PEPPER ? b64ToBytes(PEPPER) : ENC.encode('hopepms-fallback-pepper');
  const ikm = concat(pepperBytes, EPHEMERAL_SALT);

  const baseKey = await crypto.subtle.importKey(
    'raw',
    ikm,
    { name: 'HKDF' },
    false,
    ['deriveKey'],
  );

  cachedKey = await crypto.subtle.deriveKey(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: EPHEMERAL_SALT,
      info: ENC.encode('hopepms.session.v1'),
    },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );

  return cachedKey;
}

export async function encryptString(plaintext: string): Promise<string> {
  const key = await deriveKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    ENC.encode(plaintext),
  );
  return bytesToB64(concat(iv, new Uint8Array(ct)));
}

export async function decryptString(payload: string): Promise<string | null> {
  try {
    const key = await deriveKey();
    const buf = b64ToBytes(payload);
    if (buf.length < 13) return null;
    const iv = buf.slice(0, 12);
    const ct = buf.slice(12);
    const pt = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct);
    return DEC.decode(pt);
  } catch {
    // Wrong key (new tab, new ephemeral salt) or tampered payload — treat as
    // logged out. Caller is expected to clear and prompt re-auth.
    return null;
  }
}

/** Cryptographically-random opaque ID, base64url, ~22 chars. */
export function randomId(bytes = 16): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return bytesToB64(b).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/**
 * Reset the in-memory derived key. Used on sign-out so subsequent encryption
 * calls within the same tab cannot read pre-logout ciphertext even if it
 * lingers in memory due to GC delay.
 */
export function rotateEphemeralKey(): void {
  cachedKey = null;
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
    crypto.getRandomValues(EPHEMERAL_SALT);
  }
}
