/**
 * Server-side rights lookup. Always check rights at the Lambda layer; the
 * UI gates are a UX nicety, not a security boundary.
 */
import { query } from '../db/pool';

export interface AppUser {
  userId: string;
  username: string;
  user_type: 'SUPERADMIN' | 'ADMIN' | 'USER';
  record_status: 'ACTIVE' | 'INACTIVE';
  rights: string[];
}

export async function loadAppUser(authSub: string): Promise<AppUser | null> {
  const userRows = await query<{
    userId: string;
    username: string;
    user_type: AppUser['user_type'];
    record_status: AppUser['record_status'];
  }>(
    `SET search_path = hopedb, public;
     SELECT "userId", username, user_type, record_status
     FROM "user" WHERE "userId" = $1`,
    [authSub],
  );
  if (userRows.length === 0) return null;
  const u = userRows[0];

  const rights = await query<{ Right_ID: string }>(
    `SELECT "Right_ID" FROM "UserModule_Rights"
     WHERE userid = $1 AND "Right_value" = 1 AND "Record_status" = 'ACTIVE'`,
    [authSub],
  );
  return {
    userId: u.userId,
    username: u.username,
    user_type: u.user_type,
    record_status: u.record_status,
    rights: rights.map((r) => r.Right_ID),
  };
}

export function requireRight(user: AppUser, right: string): void {
  if (!user.rights.includes(right)) {
    throw Object.assign(new Error('forbidden'), { status: 403, code: 'forbidden_right', right });
  }
}

export function requireAdmin(user: AppUser): void {
  if (user.user_type !== 'ADMIN' && user.user_type !== 'SUPERADMIN') {
    throw Object.assign(new Error('forbidden'), { status: 403, code: 'admin_required' });
  }
}

export function isAdmin(user: AppUser): boolean {
  return user.user_type === 'ADMIN' || user.user_type === 'SUPERADMIN';
}
