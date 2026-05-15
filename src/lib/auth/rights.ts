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

import type { SessionIdentity } from '@lib/auth/volatileSession';

export type Right =
  | 'PRD_ADD'
  | 'PRD_EDIT'
  | 'PRD_DEL'
  | 'REP_001'
  | 'REP_002'
  | 'ADM_USER';

export function useRights(session: SessionIdentity | null) {
  return (right: Right): boolean => session !== null && session.rights.has(right);
}

export function isAdmin(session: SessionIdentity | null): boolean {
  return session?.userType === 'ADMIN' || session?.userType === 'SUPERADMIN';
}

export function isSuperAdmin(session: SessionIdentity | null): boolean {
  return session?.userType === 'SUPERADMIN';
}

export function canSeeStamp(session: SessionIdentity | null): boolean {
  return isAdmin(session);
}

export function canSeeDeletedItems(session: SessionIdentity | null): boolean {
  return isAdmin(session);
}
