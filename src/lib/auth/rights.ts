/**
 * Rights-evaluation helpers. The source of truth is the API-returned
 * `rights: string[]` array (e.g., ['PRD_ADD', 'PRD_EDIT', 'REP_001']).
 *
 * Pattern:
 *   const can = useRights(session);
 *   {can('PRD_ADD') && <AddButton />}
 *
 * We deliberately re-check on the server too — the UI gate is convenience,
 * the Lambda + RLS gate is the security boundary.
 */

import type { SessionPayload } from '@lib/security/session';

export type Right =
  | 'PRD_ADD'
  | 'PRD_EDIT'
  | 'PRD_DEL'
  | 'REP_001'
  | 'REP_002'
  | 'ADM_USER';

export function useRights(session: SessionPayload | null) {
  const set = new Set(session?.rights ?? []);
  return (right: Right): boolean => set.has(right);
}

export function isAdmin(session: SessionPayload | null): boolean {
  return session?.userType === 'ADMIN' || session?.userType === 'SUPERADMIN';
}

export function isSuperAdmin(session: SessionPayload | null): boolean {
  return session?.userType === 'SUPERADMIN';
}

export function canSeeStamp(session: SessionPayload | null): boolean {
  return isAdmin(session);
}

export function canSeeDeletedItems(session: SessionPayload | null): boolean {
  return isAdmin(session);
}
