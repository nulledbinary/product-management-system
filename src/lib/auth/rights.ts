/**
 * Right-evaluation helpers that read from the volatile session atom.
 *
 * The source of truth lives in [[volatileSession]]; this module only adapts
 * it for components that want a synchronous "can I show this button?" check.
 */

import type { SessionIdentity } from './volatileSession';

export type Right =
  | 'PRD_ADD'
  | 'PRD_EDIT'
  | 'PRD_DEL'
  | 'REP_001'
  | 'REP_002'
  | 'ADM_USER';

export function useRights(session: SessionIdentity | null) {
  return (right: Right): boolean => !!session && session.rights.has(right);
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
