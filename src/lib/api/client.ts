/**
 * Thin fetch wrapper for backend (Spring Boot / BFF) calls.
 * - Authentication rides on the HttpOnly `HPMS_SID` cookie set by the
 *   backend at /api/auth/callback; we never see or carry a Bearer token.
 * - Re-validates traversal-safe path segments on the way out.
 * - Surfaces a typed `ApiError` so call sites can branch on `.status`.
 */

import { hasTraversal, ValidationError } from '@lib/security/pathGuard';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

function base(): string {
  return String(import.meta.env.PUBLIC_API_BASE_URL ?? '/api').replace(/\/+$/, '');
}

function joinPath(path: string): string {
  if (hasTraversal(path)) {
    throw new ValidationError('path', 'path contains a traversal sequence');
  }
  return path.startsWith('/') ? path : `/${path}`;
}

interface RequestOpts {
  method?: 'GET' | 'POST' | 'PATCH' | 'DELETE';
  body?: unknown;
  signal?: AbortSignal;
}

export async function request<T>(path: string, opts: RequestOpts = {}): Promise<T> {
  const res = await fetch(`${base()}${joinPath(path)}`, {
    method: opts.method ?? 'GET',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
    credentials: 'include',
    cache: 'no-store',
  });

  const text = await res.text();
  const json = text ? safeParse(text) : null;

  if (!res.ok) {
    const code = (json as { code?: string } | null)?.code ?? 'request_failed';
    const msg = (json as { message?: string } | null)?.message ?? res.statusText;
    throw new ApiError(res.status, code, msg);
  }

  return json as T;
}

function safeParse(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}
